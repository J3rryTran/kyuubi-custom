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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.runtime._
import org.apache.kyuubi.server.notebook.service._
import org.apache.kyuubi.server.notebook.store.ExecutionFilter

/**
 * Exercises the execution lifecycle against a scripted adapter.
 *
 * A real Kyuubi engine is deliberately not used: these assertions are about the service layer's
 * state machine, idempotency and authorization, which must hold whatever the backend does. The
 * adapter contract itself is what [[KyuubiSqlRuntimeAdapter]] implements against Spark.
 */
class NotebookExecutionSuite extends NotebookTestBase {

  private var adapter: ScriptedAdapter = _
  private var registry: RuntimeAdapterRegistry = _
  private var runtimeService: NotebookRuntimeService = _
  private var sessionService: NotebookSessionService = _
  private var executionService: NotebookExecutionService = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    adapter = new ScriptedAdapter
    registry = new RuntimeAdapterRegistry(Seq(adapter))
    runtimeService = new NotebookRuntimeService(manager.store, registry, () => "test-instance")
    sessionService = new NotebookSessionService(
      manager.store,
      documents,
      permissions,
      runtimeService,
      () => "test-instance")
    executionService = new NotebookExecutionService(
      conf,
      manager.store,
      documents,
      permissions,
      sessionService,
      runtimeService,
      registry)
  }

  private def openSession(principal: NotebookPrincipal, name: String): NotebookSession = {
    val (notebook, _) = createNotebook(principal, name)
    sessionService.create(principal, notebook.id, new CreateSessionRequest)
  }

  private def submit(
      principal: NotebookPrincipal,
      session: NotebookSession,
      source: String,
      requestId: Option[String] = None): CellExecution = {
    val request = new SubmitExecutionRequest
    request.setSource(source)
    request.setLanguage("SQL")
    requestId.foreach(request.setClientRequestId)
    executionService.submit(principal, session.id, request)
  }

  test("a submission returns immediately and starts a runtime on demand") {
    val session = openSession(alice, "exec-basic")
    val execution = submit(alice, session, "select 1")

    assert(execution.state === ExecutionState.STARTING)
    assert(execution.submittedBy === "alice")
    assert(execution.sourceSnapshot === "select 1")
    val runtimes = runtimeService.list(session)
    assert(runtimes.size === 1)
    assert(runtimes.head.state === RuntimeState.IDLE)
  }

  test("a second submission reuses the runtime of its session") {
    val session = openSession(alice, "exec-reuse")
    submit(alice, session, "select 1")
    submit(alice, session, "select 2")
    assert(runtimeService.list(session).size === 1)
  }

  test("the source is snapshotted, so editing the cell afterwards changes nothing") {
    val (notebook, created) = createNotebook(
      alice,
      "exec-snapshot",
      cells = Seq(("CODE", "SQL", "select original")))
    val session = sessionService.create(alice, notebook.id, new CreateSessionRequest)
    val request = new SubmitExecutionRequest
    request.setCellId(created.head.id)
    val execution = executionService.submit(alice, session.id, request)

    val update = new UpdateCellRequest
    update.setSource("select changed")
    documents.updateCell(alice, notebook.id, created.head.id, update)

    assert(executionService.require(alice, execution.id).sourceSnapshot === "select original")
  }

  test("a repeated clientRequestId returns the first execution rather than running twice") {
    val session = openSession(alice, "exec-idempotent")
    // The adapter is shared by the whole suite, so only the delta is meaningful.
    val before = adapter.executeCount.get()
    val first = submit(alice, session, "select 1", Some("stable-id"))
    val second = submit(alice, session, "select 1", Some("stable-id"))
    assert(first.id === second.id)
    assert(adapter.executeCount.get() - before === 1)
  }

  test("the same clientRequestId with a different payload is a conflict") {
    val session = openSession(alice, "exec-idempotent-conflict")
    submit(alice, session, "select 1", Some("conflicting-id"))
    interceptNotebook(NotebookErrorCode.VERSION_CONFLICT) {
      submit(alice, session, "select 2", Some("conflicting-id"))
    }
  }

  test("state moves to succeeded once the adapter finishes") {
    val session = openSession(alice, "exec-succeeds")
    val execution = submit(alice, session, "select 1")
    adapter.finish(execution.id, ExecutionState.SUCCEEDED)
    assert(executionService.get(alice, execution.id).state === ExecutionState.SUCCEEDED)
  }

  test("a failure carries the adapter's message and never reports success") {
    val session = openSession(alice, "exec-fails")
    val execution = submit(alice, session, "select bad")
    adapter.fail(execution.id, "syntax error near 'bad'")
    val failed = executionService.get(alice, execution.id)
    assert(failed.state === ExecutionState.FAILED)
    assert(failed.errorMessage.contains("syntax error near 'bad'"))
  }

  test("events are replayable from any sequence a client already has") {
    val session = openSession(alice, "exec-events")
    val execution = submit(alice, session, "select 1")
    adapter.finish(execution.id, ExecutionState.SUCCEEDED)
    executionService.get(alice, execution.id)

    val all = executionService.events(alice, execution.id, 0L, 0L, 100)
    assert(all.events.map(_.eventType) === Seq("QUEUED", "STARTING", "SUCCEEDED"))
    assert(all.events.map(_.sequence) === Seq(1L, 2L, 3L))

    val replayed = executionService.events(alice, execution.id, 1L, 0L, 100)
    assert(replayed.events.map(_.eventType) === Seq("STARTING", "SUCCEEDED"))
    assert(executionService.events(alice, execution.id, 3L, 0L, 100).events.isEmpty)
  }

  test("cancel moves through canceling to canceled") {
    val session = openSession(alice, "exec-cancel")
    val execution = submit(alice, session, "select 1")
    adapter.cancelAcknowledges = true
    val canceled = executionService.cancel(alice, execution.id)
    assert(canceled.state === ExecutionState.CANCELED)
    assert(adapter.interrupted.contains(execution.id))
  }

  test("cancelling a finished execution is a no-op") {
    val session = openSession(alice, "exec-cancel-finished")
    val execution = submit(alice, session, "select 1")
    adapter.finish(execution.id, ExecutionState.SUCCEEDED)
    executionService.get(alice, execution.id)
    assert(executionService.cancel(alice, execution.id).state === ExecutionState.SUCCEEDED)
  }

  test("close releases the backend resources and is idempotent") {
    val session = openSession(alice, "exec-close")
    val execution = submit(alice, session, "select 1")
    adapter.finish(execution.id, ExecutionState.SUCCEEDED)
    executionService.get(alice, execution.id)

    assert(executionService.close(alice, execution.id).state === ExecutionState.CLOSED)
    assert(executionService.close(alice, execution.id).state === ExecutionState.CLOSED)
    assert(adapter.closed.count(_ == execution.id) === 1)
  }

  test("results page forwards and refuses a backwards jump") {
    val session = openSession(alice, "exec-results")
    val execution = submit(alice, session, "select 1")
    adapter.rows = (1 to 5).map(i => Seq(i.toString, s"row-$i"))
    adapter.finish(execution.id, ExecutionState.SUCCEEDED)
    executionService.get(alice, execution.id)

    val first = executionService.results(alice, execution.id, None, 2)
    assert(first.rows === Seq(Seq("1", "row-1"), Seq("2", "row-2")))
    assert(first.hasMore)

    val second = executionService.results(alice, execution.id, first.nextCursor, 2)
    assert(second.rows === Seq(Seq("3", "row-3"), Seq("4", "row-4")))

    // Re-reading the page just delivered must be safe; jumping back must not silently lie.
    assert(executionService.results(alice, execution.id, first.nextCursor, 2).rows === second.rows)
    interceptNotebook(NotebookErrorCode.RESULT_EXPIRED) {
      executionService.results(alice, execution.id, None, 2)
    }
  }

  test("log lines are redacted before they leave the server") {
    val session = openSession(alice, "exec-logs")
    val execution = submit(alice, session, "select 1")
    adapter.logLines = Seq(
      "connecting with password=hunter2 to the store",
      "token: abcdef123456",
      "plain line")
    val page = executionService.logs(alice, execution.id, 0L, 10)
    assert(page.lines.head === "connecting with password=*** to the store")
    assert(page.lines(1) === "token: ***")
    assert(page.lines(2) === "plain line")
  }

  test("another user cannot see or touch an execution") {
    val session = openSession(alice, "exec-isolation")
    val execution = submit(alice, session, "select 1")
    interceptNotebook(NotebookErrorCode.EXECUTION_NOT_FOUND) {
      executionService.get(bob, execution.id)
    }
    interceptNotebook(NotebookErrorCode.EXECUTION_NOT_FOUND) {
      executionService.cancel(bob, execution.id)
    }
  }

  test("another user cannot use somebody else's session even with notebook access") {
    val (notebook, _) = createNotebook(alice, "exec-shared-notebook")
    val session = sessionService.create(alice, notebook.id, new CreateSessionRequest)
    val entry = new PermissionEntryRequest
    entry.setPrincipalType("USER")
    entry.setPrincipalId("bob")
    entry.setRole("EDITOR")
    val grant = new SetPermissionsRequest
    grant.setPermissions(Seq(entry).asJava)
    permissions.replace(documents.loadNotebook(notebook.id), alice, grant)

    // Sharing a notebook shares its content, never the owner's compute.
    interceptNotebook(NotebookErrorCode.NOTEBOOK_SESSION_NOT_FOUND) {
      submit(bob, session, "select 1")
    }
  }

  test("a viewer may not open a session at all") {
    val (notebook, _) = createNotebook(alice, "exec-viewer")
    val entry = new PermissionEntryRequest
    entry.setPrincipalType("USER")
    entry.setPrincipalId("bob")
    entry.setRole("VIEWER")
    val grant = new SetPermissionsRequest
    grant.setPermissions(Seq(entry).asJava)
    permissions.replace(documents.loadNotebook(notebook.id), alice, grant)

    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      sessionService.create(bob, notebook.id, new CreateSessionRequest)
    }
  }

  test("a restart bumps the generation and orphans the executions of the old one") {
    val session = openSession(alice, "exec-restart")
    val execution = submit(alice, session, "select 1")
    val runtime = runtimeService.list(session).head

    val restarted = runtimeService.restart(runtime)
    assert(restarted.generation === runtime.generation + 1)

    // The outcome of work from the previous generation can no longer be established.
    val orphaned = executionService.get(alice, execution.id)
    assert(orphaned.state === ExecutionState.LOST)
    assert(orphaned.errorCode.contains(NotebookErrorCode.RUNTIME_LOST.toString))
  }

  test("stopping a session stops its runtimes and is idempotent") {
    val session = openSession(alice, "exec-stop")
    submit(alice, session, "select 1")
    val stopped = sessionService.stop(alice, session.id)
    assert(stopped.state === NotebookSessionState.STOPPED)
    assert(runtimeService.list(session).forall(_.state == RuntimeState.STOPPED))
    assert(sessionService.stop(alice, session.id).state === NotebookSessionState.STOPPED)
  }

  test("reconciliation after a restart loses unfinished work instead of guessing") {
    val session = openSession(alice, "exec-reconcile")
    val execution = submit(alice, session, "select 1")

    executionService.reconcileAfterRestart()
    sessionService.reconcileAfterRestart()

    val reconciled = manager.store.getExecution(execution.id).get
    assert(reconciled.state === ExecutionState.LOST)
    assert(manager.store.getSession(session.id).get.state === NotebookSessionState.LOST)
  }

  test("an idle runtime is reclaimed and its session stopped with it") {
    val session = openSession(alice, "exec-idle")
    submit(alice, session, "select 1")
    assert(runtimeService.listFor(session).size === 1)

    // A zero timeout disables reaping entirely, which is what the config documents.
    sessionService.reapIdle(0L)
    assert(runtimeService.listFor(session).size === 1)

    // Everything is older than a negative-age deadline, so this reclaims immediately.
    sessionService.reapIdle(1L)
    assert(runtimeService.listFor(session).isEmpty)
    assert(manager.store.getSession(session.id).get.state === NotebookSessionState.STOPPED)
  }

  test("a runtime in use is not reclaimed") {
    val session = openSession(alice, "exec-idle-busy")
    submit(alice, session, "select 1")
    // Submitting records activity, so a generous timeout must leave the runtime alone.
    sessionService.reapIdle(java.util.concurrent.TimeUnit.HOURS.toMillis(1))
    assert(runtimeService.listFor(session).size === 1)
  }

  test("executions are listed for their notebook") {
    val session = openSession(alice, "exec-listing")
    submit(alice, session, "select 1")
    submit(alice, session, "select 2")
    val listed = executionService.list(
      alice,
      ExecutionFilter(notebookSessionId = Some(session.id), limit = 10))
    assert(listed.size === 2)
    assert(listed.forall(_.notebookId == session.notebookId))
  }
}

