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

import org.apache.kyuubi.server.notebook.api.CellLanguage.CellLanguage
import org.apache.kyuubi.server.notebook.api.ExecutionState.ExecutionState
import org.apache.kyuubi.server.notebook.api.FailurePolicy.FailurePolicy
import org.apache.kyuubi.server.notebook.api.NotebookSessionState.NotebookSessionState
import org.apache.kyuubi.server.notebook.api.RunState.RunState
import org.apache.kyuubi.server.notebook.api.RuntimeState.RuntimeState

/**
 * Runtime-side domain model.
 *
 * `internalRuntimeHandle`, `internalRuntimeLocation` and `internalOperationHandle` carry the
 * Kyuubi session handle, the instance that owns it, and the operation handle. They exist only so
 * the server can find its own work again after a restart and must never reach a response; the
 * `*View` types below are what the REST layer returns.
 */
case class NotebookSession(
    id: String,
    notebookId: String,
    owner: String,
    state: NotebookSessionState,
    runtimeProfile: Option[String],
    createdAt: Long,
    lastActivityAt: Long,
    stoppedAt: Option[Long],
    failureMessage: Option[String],
    kyuubiInstance: Option[String],
    version: Long)

case class NotebookRuntime(
    id: String,
    notebookSessionId: String,
    runtimeSpecId: String,
    runtimeType: String,
    language: CellLanguage,
    owner: String,
    state: RuntimeState,
    generation: Long,
    environmentRevisionId: Option[String],
    createdAt: Long,
    lastActivityAt: Long,
    stoppedAt: Option[Long],
    failureMessage: Option[String],
    internalRuntimeHandle: Option[String],
    internalRuntimeLocation: Option[String],
    version: Long)

case class CellExecution(
    id: String,
    notebookId: String,
    notebookSessionId: String,
    runtimeId: String,
    runtimeGeneration: Long,
    cellId: Option[String],
    cellVersion: Option[Long],
    language: CellLanguage,
    sourceSnapshot: String,
    state: ExecutionState,
    submittedAt: Long,
    startedAt: Option[Long],
    finishedAt: Option[Long],
    submittedBy: String,
    errorCode: Option[String],
    errorMessage: Option[String],
    clientRequestId: Option[String],
    notebookRunId: Option[String],
    internalOperationHandle: Option[String],
    version: Long)

case class ExecutionEvent(
    executionId: String,
    sequence: Long,
    eventType: String,
    payload: Option[String],
    createdAt: Long)

case class NotebookRun(
    id: String,
    notebookId: String,
    notebookSessionId: String,
    state: RunState,
    submittedAt: Long,
    startedAt: Option[Long],
    finishedAt: Option[Long],
    submittedBy: String,
    requestedCellIds: Seq[String],
    currentCellId: Option[String],
    failurePolicy: FailurePolicy,
    version: Long)

/**
 * A runtime a user may ask for. Startup commands, connection details and credentials are
 * deliberately absent: a spec describes what a runtime *is*, not how the server launches it.
 */
case class RuntimeSpec(
    id: String,
    displayName: String,
    language: String,
    version: String,
    enabled: Boolean,
    configurableKeys: Seq[String],
    limits: Map[String, String])

// ------------------------------------------------------------------------------------------------
// Views
// ------------------------------------------------------------------------------------------------

case class NotebookSessionView(
    id: String,
    notebookId: String,
    owner: String,
    state: String,
    runtimeProfile: Option[String],
    createdAt: Long,
    lastActivityAt: Long,
    stoppedAt: Option[Long],
    failureMessage: Option[String],
    version: Long)

object NotebookSessionView {
  def apply(session: NotebookSession): NotebookSessionView = NotebookSessionView(
    session.id,
    session.notebookId,
    session.owner,
    session.state.toString,
    session.runtimeProfile,
    session.createdAt,
    session.lastActivityAt,
    session.stoppedAt,
    session.failureMessage,
    session.version)
}

case class NotebookRuntimeView(
    id: String,
    notebookSessionId: String,
    runtimeSpecId: String,
    runtimeType: String,
    language: String,
    owner: String,
    state: String,
    generation: Long,
    createdAt: Long,
    lastActivityAt: Long,
    stoppedAt: Option[Long],
    failureMessage: Option[String],
    version: Long)

