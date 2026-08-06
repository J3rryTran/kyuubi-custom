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

package org.apache.kyuubi.server.notebook

import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api.{ExecutionState, NotebookErrorCode, NotebookException, NotebookSession, NotebookStatusView}
import org.apache.kyuubi.server.notebook.python.PythonEnvironmentBuilder
import org.apache.kyuubi.server.notebook.runtime.{CpythonRuntimeAdapter, KyuubiSqlRuntimeAdapter, PythonRuntimeContext, RuntimeAdapterRegistry}
import org.apache.kyuubi.server.notebook.service._
import org.apache.kyuubi.server.notebook.store.{ExecutionFilter, JDBCNotebookStore, NotebookStore}
import org.apache.kyuubi.service.{AbstractService, BackendService}
import org.apache.kyuubi.util.ThreadUtils
import org.apache.kyuubi.util.ThreadUtils.scheduleTolerableRunnableWithFixedDelay

/**
 * Composition root and lifecycle owner of the notebook subsystem. REST resources reach the
 * services only through here, so nothing in the API layer constructs a store or holds state.
 */
class NotebookManager(
    backendService: () => BackendService,
    instanceUri: () => String) extends AbstractService("NotebookManager") {
  import NotebookManager._

  @volatile private var _store: NotebookStore = _
  @volatile private var _permissions: NotebookPermissionService = _
  @volatile private var _revisions: NotebookRevisionService = _
  @volatile private var _documents: NotebookDocumentService = _
  @volatile private var _content: NotebookContentService = _
  @volatile private var _schedules: NotebookScheduleService = _
  @volatile private var _registry: RuntimeAdapterRegistry = _
  @volatile private var _runtimes: NotebookRuntimeService = _
  @volatile private var _sessions: NotebookSessionService = _
  @volatile private var _executions: NotebookExecutionService = _
  @volatile private var _pythonEnvironments: PythonEnvironmentService = _

  def store: NotebookStore = _store
  def permissions: NotebookPermissionService = _permissions
  def revisions: NotebookRevisionService = _revisions
  def documents: NotebookDocumentService = _documents
  def content: NotebookContentService = _content
  def schedules: NotebookScheduleService = _schedules
  def registry: RuntimeAdapterRegistry = _registry
  def runtimes: NotebookRuntimeService = _runtimes
  def sessions: NotebookSessionService = _sessions
  def executions: NotebookExecutionService = _executions
  def pythonEnvironments: PythonEnvironmentService = _pythonEnvironments

  override def initialize(conf: KyuubiConf): Unit = {
    _store = new JDBCNotebookStore(conf)
    if (conf.get(NOTEBOOK_SCHEMA_INIT)) {
      _store.initSchema()
    }
    _permissions = new NotebookPermissionService(_store)
    _revisions = new NotebookRevisionService(conf, _store)
    _documents = new NotebookDocumentService(conf, _store, _permissions, _revisions)
    _content = new NotebookContentService(conf, _store, _documents, _revisions, _permissions)
    _schedules = new NotebookScheduleService(_store, _permissions)
    val pythonBuilder = new PythonEnvironmentBuilder(conf)
    _pythonEnvironments = new PythonEnvironmentService(conf, _store, pythonBuilder)
    // The adapter reads the caller's active revision at start time, so a runtime always binds to
    // whatever was activated then and a later activation cannot change it underneath.
    val pythonContext = new PythonRuntimeContext {
      override def interpreterFor(owner: String) =
        _pythonEnvironments.list(NotebookPrincipal(owner, admin = false))
          .headOption
          .flatMap(_pythonEnvironments.activeInterpreter)

      override def activeRevisionIdFor(owner: String) =
        _pythonEnvironments.list(NotebookPrincipal(owner, admin = false))
          .headOption
          .flatMap(_.activeRevisionId)
    }
    _registry = new RuntimeAdapterRegistry(Seq(
      new KyuubiSqlRuntimeAdapter(backendService, instanceUri, conf.get(NOTEBOOK_MAX_PAGE_SIZE)),
      new CpythonRuntimeAdapter(conf, pythonContext, pythonBuilder, instanceUri)))
    _runtimes = new NotebookRuntimeService(_store, _registry, instanceUri)
    _sessions =
      new NotebookSessionService(_store, _documents, _permissions, _runtimes, instanceUri)
    _executions = new NotebookExecutionService(
      conf,
      _store,
      _documents,
      _permissions,
      _sessions,
      _runtimes,
      _registry)
    super.initialize(conf)
  }

  private lazy val idleReaper =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("notebook-idle-reaper")

  override def start(): Unit = {
    // Nothing that was running can have survived; saying so up front beats letting the first
    // poll of a stale execution report a confusing failure.
    _executions.reconcileAfterRestart()
    _sessions.reconcileAfterRestart()
    _pythonEnvironments.reconcileAfterRestart()
    val idleTimeout = conf.get(NOTEBOOK_RUNTIME_IDLE_TIMEOUT)
    if (idleTimeout > 0) {
      val interval = conf.get(NOTEBOOK_RUNTIME_IDLE_CHECK_INTERVAL)
      scheduleTolerableRunnableWithFixedDelay(
        idleReaper,
        () => _sessions.reapIdle(idleTimeout),
        interval,
        interval,
        TimeUnit.MILLISECONDS)
    }
    super.start()
  }

  override def stop(): Unit = {
    ThreadUtils.shutdown(idleReaper)
    if (_pythonEnvironments != null) _pythonEnvironments.shutdown()
    if (_store != null) {
      try _store.close()
      catch { case NonFatal(e) => warn("Failed to close the notebook store", e) }
    }
    super.stop()
  }

  /**
   * Sanitized health. A subsystem that does not exist yet reports `NOT_IMPLEMENTED` rather than
   * a zero count, so a monitor cannot read "no runtimes" as "healthy and idle" while that part
   * is simply absent.
   */
  def status(): NotebookStatusView = {
    val liveSessions =
      try _store.listLiveSessions()
      catch { case NonFatal(_) => Seq.empty[NotebookSession] }
    val persistence =
      try {
        _store.healthCheck()
        "HEALTHY"
      } catch {
        case NonFatal(e) =>
          warn("Notebook store health check failed", e)
          "UNAVAILABLE"
      }
    NotebookStatusView(
      notebookService = if (persistence == "HEALTHY") "HEALTHY" else "DEGRADED",
      persistence = persistence,
      kyuubiSql = if (_registry.specs.exists(_.enabled)) "HEALTHY" else "UNAVAILABLE",
      pythonRuntimeManager =
        if (_pythonEnvironments.available) "HEALTHY" else "UNAVAILABLE",
      activeSessions = liveSessions.size,
      activeRuntimes = liveSessions.flatMap(_runtimes.listFor).size,
      queuedExecutions = _store.listExecutions(ExecutionFilter(
        states = Set(ExecutionState.QUEUED.toString, ExecutionState.STARTING.toString),
        limit = STATUS_COUNT_LIMIT)).size)
  }
}

object NotebookManager {

  /** Bound on the status count query; a status page never needs an exact number of a huge queue. */
  private val STATUS_COUNT_LIMIT = 1000

  /** Raised when a notebook endpoint is reached while the subsystem is switched off. */
  def disabled(): NotebookException = new NotebookException(
    NotebookErrorCode.NOTEBOOK_DISABLED,
    s"the notebook subsystem is disabled; set ${NOTEBOOK_ENABLED.key} to true to enable it")
}