/**
 * A runtime adapter whose behaviour the test drives directly, so a state transition can be
 * observed without waiting on a real engine.
 */
private class ScriptedAdapter extends TabularNotebookRuntimeAdapter {

  val executeCount = new AtomicInteger(0)
  private val statuses = new ConcurrentHashMap[String, AdapterExecutionStatus]()
  var interrupted: Seq[String] = Seq.empty
  var closed: Seq[String] = Seq.empty
  var cancelAcknowledges: Boolean = false
  var rows: Seq[Seq[String]] = Seq.empty
  var logLines: Seq[String] = Seq.empty

  override val runtimeType: String = "SQL"

  override val runtimeSpec: RuntimeSpec = RuntimeSpec(
    id = "scripted-sql",
    displayName = "Scripted SQL",
    language = CellLanguage.SQL.toString,
    version = "test",
    enabled = true,
    configurableKeys = Seq.empty,
    limits = Map.empty)

  override def startRuntime(
      runtime: NotebookRuntime,
      configuration: Map[String, String]): AdapterRuntime =
    AdapterRuntime(s"handle-${runtime.id}", Some("test-instance"))

  override def getRuntimeStatus(runtime: NotebookRuntime): AdapterRuntimeStatus =
    AdapterRuntimeStatus(RuntimeState.IDLE, None)

