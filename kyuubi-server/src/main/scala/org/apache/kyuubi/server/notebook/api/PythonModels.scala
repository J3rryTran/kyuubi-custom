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

package org.apache.kyuubi.server.notebook.api

import scala.beans.BeanProperty

import org.apache.kyuubi.server.notebook.api.PackageAction.PackageAction
import org.apache.kyuubi.server.notebook.api.PackageOperationState.PackageOperationState
import org.apache.kyuubi.server.notebook.api.PythonEnvironmentState.PythonEnvironmentState

/**
 * A user's Python environment.
 *
 * An environment is owned by exactly one user and is never writable by another: installing into a
 * shared environment would be arbitrary code execution against everyone who runs from it.
 */
case class PythonEnvironment(
    id: String,
    owner: String,
    name: String,
    runtimeSpecId: String,
    pythonVersion: Option[String],
    activeRevisionId: Option[String],
    state: PythonEnvironmentState,
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long)

/**
 * One immutable build of an environment.
 *
 * `internalEnvironmentLocation` is the on-disk virtualenv and never leaves the server. A revision
 * is written once and then only activated or discarded; an active one is never modified in place,
 * which is what makes rollback possible and keeps a failed install from leaving a half-built
 * environment behind.
 */
case class PythonEnvironmentRevision(
    id: String,
    environmentId: String,
    revisionNumber: Long,
    state: PythonEnvironmentState,
    requirements: Seq[String],
    resolvedPackages: Seq[String],
    createdAt: Long,
    createdBy: String,
    activatedAt: Option[Long],
    failureMessage: Option[String],
    internalEnvironmentLocation: Option[String])

case class PythonPackageOperation(
    id: String,
    environmentId: String,
    baseRevisionId: Option[String],
    targetRevisionId: Option[String],
    action: PackageAction,
    requestedPackages: Seq[String],
    state: PackageOperationState,
    submittedAt: Long,
    startedAt: Option[Long],
    finishedAt: Option[Long],
    submittedBy: String,
    clientRequestId: Option[String],
    errorCode: Option[String],
    errorMessage: Option[String],
    operationLog: Option[String],
    version: Long)

// ------------------------------------------------------------------------------------------------
// Views
// ------------------------------------------------------------------------------------------------

case class PythonEnvironmentView(
    id: String,
    owner: String,
    name: String,
    runtimeSpecId: String,
    pythonVersion: Option[String],
    activeRevisionNumber: Option[Long],
    state: String,
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long)

object PythonEnvironmentView {
  def apply(
      environment: PythonEnvironment,
      activeRevisionNumber: Option[Long]): PythonEnvironmentView = PythonEnvironmentView(
    environment.id,
    environment.owner,
    environment.name,
    environment.runtimeSpecId,
    environment.pythonVersion,
    activeRevisionNumber,
    environment.state.toString,
    environment.createdAt,
    environment.createdBy,
    environment.updatedAt,
    environment.updatedBy,
    environment.version)
}

case class PythonEnvironmentRevisionView(
    revisionNumber: Long,
    state: String,
    requirements: Seq[String],
    resolvedPackages: Seq[String],
    createdAt: Long,
    createdBy: String,
    activatedAt: Option[Long],
    failureMessage: Option[String],
    active: Boolean)

object PythonEnvironmentRevisionView {
  def apply(
      revision: PythonEnvironmentRevision,
      active: Boolean): PythonEnvironmentRevisionView = PythonEnvironmentRevisionView(
    revision.revisionNumber,
    revision.state.toString,
    revision.requirements,
    revision.resolvedPackages,
    revision.createdAt,
    revision.createdBy,
    revision.activatedAt,
    revision.failureMessage,
    active)
}

case class PythonPackageOperationView(
    id: String,
    environmentId: String,
    action: String,
    requestedPackages: Seq[String],
    state: String,
    submittedAt: Long,
    startedAt: Option[Long],
    finishedAt: Option[Long],
    submittedBy: String,
    errorCode: Option[String],
    errorMessage: Option[String],
    version: Long)

object PythonPackageOperationView {
  def apply(operation: PythonPackageOperation): PythonPackageOperationView =
    PythonPackageOperationView(
      operation.id,
      operation.environmentId,
      operation.action.toString,
      operation.requestedPackages,
      operation.state.toString,
      operation.submittedAt,
      operation.startedAt,
      operation.finishedAt,
      operation.submittedBy,
      operation.errorCode,
      operation.errorMessage,
      operation.version)
}

/** `fromImage` marks a package the server image provides, which a user cannot remove. */
case class InstalledPackage(name: String, version: String, fromImage: Boolean = false)

case class PythonPackageListView(
    revisionNumber: Option[Long],
    packages: Seq[InstalledPackage],
    requirements: Seq[String])

case class PythonOperationLogView(log: String, finished: Boolean)

// ------------------------------------------------------------------------------------------------
// Requests
// ------------------------------------------------------------------------------------------------

class CreatePythonEnvironmentRequest {
  @BeanProperty var name: String = _
  @BeanProperty var runtimeSpecId: String = _
}

class UpdatePythonEnvironmentRequest {
  @BeanProperty var name: String = _
  @BeanProperty var version: java.lang.Long = _
}

class PackageOperationRequest {

  /** Requirement specifications only; pip options and URLs are rejected by the parser. */
  @BeanProperty var packages: java.util.List[String] = _
  @BeanProperty var clientRequestId: String = _
}
