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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.python.RichOutputSanitizer
import org.apache.kyuubi.server.notebook.runtime.RuntimeAdapterRegistry
import org.apache.kyuubi.server.notebook.store.{ExecutionFilter, NotebookStore}

/**
 * Submission and observation of cell executions.
 *
 * An execution is a durable resource, not a request-scoped operation: the submission returns as
 * soon as the work is accepted, and everything after that - state, events, logs, results - is
 * polled. That is what lets a browser refresh, a second tab, or a reconnect rejoin work that is
 * already running.
 */
class NotebookExecutionService(
    conf: KyuubiConf,
    store: NotebookStore,
    documents: NotebookDocumentService,
    permissions: NotebookPermissionService,
    sessions: NotebookSessionService,
    runtimes: NotebookRuntimeService,
    registry: RuntimeAdapterRegistry) extends Logging {
  import NotebookExecutionService._

  private val maxPageSize = conf.get(NOTEBOOK_MAX_PAGE_SIZE)

  /**
   * Last result page handed to each client. Result cursors only move forwards, so this is what
   * makes retrying the page just received safe: without it a retry after a timeout would skip
   * rows the client never saw.
   */
  private val lastResultPages = new ConcurrentHashMap[String, DeliveredPage]()
  private val maxLogLines = conf.get(NOTEBOOK_EXECUTION_LOG_MAX_LINES)
  private val maxEventWaitMillis = conf.get(NOTEBOOK_EVENT_MAX_WAIT_MS)

  def submit(
      principal: NotebookPrincipal,
      sessionId: String,
      request: SubmitExecutionRequest): CellExecution = {
    val session = sessions.require(principal, sessionId)
    val notebook = documents.loadNotebook(session.notebookId)
    permissions.requireWrite(notebook, principal)

    val requestId = Option(request.getClientRequestId).map(_.trim).filter(_.nonEmpty)
    val (source, cell) = resolveSource(session.notebookId, request)
    val language = resolveLanguage(request, cell)

    // Idempotency is checked before anything is started, so a retried POST cannot submit twice.
    val alreadySubmitted =
      requestId.flatMap(id => store.findExecutionByRequestId(principal.user, id)).map { existing =>
        if (existing.sourceSnapshot != source || existing.language != language) {
          throw new NotebookException(
            NotebookErrorCode.VERSION_CONFLICT,
            "clientRequestId was already used with a different request")
        }
        existing
      }
    if (alreadySubmitted.isDefined) {
      alreadySubmitted.get
    } else {
      startExecution(principal, session, request, source, cell, language, requestId)
    }
  }

  private def startExecution(
      principal: NotebookPrincipal,
      session: NotebookSession,
      request: SubmitExecutionRequest,
      source: String,
      cell: Option[NotebookCell],
      language: CellLanguage.Value,
      requestId: Option[String]): CellExecution = {
    val configuration = Option(request.getConfiguration).map(_.asScala.toMap).getOrElse(Map.empty)
    val runtime = runtimes.ensureFor(
      session,
      language,
      Option(request.getRuntimeId).flatMap {
        runtimeId => Some(runtimes.require(principal, runtimeId).runtimeSpecId)
      },
      configuration)
    if (runtime.language != language) {
      throw new NotebookException(
        NotebookErrorCode.UNSUPPORTED_LANGUAGE,
        s"runtime ${runtime.id} cannot run $language")
    }

    val now = System.currentTimeMillis()
    val queued = CellExecution(
      id = UUID.randomUUID().toString,
      notebookId = session.notebookId,
      notebookSessionId = session.id,
      runtimeId = runtime.id,
      runtimeGeneration = runtime.generation,
      cellId = cell.map(_.id),
      cellVersion = cell.map(_.version),
      language = language,
      // Snapshotted here, so editing the cell afterwards cannot change what ran.
      sourceSnapshot = source,
      state = ExecutionState.QUEUED,
      submittedAt = now,
      startedAt = None,
      finishedAt = None,
      submittedBy = principal.user,
      errorCode = None,
      errorMessage = None,
      clientRequestId = requestId,
      notebookRunId = None,
      internalOperationHandle = None,
      version = 1L)
    store.createExecution(queued)
    emit(queued, "QUEUED", None)

    try {
      val submitted = registry.get(runtime.runtimeSpecId).execute(runtime, queued, configuration)
      val started = queued.copy(
        state = ExecutionState.STARTING,
        internalOperationHandle = Some(submitted.handle),
        version = queued.version + 1)
      store.updateExecution(started, queued.version)
      emit(started, "STARTING", None)
      // The idle reaper measures from here, so a runtime in use is never reclaimed.
      runtimes.touchActivity(runtime)
      started
    } catch {
      case e: NotebookException =>
        fail(queued, e.code.toString, e.message)
        throw e
      case NonFatal(e) =>
        warn(s"Failed to submit execution ${queued.id}", e)
        fail(
          queued,
          NotebookErrorCode.KYUUBI_UNAVAILABLE.toString,
          "the statement was not accepted")
        throw new NotebookException(
          NotebookErrorCode.KYUUBI_UNAVAILABLE,
          "the statement was not accepted",
          retryable = true,
          cause = e)
    }
  }

  def get(principal: NotebookPrincipal, executionId: String): CellExecution =
    refresh(require(principal, executionId))

  def require(principal: NotebookPrincipal, executionId: String): CellExecution = {
    val execution = store.getExecution(executionId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.EXECUTION_NOT_FOUND,
        s"execution $executionId was not found")
    }
    if (!principal.admin && execution.submittedBy != principal.user) {
      throw NotebookException.notFound(
        NotebookErrorCode.EXECUTION_NOT_FOUND,
        s"execution $executionId was not found")
    }
    execution
  }

  /** Pulls the adapter's view of a running execution into the store, emitting state events. */
  def refresh(execution: CellExecution): CellExecution = {
    if (ExecutionState.terminal.contains(execution.state)) {
      execution
    } else {
      val runtime = runtimes.load(execution.runtimeId)
      if (runtime.generation != execution.runtimeGeneration ||
        RuntimeState.terminal.contains(runtime.state)) {
        // The runtime this ran on is gone or was restarted; the outcome can no longer be
        // established, and unknown work is never reported as successful.
        return lose(execution, "the runtime that was running this execution is gone")
      }
      val status =
        try {
          registry.get(runtime.runtimeSpecId).getExecutionStatus(execution)
        } catch {
          case NonFatal(e) =>
            warn(s"Failed to read status of execution ${execution.id}", e)
            return lose(execution, "the execution state could not be established")
        }
      if (status.state == execution.state) {
        execution
      } else {
        val updated = execution.copy(
          state = status.state,
          startedAt = status.startedAt.orElse(execution.startedAt),
          finishedAt = status.finishedAt.orElse(execution.finishedAt),
          errorCode = status.errorCode.orElse(execution.errorCode),
          errorMessage = status.errorMessage.orElse(execution.errorMessage),
          version = execution.version + 1)
        store.updateExecution(updated, execution.version)
        emit(updated, status.state.toString, status.errorMessage)
        updated
      }
    }
  }

  def list(
      principal: NotebookPrincipal,
      filter: ExecutionFilter): Seq[CellExecution] = {
    val bounded = filter.copy(limit = math.min(filter.limit, maxPageSize))
    store.listExecutions(bounded).filter(execution =>
      principal.admin || execution.submittedBy == principal.user)
  }

  def cancel(principal: NotebookPrincipal, executionId: String): CellExecution = {
    val execution = require(principal, executionId)
    if (ExecutionState.terminal.contains(execution.state)) {
      execution
    } else {
      val runtime = runtimes.load(execution.runtimeId)
      try registry.get(runtime.runtimeSpecId).interruptExecution(execution)
      catch { case NonFatal(e) => warn(s"Failed to cancel execution $executionId", e) }
      val canceling = execution.copy(
        state = ExecutionState.CANCELING,
        version = execution.version + 1)
      store.updateExecution(canceling, execution.version)
      emit(canceling, "CANCELING", None)
      // The adapter decides when it is actually cancelled; refresh reports the settled state.
      refresh(canceling)
    }
  }

  /** Releases the backend resources of a finished execution. History and events are kept. */
  def close(principal: NotebookPrincipal, executionId: String): CellExecution = {
    val execution = require(principal, executionId)
    if (execution.state == ExecutionState.CLOSED) {
      execution
    } else {
      val runtime = runtimes.load(execution.runtimeId)
      try registry.get(runtime.runtimeSpecId).closeExecution(execution)
      catch { case NonFatal(e) => warn(s"Failed to close execution $executionId", e) }
      val now = System.currentTimeMillis()
      lastResultPages.remove(executionId)
      val closed = execution.copy(
        state = ExecutionState.CLOSED,
        finishedAt = execution.finishedAt.orElse(Some(now)),
        version = execution.version + 1)
      store.updateExecution(closed, execution.version)
      emit(closed, "CLOSED", None)
      closed
    }
  }

  // -------------------------------------------------------------------------------------------
  // Events, logs and results
  // -------------------------------------------------------------------------------------------

  /**
   * Long-polls the event stream. Sequences are stable and replayable, so a client that
   * reconnects asks for what it already has and receives the rest; duplicates are acceptable,
   * gaps are not.
   */
  def events(
      principal: NotebookPrincipal,
      executionId: String,
      afterSequence: Long,
      waitMillis: Long,
      limit: Int): ExecutionEventPage = {
    val execution = require(principal, executionId)
    val bounded = math.min(limit, maxPageSize)
    val deadline = System.currentTimeMillis() + math.min(waitMillis, maxEventWaitMillis)
    var events = store.listEvents(executionId, afterSequence, bounded)
    while (events.isEmpty && System.currentTimeMillis() < deadline) {
      refresh(store.getExecution(executionId).getOrElse(execution))
      events = store.listEvents(executionId, afterSequence, bounded)
      if (events.isEmpty) Thread.sleep(EVENT_POLL_INTERVAL_MS)
    }
    ExecutionEventPage(
      events.map(ExecutionEventView.apply),
      events.lastOption.map(_.sequence).getOrElse(afterSequence))
  }

  def logs(
      principal: NotebookPrincipal,
      executionId: String,
      offset: Long,
      maxLines: Int): ExecutionLogPage = {
    val execution = require(principal, executionId)
    val runtime = runtimes.load(execution.runtimeId)
    val page = registry.get(runtime.runtimeSpecId)
      .fetchLogs(execution, math.max(offset, 0L), math.min(maxLines, maxLogLines))
    ExecutionLogPage(page.lines.map(redact), page.nextOffset, page.hasMore)
  }

  /**
   * Rich outputs, sanitized here rather than at the adapter, so every runtime is covered by the
   * same pass and a new adapter cannot forget it.
   */
  def outputs(
      principal: NotebookPrincipal,
      executionId: String,
      afterSequence: Long,
      limit: Int): ExecutionOutputPage = {
    val execution = require(principal, executionId)
    val runtime = runtimes.load(execution.runtimeId)
    val bounded = math.min(limit, maxPageSize)
    val page = registry.get(runtime.runtimeSpecId)
      .fetchOutputs(execution, math.max(afterSequence, 0L), bounded)
    val sanitized = page.flatMap { output =>
      RichOutputSanitizer.sanitize(output.mimeType, output.data).map { case (mime, data) =>
        ExecutionOutputView(
          output.sequence,
          output.outputType,
          output.stream,
          mime,
          if (output.outputType == "STREAM" || output.outputType == "ERROR") redact(data)
          else data)
      }
    }
    ExecutionOutputPage(
      sanitized,
      sanitized.lastOption.map(_.sequence).getOrElse(afterSequence),
      page.size == bounded)
  }

  def schema(principal: NotebookPrincipal, executionId: String): ExecutionSchema = {
    val execution = require(principal, executionId)
    tabular(execution).fetchSchema(execution)
  }

  def results(
      principal: NotebookPrincipal,
      executionId: String,
      cursor: Option[String],
      maxRows: Int): ExecutionResultPage = {
    val execution = require(principal, executionId)
    val offset = cursor.map(NotebookJson.decodeCursor).map { value =>
      try value.toLong
      catch {
        case _: NumberFormatException => throw NotebookException.invalid("cursor is malformed")
      }
    }.getOrElse(0L)
    val delivered = Option(lastResultPages.get(executionId))
    delivered.filter(_.offset == offset) match {
      case Some(cached) => cached.toPage
      case None =>
        val expected = delivered.map(_.nextOffset).getOrElse(0L)
        if (offset != expected) {
          throw new NotebookException(
            NotebookErrorCode.RESULT_EXPIRED,
            "results can only be read forwards; start again from the first page",
            details = Map("expectedCursor" -> expected.toString))
        }
        val page =
          tabular(execution).fetchResults(execution, offset, math.min(maxRows, maxPageSize))
        val recorded = DeliveredPage(offset, page.rows, page.nextOffset, page.hasMore)
        lastResultPages.put(executionId, recorded)
        recorded.toPage
    }
  }

  private def tabular(execution: CellExecution) = {
    val runtime = runtimes.load(execution.runtimeId)
    registry.tabular(runtime.runtimeSpecId).getOrElse {
      throw new NotebookException(
        NotebookErrorCode.NO_TABULAR_RESULT,
        s"${execution.language} executions do not produce a tabular result")
    }
  }

  // -------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------

  private def resolveSource(
      notebookId: String,
      request: SubmitExecutionRequest): (String, Option[NotebookCell]) = {
    Option(request.getCellId).map(_.trim).filter(_.nonEmpty) match {
      case Some(cellId) =>
        val cell = store.getCell(notebookId, cellId).getOrElse {
          throw NotebookException.notFound(
            NotebookErrorCode.CELL_NOT_FOUND,
            s"cell $cellId was not found")
        }
        // An explicit source overrides the stored cell, which is how an unsaved edit is run.
        (Option(request.getSource).getOrElse(cell.source), Some(cell))
      case None =>
        val source = Option(request.getSource).map(_.trim).filter(_.nonEmpty).getOrElse {
          throw NotebookException.invalid("either cellId or source must be provided")
        }
        (source, None)
    }
  }

  private def resolveLanguage(
      request: SubmitExecutionRequest,
      cell: Option[NotebookCell]): CellLanguage.Value = {
    val language = Option(request.getLanguage).map(_.trim).filter(_.nonEmpty) match {
      case Some(value) => CellLanguage.values.find(_.toString.equalsIgnoreCase(value)).getOrElse {
          throw new NotebookException(
            NotebookErrorCode.UNSUPPORTED_LANGUAGE,
            s"language must be one of ${CellLanguage.executable.mkString(", ")}")
        }
      case None => cell.map(_.language).getOrElse(CellLanguage.SQL)
    }
    if (!CellLanguage.executable.contains(language)) {
      throw new NotebookException(
        NotebookErrorCode.UNSUPPORTED_LANGUAGE,
        s"$language cannot be executed")
    }
    language
  }

  private def fail(execution: CellExecution, code: String, message: String): CellExecution = {
    val failed = execution.copy(
      state = ExecutionState.FAILED,
      finishedAt = Some(System.currentTimeMillis()),
      errorCode = Some(code),
      errorMessage = Some(message),
      version = execution.version + 1)
    store.updateExecution(failed, execution.version)
    emit(failed, "FAILED", Some(message))
    failed
  }

  private def lose(execution: CellExecution, message: String): CellExecution = {
    val lost = execution.copy(
      state = ExecutionState.LOST,
      finishedAt = Some(System.currentTimeMillis()),
      errorCode = Some(NotebookErrorCode.RUNTIME_LOST.toString),
      errorMessage = Some(message),
      version = execution.version + 1)
    store.updateExecution(lost, execution.version)
    emit(lost, "LOST", Some(message))
    lost
  }

  private def emit(
      execution: CellExecution,
      eventType: String,
      payload: Option[String]): Unit = {
    try {
      store.appendEvent(ExecutionEvent(
        execution.id,
        store.nextEventSequence(execution.id),
        eventType,
        payload.map(redact),
        System.currentTimeMillis()))
    } catch {
      // An event is an observation of something that already happened; losing one must not undo
      // the state change that produced it.
      case NonFatal(e) => warn(s"Failed to record a $eventType event for ${execution.id}", e)
    }
  }

  /** Strips anything that looks like a credential before a log line leaves the server. */
  private def redact(line: String): String =
    Option(line).map(NotebookExecutionService.REDACTION_PATTERN.replaceAllIn(_, "$1***"))
      .getOrElse("")

  /** Reconciles executions left non-terminal by a restart. */
  def reconcileAfterRestart(): Unit = {
    val unfinished = store.listExecutions(ExecutionFilter(
      states = (ExecutionState.values -- ExecutionState.terminal).map(_.toString),
      limit = RECONCILE_BATCH))
    unfinished.foreach { execution =>
      try lose(execution, "the server restarted while this execution was running")
      catch { case NonFatal(e) => warn(s"Failed to reconcile execution ${execution.id}", e) }
    }
  }
}

object NotebookExecutionService {

  /** One page already handed to a client, kept so the same cursor returns the same rows. */
  private case class DeliveredPage(
      offset: Long,
      rows: Seq[Seq[String]],
      nextOffset: Long,
      hasMore: Boolean) {
    def toPage: ExecutionResultPage = ExecutionResultPage(
      rows,
      if (hasMore) Some(NotebookJson.encodeCursor(nextOffset.toString)) else None,
      hasMore)
  }

  private val EVENT_POLL_INTERVAL_MS = 200L
  private val RECONCILE_BATCH = 1000

  /** `key=value`, `key: value` and `key "value"` forms of the usual secret-bearing names. */
  private val REDACTION_PATTERN =
    ("(?i)((?:password|passwd|secret|token|credential|authorization)[\"']?\\s*[:=]\\s*)" +
      "[\"']?[^\\s\"',;]+").r
}