  override def execute(
      runtime: NotebookRuntime,
      execution: CellExecution,
      configuration: Map[String, String]): AdapterExecution = {
    executeCount.incrementAndGet()
    statuses.put(
      execution.id,
      AdapterExecutionStatus(ExecutionState.STARTING, None, None, None, None, false))
    AdapterExecution(s"op-${execution.id}")
  }

  override def getExecutionStatus(execution: CellExecution): AdapterExecutionStatus =
    Option(statuses.get(execution.id)).getOrElse(
      AdapterExecutionStatus(ExecutionState.STARTING, None, None, None, None, false))

  override def interruptExecution(execution: CellExecution): Unit = {
    interrupted = interrupted :+ execution.id
    if (cancelAcknowledges) {
      statuses.put(
        execution.id,
        AdapterExecutionStatus(
          ExecutionState.CANCELED,
          None,
          Some(System.currentTimeMillis()),
          None,
          None,
          false))
    }
  }

  override def closeExecution(execution: CellExecution): Unit = closed = closed :+ execution.id

  override def restartRuntime(runtime: NotebookRuntime): AdapterRuntime =
    AdapterRuntime(s"handle-${runtime.id}-restarted", Some("test-instance"))

  override def stopRuntime(runtime: NotebookRuntime): Unit = ()

