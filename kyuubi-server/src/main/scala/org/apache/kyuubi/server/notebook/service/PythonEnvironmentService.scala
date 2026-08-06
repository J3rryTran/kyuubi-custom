/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.notebook.service

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.python.{PythonEnvironmentBuilder, RequirementSpec}
import org.apache.kyuubi.server.notebook.store.NotebookStore
import org.apache.kyuubi.util.ThreadUtils

/**
 * Per-user Python environments and the package operations that build them.
 *
 * Two rules shape everything here. An active revision is never modified in place, so a failed
 * install leaves the previous one serving and a rollback is always possible. And an environment
 * belongs to exactly one user: there is no path by which another principal can install into it,
 * because installing into someone's environment is executing code as them.
 */
class PythonEnvironmentService(
    conf: KyuubiConf,
    store: NotebookStore,
    builder: PythonEnvironmentBuilder) extends Logging {

  private val allowlist = conf.get(PYTHON_PACKAGE_ALLOWLIST).map(RequirementSpec.normalize).toSet
  private val denylist = conf.get(PYTHON_PACKAGE_DENYLIST).map(RequirementSpec.normalize).toSet
  private val maxPackages = conf.get(PYTHON_PACKAGE_MAX_COUNT)
  private val maxPerUser = conf.get(PYTHON_ENVIRONMENT_MAX_PER_USER)
  private val keepRevisions = conf.get(PYTHON_ENVIRONMENT_KEEP_REVISIONS)
  private val rootDir: Path = PythonEnvironmentBuilder.rootDir(conf)

  private lazy val workers: ExecutorService =
    ThreadUtils.newDaemonFixedThreadPool(2, "notebook-python-package")

  /**
   * One in-flight operation per environment. Two concurrent installs would race to activate and
   * could leave the environment pointing at a revision that never saw the other's packages.
   */
  private val busy = new ConcurrentHashMap[String, String]()

  def available: Boolean = conf.get(PYTHON_ENABLED) && builder.available

  // -------------------------------------------------------------------------------------------
  // Environments
  // -------------------------------------------------------------------------------------------

  def create(
      principal: NotebookPrincipal,
      request: CreatePythonEnvironmentRequest): PythonEnvironment = {
    requireAvailable()
    val name = NotebookPaths.validateName(request.getName)
    val existing = store.listEnvironments(principal.user)
    if (existing.exists(_.name == name)) {
      throw NotebookException.pathConflict(s"an environment named $name already exists")
    }
    if (existing.size >= maxPerUser) {
      throw NotebookException.invalid(s"a user may own at most $maxPerUser environments")
    }
    val now = System.currentTimeMillis()
    val environment = PythonEnvironment(
      id = UUID.randomUUID().toString,
      owner = principal.user,
      name = name,
      runtimeSpecId = Option(request.getRuntimeSpecId).filter(_.nonEmpty)
        .getOrElse(PythonEnvironmentService.DEFAULT_SPEC_ID),
      pythonVersion = builder.pythonVersion,
      activeRevisionId = None,
      state = PythonEnvironmentState.CREATING,
      createdAt = now,
      createdBy = principal.user,
      updatedAt = now,
      updatedBy = principal.user,
      version = 1L)
    store.createEnvironment(environment)
    // An empty first revision gives the environment something to bind runtimes to before any
    // package has been requested.
    val operation = submitOperation(
      principal,
      environment,
      PackageAction.REBUILD,
      Seq.empty,
      None)
    debug(s"Environment ${environment.id} is being built by operation ${operation.id}")
    environment
  }

  def list(principal: NotebookPrincipal): Seq[PythonEnvironment] =
    store.listEnvironments(principal.user)

  def load(environmentId: String): PythonEnvironment =
    store.getEnvironment(environmentId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.PYTHON_ENVIRONMENT_NOT_FOUND,
        s"python environment $environmentId was not found")
    }

  def require(principal: NotebookPrincipal, environmentId: String): PythonEnvironment = {
    val environment = load(environmentId)
    if (!principal.admin && environment.owner != principal.user) {
      // Reported as missing rather than forbidden, so ids cannot be probed.
      throw NotebookException.notFound(
        NotebookErrorCode.PYTHON_ENVIRONMENT_NOT_FOUND,
        s"python environment $environmentId was not found")
    }
    environment
  }

  def rename(
      principal: NotebookPrincipal,
      environmentId: String,
      request: UpdatePythonEnvironmentRequest): PythonEnvironment = {
    val environment = require(principal, environmentId)
    val name = Option(request.getName).map(NotebookPaths.validateName).getOrElse(environment.name)
    Option(request.getVersion).map(_.longValue()).foreach { requested =>
      if (requested != environment.version) {
        throw NotebookException.versionConflict("the environment was modified since it was read")
      }
    }
    val updated = environment.copy(
      name = name,
      updatedAt = System.currentTimeMillis(),
      updatedBy = principal.user,
      version = environment.version + 1)
    if (!store.updateEnvironment(updated, environment.version)) {
      throw NotebookException.versionConflict("the environment was modified concurrently")
    }
    updated
  }

  def delete(principal: NotebookPrincipal, environmentId: String): Unit = {
    val environment = require(principal, environmentId)
    requireIdle(environment)
    store.listEnvironmentRevisions(environment.id).foreach { revision =>
      revision.internalEnvironmentLocation.foreach(location =>
        builder.deleteRecursively(java.nio.file.Paths.get(location)))
    }
    store.deleteEnvironment(environment.id)
  }

  def revisions(
      principal: NotebookPrincipal,
      environmentId: String): Seq[PythonEnvironmentRevisionView] = {
    val environment = require(principal, environmentId)
    store.listEnvironmentRevisions(environment.id).map { revision =>
      PythonEnvironmentRevisionView(
        revision,
        environment.activeRevisionId.contains(revision.id))
    }
  }

  /**
   * What the environment holds. Packages the image provides are listed too, marked so a client
   * can show that they are not the user's to remove.
   */
  def packages(principal: NotebookPrincipal, environmentId: String): PythonPackageListView = {
    val environment = require(principal, environmentId)
    val base = builder.baseInstalledPackages.map(entry =>
      InstalledPackage(entry.name, entry.version, fromImage = true))
    activeRevision(environment) match {
      case None => PythonPackageListView(None, base, Seq.empty)
      case Some(revision) =>
        val own = revision.resolvedPackages.flatMap { line =>
          line.split("==", 2) match {
            case Array(name, version) => Some(InstalledPackage(name, version))
            case _ => None
          }
        }
        val ownNames = own.map(entry => RequirementSpec.normalize(entry.name)).toSet
        PythonPackageListView(
          Some(revision.revisionNumber),
          // A user's own version shadows the image's, so the image entry is dropped from view.
          own ++ base.filterNot(entry =>
            ownNames.contains(RequirementSpec.normalize(entry.name))),
          revision.requirements)
    }
  }

  def activeRevision(environment: PythonEnvironment): Option[PythonEnvironmentRevision] =
    environment.activeRevisionId.flatMap(store.getEnvironmentRevision)

  /** The interpreter a runtime should launch, or None when nothing is activated yet. */
  def activeInterpreter(environment: PythonEnvironment): Option[Path] =
    activeRevision(environment)
      .filter(_.state == PythonEnvironmentState.READY)
      .flatMap(_.internalEnvironmentLocation)
      .map(location => builder.interpreterOf(java.nio.file.Paths.get(location)))

  // -------------------------------------------------------------------------------------------
  // Package operations
  // -------------------------------------------------------------------------------------------

  def install(
      principal: NotebookPrincipal,
      environmentId: String,
      request: PackageOperationRequest): PythonPackageOperation =
    packageOperation(principal, environmentId, PackageAction.INSTALL, request)

  def uninstall(
      principal: NotebookPrincipal,
      environmentId: String,
      request: PackageOperationRequest): PythonPackageOperation =
    packageOperation(principal, environmentId, PackageAction.UNINSTALL, request)

  def rebuild(
      principal: NotebookPrincipal,
      environmentId: String): PythonPackageOperation = {
    val environment = require(principal, environmentId)
    requireIdle(environment)
    submitOperation(principal, environment, PackageAction.REBUILD, Seq.empty, None)
  }

  private def packageOperation(
      principal: NotebookPrincipal,
      environmentId: String,
      action: PackageAction.Value,
      request: PackageOperationRequest): PythonPackageOperation = {
    requireAvailable()
    val environment = require(principal, environmentId)
    val requestId = Option(request.getClientRequestId).map(_.trim).filter(_.nonEmpty)
    val raw = Option(request.getPackages).map(_.asScala.toSeq).getOrElse(Seq.empty)
    if (raw.isEmpty) {
      throw NotebookException.invalid("at least one package must be provided")
    }
    // Parsing happens before anything is scheduled, so a malformed requirement is a plain 400
    // rather than a failed background operation.
    val specs = raw.map(RequirementSpec.parse)
    if (action == PackageAction.UNINSTALL) {
      // Removing it from the requirement list would rebuild without it and still leave it
      // importable, because it comes from the image. Saying so beats a silent no-op.
      specs.map(spec => RequirementSpec.normalize(spec.name))
        .find(builder.basePackages.contains)
        .foreach { name =>
          throw new NotebookException(
            NotebookErrorCode.PYTHON_PACKAGE_DENIED,
            s"$name is provided by the server image and cannot be removed")
        }
    } else {
      specs.foreach(spec => requirePermitted(spec.name))
    }

    requestId.flatMap(id => store.findPackageOperationByRequestId(principal.user, id)) match {
      case Some(existing) if existing.requestedPackages == specs.map(_.render) => existing
      case Some(_) =>
        throw new NotebookException(
          NotebookErrorCode.VERSION_CONFLICT,
          "clientRequestId was already used with a different request")
      case None =>
        requireIdle(environment)
        submitOperation(principal, environment, action, specs, requestId)
    }
  }

  private def submitOperation(
      principal: NotebookPrincipal,
      environment: PythonEnvironment,
      action: PackageAction.Value,
      specs: Seq[RequirementSpec],
      requestId: Option[String]): PythonPackageOperation = {
    val now = System.currentTimeMillis()
    val operation = PythonPackageOperation(
      id = UUID.randomUUID().toString,
      environmentId = environment.id,
      baseRevisionId = environment.activeRevisionId,
      targetRevisionId = None,
      action = action,
      requestedPackages = specs.map(_.render),
      state = PackageOperationState.QUEUED,
      submittedAt = now,
      startedAt = None,
      finishedAt = None,
      submittedBy = principal.user,
      clientRequestId = requestId,
      errorCode = None,
      errorMessage = None,
      operationLog = None,
      version = 1L)
    store.createPackageOperation(operation)
    if (busy.putIfAbsent(environment.id, operation.id) != null) {
      failOperation(
        operation,
        NotebookErrorCode.PYTHON_ENVIRONMENT_BUSY.toString,
        "another package operation is already running for this environment",
        None)
      throw new NotebookException(
        NotebookErrorCode.PYTHON_ENVIRONMENT_BUSY,
        "another package operation is already running for this environment")
    }
    workers.submit(new Runnable {
      override def run(): Unit = execute(operation, environment, specs)
    })
    operation
  }

  /**
   * Builds a candidate revision and activates it only once it is known good. The previous
   * revision keeps serving until the swap, and stays on disk afterwards for rollback.
   */
  private def execute(
      operation: PythonPackageOperation,
      environment: PythonEnvironment,
      specs: Seq[RequirementSpec]): Unit = {
    val started = operation.copy(
      state = PackageOperationState.RUNNING,
      startedAt = Some(System.currentTimeMillis()),
      version = operation.version + 1)
    store.updatePackageOperation(started, operation.version)
    try {
      val base = activeRevision(environment)
      val requirements = nextRequirements(operation.action, base, specs)
      if (requirements.size > maxPackages) {
        throw NotebookException.invalid(
          s"an environment may hold at most $maxPackages requested packages")
      }
      val revisionNumber = store.nextEnvironmentRevisionNumber(environment.id)
      val revisionId = UUID.randomUUID().toString
      val location = rootDir
        .resolve(sanitize(environment.owner))
        .resolve(environment.id)
        .resolve(s"rev-$revisionNumber")

      val candidate = PythonEnvironmentRevision(
        id = revisionId,
        environmentId = environment.id,
        revisionNumber = revisionNumber,
        state = PythonEnvironmentState.CREATING,
        requirements = requirements.map(_.render),
        resolvedPackages = Seq.empty,
        createdAt = System.currentTimeMillis(),
        createdBy = operation.submittedBy,
        activatedAt = None,
        failureMessage = None,
        internalEnvironmentLocation = Some(location.toString))
      store.createEnvironmentRevision(candidate)

      val result = builder.build(location, requirements)

      val ready = candidate.copy(
        state = PythonEnvironmentState.READY,
        resolvedPackages = result.resolvedPackages,
        activatedAt = Some(System.currentTimeMillis()))
      store.updateEnvironmentRevision(ready)
      activate(environment, ready, operation.submittedBy)
      trimRevisions(environment.id, ready.id)

      val succeeded = started.copy(
        state = PackageOperationState.SUCCEEDED,
        targetRevisionId = Some(ready.id),
        finishedAt = Some(System.currentTimeMillis()),
        operationLog = Some(result.log),
        version = started.version + 1)
      store.updatePackageOperation(succeeded, started.version)
    } catch {
      case e: NotebookException =>
        failOperation(started, e.code.toString, e.message, e.details.get("log"))
      case NonFatal(e) =>
        warn(s"Package operation ${operation.id} failed", e)
        failOperation(
          started,
          NotebookErrorCode.PYTHON_PACKAGE_INSTALL_FAILED.toString,
          "the package operation failed",
          None)
    } finally {
      busy.remove(environment.id)
    }
  }

  /** The requirement list a revision should be built from, given what the action asks for. */
  private def nextRequirements(
      action: PackageAction.Value,
      base: Option[PythonEnvironmentRevision],
      specs: Seq[RequirementSpec]): Seq[RequirementSpec] = {
    val current = base.map(_.requirements).getOrElse(Seq.empty).map(RequirementSpec.parse)
    action match {
      case PackageAction.INSTALL =>
        // A repeat of an existing name replaces its constraint rather than duplicating it.
        val replaced = specs.map(spec => RequirementSpec.normalize(spec.name)).toSet
        current.filterNot(spec => replaced.contains(RequirementSpec.normalize(spec.name))) ++ specs
      case PackageAction.UNINSTALL =>
        val removed = specs.map(spec => RequirementSpec.normalize(spec.name)).toSet
        current.filterNot(spec => removed.contains(RequirementSpec.normalize(spec.name)))
      case PackageAction.REBUILD => current
    }
  }

  /**
   * Swaps the active revision. Runtimes already bound to the old one keep running on it and are
   * marked as needing a restart; restarting them here would destroy variables the user cannot
   * necessarily reproduce.
   */
  private def activate(
      environment: PythonEnvironment,
      revision: PythonEnvironmentRevision,
      user: String): Unit = {
    val current = store.getEnvironment(environment.id).getOrElse(environment)
    val activated = current.copy(
      activeRevisionId = Some(revision.id),
      state = PythonEnvironmentState.READY,
      pythonVersion = current.pythonVersion.orElse(builder.pythonVersion),
      updatedAt = System.currentTimeMillis(),
      updatedBy = user,
      version = current.version + 1)
    if (!store.updateEnvironment(activated, current.version)) {
      throw NotebookException.versionConflict("the environment was modified concurrently")
    }
  }

  private def trimRevisions(environmentId: String, activeRevisionId: String): Unit = {
    val revisions = store.listEnvironmentRevisions(environmentId)
      .filter(_.id != activeRevisionId)
      .filter(_.state == PythonEnvironmentState.READY)
    revisions.drop(keepRevisions).foreach { revision =>
      revision.internalEnvironmentLocation.foreach(location =>
        builder.deleteRecursively(java.nio.file.Paths.get(location)))
      store.deleteEnvironmentRevision(revision.id)
    }
  }

  private def failOperation(
      operation: PythonPackageOperation,
      code: String,
      message: String,
      log: Option[String]): Unit = {
    val failed = operation.copy(
      state = PackageOperationState.FAILED,
      finishedAt = Some(System.currentTimeMillis()),
      errorCode = Some(code),
      errorMessage = Some(message),
      operationLog = log.orElse(operation.operationLog),
      version = operation.version + 1)
    store.updatePackageOperation(failed, operation.version)
  }

  def operation(principal: NotebookPrincipal, operationId: String): PythonPackageOperation = {
    val operation = store.getPackageOperation(operationId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.PYTHON_ENVIRONMENT_NOT_FOUND,
        s"package operation $operationId was not found")
    }
    require(principal, operation.environmentId)
    operation
  }

  def operationLog(principal: NotebookPrincipal, operationId: String): PythonOperationLogView = {
    val found = operation(principal, operationId)
    PythonOperationLogView(
      found.operationLog.getOrElse(""),
      PackageOperationState.terminal.contains(found.state))
  }

  def cancel(principal: NotebookPrincipal, operationId: String): PythonPackageOperation = {
    val found = operation(principal, operationId)
    if (PackageOperationState.terminal.contains(found.state)) {
      found
    } else {
      // The build itself is not interrupted; marking it cancelled stops the result from being
      // activated, and the candidate directory is cleaned up by the next rebuild.
      val canceled = found.copy(
        state = PackageOperationState.CANCELED,
        finishedAt = Some(System.currentTimeMillis()),
        version = found.version + 1)
      store.updatePackageOperation(canceled, found.version)
      canceled
    }
  }

  /** Fails operations a restart interrupted; a half-built revision is never activated. */
  def reconcileAfterRestart(): Unit = {
    store.listUnfinishedPackageOperations().foreach { operation =>
      try {
        failOperation(
          operation,
          NotebookErrorCode.PYTHON_PACKAGE_INSTALL_FAILED.toString,
          "the server restarted while this operation was running",
          None)
      } catch {
        case NonFatal(e) => warn(s"Failed to reconcile package operation ${operation.id}", e)
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Guards
  // -------------------------------------------------------------------------------------------

  private def requireAvailable(): Unit = {
    if (!available) {
      throw new NotebookException(
        NotebookErrorCode.PYTHON_RUNTIME_UNAVAILABLE,
        "the server has no usable python3 with venv and pip",
        retryable = false)
    }
  }

  private def requireIdle(environment: PythonEnvironment): Unit = {
    if (busy.containsKey(environment.id)) {
      throw new NotebookException(
        NotebookErrorCode.PYTHON_ENVIRONMENT_BUSY,
        "another package operation is already running for this environment")
    }
  }

  private def requirePermitted(name: String): Unit = {
    val normalized = RequirementSpec.normalize(name)
    if (denylist.contains(normalized)) {
      throw new NotebookException(
        NotebookErrorCode.PYTHON_PACKAGE_DENIED,
        s"$name is on the administrator's denylist")
    }
    if (allowlist.nonEmpty && !allowlist.contains(normalized)) {
      throw new NotebookException(
        NotebookErrorCode.PYTHON_PACKAGE_DENIED,
        s"$name is not on the administrator's allowlist")
    }
  }

  /** Owner names reach the filesystem, so only a conservative character set is allowed through. */
  private def sanitize(owner: String): String =
    owner.replaceAll("[^A-Za-z0-9_.-]", "_").take(64)

  def shutdown(): Unit = ThreadUtils.shutdown(workers)
}

object PythonEnvironmentService {
  val DEFAULT_SPEC_ID = "cpython3"
}