object NotebookRuntimeView {
  def apply(runtime: NotebookRuntime): NotebookRuntimeView = NotebookRuntimeView(
    runtime.id,
    runtime.notebookSessionId,
    runtime.runtimeSpecId,
    runtime.runtimeType,
    runtime.language.toString,
    runtime.owner,
    runtime.state.toString,
    runtime.generation,
    runtime.createdAt,
    runtime.lastActivityAt,
    runtime.stoppedAt,
    runtime.failureMessage,
    runtime.version)
}

case class CellExecutionView(
    id: String,
    notebookId: String,
    notebookSessionId: String,
    runtimeId: String,
    cellId: Option[String],
    cellVersion: Option[Long],
    language: String,
    source: String,
    state: String,
    submittedAt: Long,
    startedAt: Option[Long],
    finishedAt: Option[Long],
    submittedBy: String,
    errorCode: Option[String],
    errorMessage: Option[String],
    notebookRunId: Option[String],
    version: Long)

object CellExecutionView {
  def apply(execution: CellExecution): CellExecutionView = CellExecutionView(
    execution.id,
    execution.notebookId,
    execution.notebookSessionId,
    execution.runtimeId,
    execution.cellId,
    execution.cellVersion,
    execution.language.toString,
    execution.sourceSnapshot,
    execution.state.toString,
    execution.submittedAt,
    execution.startedAt,
    execution.finishedAt,
    execution.submittedBy,
    execution.errorCode,
    execution.errorMessage,
    execution.notebookRunId,
    execution.version)
}

case class ExecutionEventView(
    sequence: Long,
    eventType: String,
    payload: Option[String],
    createdAt: Long)

object ExecutionEventView {
  def apply(event: ExecutionEvent): ExecutionEventView =
    ExecutionEventView(event.sequence, event.eventType, event.payload, event.createdAt)
}

case class ExecutionEventPage(events: Seq[ExecutionEventView], lastSequence: Long)

case class ExecutionLogPage(lines: Seq[String], nextOffset: Long, hasMore: Boolean)

/**
 * A rendered output. `data` has already been through the sanitizer, but a client must still
 * treat `text/html` and `image/svg+xml` as untrusted and render them isolated.
 */
case class ExecutionOutputView(
    sequence: Long,
    outputType: String,
    stream: Option[String],
    mimeType: String,
    data: String)

case class ExecutionOutputPage(
    outputs: Seq[ExecutionOutputView],
    lastSequence: Long,
    hasMore: Boolean)

case class ColumnSchema(name: String, dataType: String, position: Int, comment: Option[String])

case class ExecutionSchema(columns: Seq[ColumnSchema])

case class ExecutionResultPage(
    rows: Seq[Seq[String]],
    nextCursor: Option[String],
    hasMore: Boolean)

case class NotebookRunView(
    id: String,
    notebookId: String,
    notebookSessionId: String,
    state: String,
    submittedAt: Long,
    startedAt: Option[Long],
    finishedAt: Option[Long],
    submittedBy: String,
    requestedCellIds: Seq[String],
    currentCellId: Option[String],
    failurePolicy: String,
    version: Long)

object NotebookRunView {
  def apply(run: NotebookRun): NotebookRunView = NotebookRunView(
    run.id,
    run.notebookId,
    run.notebookSessionId,
    run.state.toString,
    run.submittedAt,
    run.startedAt,
    run.finishedAt,
    run.submittedBy,
    run.requestedCellIds,
    run.currentCellId,
    run.failurePolicy.toString,
    run.version)
}

// ------------------------------------------------------------------------------------------------
// Requests
// ------------------------------------------------------------------------------------------------

class CreateSessionRequest {
  @BeanProperty var runtimeProfile: String = _
}

class CreateRuntimeRequest {
  @BeanProperty var runtimeSpecId: String = _
  @BeanProperty var configuration: java.util.Map[String, String] = _
}

class SubmitExecutionRequest {
  @BeanProperty var cellId: String = _
  @BeanProperty var runtimeId: String = _
  @BeanProperty var language: String = _
  @BeanProperty var source: String = _

  /** Makes a resubmission idempotent; the same id with a different payload is a conflict. */
  @BeanProperty var clientRequestId: String = _
  @BeanProperty var executionTimeoutSeconds: java.lang.Integer = _
  @BeanProperty var configuration: java.util.Map[String, String] = _
}

class RunAllRequest {
  @BeanProperty var failurePolicy: String = _
  @BeanProperty var cellIds: java.util.List[String] = _
  @BeanProperty var clientRequestId: String = _
}