  override def fetchLogs(execution: CellExecution, offset: Long, maxLines: Int): AdapterLogPage = {
    val page = logLines.drop(offset.toInt).take(maxLines)
    AdapterLogPage(page, offset + page.size, logLines.size > offset + page.size)
  }

  override def fetchSchema(execution: CellExecution): ExecutionSchema =
    ExecutionSchema(Seq(
      ColumnSchema("id", "INT_TYPE", 1, None),
      ColumnSchema("label", "STRING_TYPE", 2, None)))

  override def fetchResults(
      execution: CellExecution,
      offset: Long,
      maxRows: Int): AdapterResultPage = {
    val page = rows.drop(offset.toInt).take(maxRows)
    AdapterResultPage(page, offset + page.size, rows.size > offset + page.size)
  }

  def finish(executionId: String, state: ExecutionState.Value): Unit =
    statuses.put(
      executionId,
      AdapterExecutionStatus(
        state,
        Some(System.currentTimeMillis()),
        Some(System.currentTimeMillis()),
        None,
        None,
        hasResultSet = true))

  def fail(executionId: String, message: String): Unit =
    statuses.put(
      executionId,
      AdapterExecutionStatus(
        ExecutionState.FAILED,
        Some(System.currentTimeMillis()),
        Some(System.currentTimeMillis()),
        Some("SQL_EXECUTION_FAILED"),
        Some(message),
        hasResultSet = false))
}
