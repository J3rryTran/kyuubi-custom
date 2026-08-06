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

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.runtime.RuntimeAdapterRegistry
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Notebook sessions and the runtimes inside them.
 *
 * A session belongs to the user who opened it, not to the notebook: sharing a notebook grants
 * access to its saved content, never to somebody else's compute. Every method here therefore
 * checks session ownership separately from the notebook permission that got the caller this far.
 */
class NotebookSessionService(
    store: NotebookStore,
    documents: NotebookDocumentService,
    permissions: NotebookPermissionService,
    runtimes: NotebookRuntimeService,
    instanceUri: () => String) extends Logging {

  def create(
      principal: NotebookPrincipal,
      notebookId: String,
      request: CreateSessionRequest): NotebookSession = {
    val notebook = documents.loadNotebook(notebookId)
    // Opening a session is an execute action, which a viewer may not perform.
    permissions.requireWrite(notebook, principal)
    val now = System.currentTimeMillis()
    val session = NotebookSession(
      id = UUID.randomUUID().toString,
      notebookId = notebookId,
      owner = principal.user,
      state = NotebookSessionState.IDLE,
      runtimeProfile = Option(request).flatMap(r => Option(r.getRuntimeProfile))
        .map(_.trim).filter(_.nonEmpty)
        .orElse(notebook.runtimeProfile),
      createdAt = now,
      lastActivityAt = now,
      stoppedAt = None,
      failureMessage = None,
      kyuubiInstance = Some(instanceUri()),
      version = 1L)
    store.createSession(session)
    session
  }

  def list(principal: NotebookPrincipal, notebookId: String): Seq[NotebookSession] = {
    val notebook = documents.loadNotebook(notebookId)
    permissions.requireRead(notebook, principal)
    // Only the caller's own sessions; another user's session is not theirs to see or touch.
    store.listSessions(notebookId).filter(session =>
      principal.admin || session.owner == principal.user)
  }

  def load(sessionId: String): NotebookSession =
    store.getSession(sessionId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_SESSION_NOT_FOUND,
        s"notebook session $sessionId was not found")
    }

  def require(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = load(sessionId)
    if (!principal.admin && session.owner != principal.user) {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_SESSION_NOT_FOUND,
        s"notebook session $sessionId was not found")
    }
    requireLocal(session)
    session
  }

  /**
   * A session's runtimes live in the Kyuubi instance that created them. Without cross-instance
   * proxying, serving it from another instance would silently do nothing, so it is refused with
   * a state a client can act on.
   */
  private def requireLocal(session: NotebookSession): Unit = {
    session.kyuubiInstance.filter(_ != instanceUri()).foreach { owner =>
      throw new NotebookException(
        NotebookErrorCode.KYUUBI_SESSION_LOST,
        "this notebook session belongs to another Kyuubi instance; open a new one",
        retryable = false,
        details = Map("ownedBy" -> owner))
    }
  }

  def restart(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = require(principal, sessionId)
    runtimes.listFor(session).foreach(runtime => runtimes.restart(runtime))
    touch(session, NotebookSessionState.IDLE)
  }

  /** Reset is a restart plus discarding transient session state; content is never touched. */
  def reset(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = require(principal, sessionId)
    val resetting = touch(session, NotebookSessionState.RESETTING)
    runtimes.listFor(resetting).foreach(runtime => runtimes.restart(runtime))
    touch(resetting, NotebookSessionState.IDLE)
  }

  /** Idempotent: stopping an already stopped session succeeds and changes nothing. */
  def stop(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = require(principal, sessionId)
    if (NotebookSessionState.terminal.contains(session.state)) {
      session
    } else {
      val stopping = touch(session, NotebookSessionState.STOPPING)
      runtimes.listFor(stopping).foreach(runtime => runtimes.stop(runtime))
      val now = System.currentTimeMillis()
      val stopped = stopping.copy(
        state = NotebookSessionState.STOPPED,
        stoppedAt = Some(now),
        lastActivityAt = now,
        version = stopping.version + 1)
      if (!store.updateSession(stopped, stopping.version)) {
        throw NotebookException.versionConflict("the session was modified concurrently")
      }
      stopped
    }
  }

  def touch(session: NotebookSession, state: NotebookSessionState.Value): NotebookSession = {
    val updated = session.copy(
      state = state,
      lastActivityAt = System.currentTimeMillis(),
      version = session.version + 1)
    if (!store.updateSession(updated, session.version)) {
      throw NotebookException.versionConflict("the session was modified concurrently")
    }
    updated
  }

  /**
   * Reclaims idle runtimes across every live session, and stops a session once nothing is left
   * running in it. Called on a timer, so an abandoned notebook does not hold an interpreter and
   * a Kyuubi session open indefinitely.
   */
  def reapIdle(idleTimeoutMillis: Long): Unit = {
    if (idleTimeoutMillis > 0) {
      store.listLiveSessions().foreach { session =>
        try {
          runtimes.reapIdle(session, idleTimeoutMillis)
          val remaining = runtimes.listFor(session)
          if (remaining.isEmpty && session.lastActivityAt <
              System.currentTimeMillis() - idleTimeoutMillis) {
            val now = System.currentTimeMillis()
            val stopped = session.copy(
              state = NotebookSessionState.STOPPED,
              stoppedAt = Some(now),
              lastActivityAt = now,
              version = session.version + 1)
            store.updateSession(stopped, session.version)
          }
        } catch {
          case NonFatal(e) => warn(s"Failed to reap notebook session ${session.id}", e)
        }
      }
    }
  }

  /**
   * Marks sessions whose runtimes cannot have survived. Called at startup: a session created by
   * this instance before a restart has no live Kyuubi session behind it any more, and pretending
   * otherwise would make the first execution fail in a confusing way.
   */
  def reconcileAfterRestart(): Unit = {
    store.listLiveSessions().foreach { session =>
      try {
        val now = System.currentTimeMillis()
        runtimes.listFor(session).foreach(runtimes.markLost)
        val lost = session.copy(
          state = NotebookSessionState.LOST,
          failureMessage = Some("the server restarted while this session was open"),
          lastActivityAt = now,
          stoppedAt = Some(now),
          version = session.version + 1)
        store.updateSession(lost, session.version)
      } catch {
        case NonFatal(e) => warn(s"Failed to reconcile notebook session ${session.id}", e)
      }
    }
  }
}

/**
 * Runtime lifecycle. Everything that touches a backend goes through an adapter, so this class
 * stays the same whether the runtime is SQL or, later, CPython.
 */
class NotebookRuntimeService(
    store: NotebookStore,
    registry: RuntimeAdapterRegistry,
    instanceUri: () => String) extends Logging {

  def specs: Seq[RuntimeSpec] = registry.specs

  def spec(runtimeSpecId: String): RuntimeSpec = registry.spec(runtimeSpecId)

  def listFor(session: NotebookSession): Seq[NotebookRuntime] =
    store.listRuntimes(session.id).filterNot(runtime =>
      RuntimeState.terminal.contains(runtime.state))

  def list(session: NotebookSession): Seq[NotebookRuntime] = store.listRuntimes(session.id)

  def load(runtimeId: String): NotebookRuntime =
    store.getRuntime(runtimeId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_NOT_FOUND,
        s"runtime $runtimeId was not found")
    }

  def require(principal: NotebookPrincipal, runtimeId: String): NotebookRuntime = {
    val runtime = load(runtimeId)
    if (!principal.admin && runtime.owner != principal.user) {
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_NOT_FOUND,
        s"runtime $runtimeId was not found")
    }
    runtime
  }

  /** Reuses the session's live runtime for a language, starting one only when there is none. */
  def ensureFor(
      session: NotebookSession,
      language: CellLanguage.Value,
      requestedSpecId: Option[String],
      configuration: Map[String, String]): NotebookRuntime = {
    val specId = requestedSpecId.getOrElse(registry.defaultSpecFor(language).id)
    listFor(session).find(runtime => runtime.runtimeSpecId == specId) match {
      case Some(runtime) => runtime
      case None => create(session, specId, configuration)
    }
  }

  def create(
      session: NotebookSession,
      runtimeSpecId: String,
      configuration: Map[String, String]): NotebookRuntime = {
    val adapter = registry.get(runtimeSpecId)
    val spec = adapter.runtimeSpec
    if (!spec.enabled) {
      throw new NotebookException(
        NotebookErrorCode.RUNTIME_SPEC_NOT_FOUND,
        s"runtime spec $runtimeSpecId is disabled")
    }
    val now = System.currentTimeMillis()
    val creating = NotebookRuntime(
      id = UUID.randomUUID().toString,
      notebookSessionId = session.id,
      runtimeSpecId = spec.id,
      runtimeType = adapter.runtimeType,
      language = CellLanguage.withName(spec.language),
      owner = session.owner,
      state = RuntimeState.CREATING,
      generation = 1L,
      environmentRevisionId = None,
      createdAt = now,
      lastActivityAt = now,
      stoppedAt = None,
      failureMessage = None,
      internalRuntimeHandle = None,
      internalRuntimeLocation = None,
      version = 1L)
    // Persisted before the backend call, so a crash mid-start leaves a row to reconcile rather
    // than an orphaned Kyuubi session nobody knows about.
    store.createRuntime(creating)
    try {
      val started = adapter.startRuntime(creating, configuration)
      val ready = creating.copy(
        state = RuntimeState.IDLE,
        internalRuntimeHandle = Some(started.handle),
        internalRuntimeLocation = started.location.orElse(Some(instanceUri())),
        lastActivityAt = System.currentTimeMillis(),
        version = creating.version + 1)
      store.updateRuntime(ready, creating.version)
      ready
    } catch {
      case NonFatal(e) =>
        val failed = creating.copy(
          state = RuntimeState.FAILED,
          failureMessage = Some("the runtime could not be started"),
          lastActivityAt = System.currentTimeMillis(),
          version = creating.version + 1)
        store.updateRuntime(failed, creating.version)
        warn(s"Failed to start runtime ${creating.id}", e)
        throw new NotebookException(
          NotebookErrorCode.KYUUBI_UNAVAILABLE,
          "the runtime could not be started",
          retryable = true,
          cause = e)
    }
  }

  def refresh(runtime: NotebookRuntime): NotebookRuntime = {
    if (RuntimeState.terminal.contains(runtime.state)) {
      runtime
    } else {
      val status = registry.get(runtime.runtimeSpecId).getRuntimeStatus(runtime)
      if (status.state == runtime.state) {
        runtime
      } else {
        val updated = runtime.copy(
          state = status.state,
          failureMessage = status.failureMessage.orElse(runtime.failureMessage),
          lastActivityAt = System.currentTimeMillis(),
          version = runtime.version + 1)
        store.updateRuntime(updated, runtime.version)
        updated
      }
    }
  }

  def interrupt(runtime: NotebookRuntime): NotebookRuntime = transition(
    runtime,
    RuntimeState.INTERRUPTING,
    () => (),
    RuntimeState.IDLE)

  /** A restart clears variables, so the generation is bumped and old executions stay attributed. */
  def restart(runtime: NotebookRuntime): NotebookRuntime = {
    val adapter = registry.get(runtime.runtimeSpecId)
    val restarting = store.getRuntime(runtime.id).getOrElse(runtime)
    try {
      val started = adapter.restartRuntime(restarting)
      val updated = restarting.copy(
        state = RuntimeState.IDLE,
        generation = restarting.generation + 1,
        internalRuntimeHandle = Some(started.handle),
        internalRuntimeLocation = started.location.orElse(Some(instanceUri())),
        failureMessage = None,
        lastActivityAt = System.currentTimeMillis(),
        version = restarting.version + 1)
      store.updateRuntime(updated, restarting.version)
      updated
    } catch {
      case NonFatal(e) =>
        warn(s"Failed to restart runtime ${runtime.id}", e)
        markFailed(restarting, "the runtime could not be restarted")
    }
  }

  /** Idempotent: stopping a stopped runtime is a no-op that still reports success. */
  def stop(runtime: NotebookRuntime): NotebookRuntime = {
    if (RuntimeState.terminal.contains(runtime.state)) {
      runtime
    } else {
      try registry.get(runtime.runtimeSpecId).stopRuntime(runtime)
      catch { case NonFatal(e) => warn(s"Failed to stop runtime ${runtime.id}", e) }
      val now = System.currentTimeMillis()
      val stopped = runtime.copy(
        state = RuntimeState.STOPPED,
        internalRuntimeHandle = None,
        stoppedAt = Some(now),
        lastActivityAt = now,
        version = runtime.version + 1)
      store.updateRuntime(stopped, runtime.version)
      stopped
    }
  }

  /**
   * Records that a runtime was used. Without this the idle reaper would measure time since the
   * runtime started rather than since it last did anything, and would stop a busy runtime.
   */
  def touchActivity(runtime: NotebookRuntime): NotebookRuntime = {
    val touched = runtime.copy(
      lastActivityAt = System.currentTimeMillis(),
      version = runtime.version + 1)
    if (store.updateRuntime(touched, runtime.version)) touched else runtime
  }

  /**
   * Stops runtimes that have been idle for longer than the timeout, releasing the interpreter
   * process and its scratch directory. Variables and anything installed from inside a cell go
   * with it; a managed environment is on disk and is untouched.
   */
  def reapIdle(session: NotebookSession, idleTimeoutMillis: Long): Seq[NotebookRuntime] = {
    if (idleTimeoutMillis <= 0) {
      Seq.empty
    } else {
      val deadline = System.currentTimeMillis() - idleTimeoutMillis
      listFor(session)
        .filter(runtime => runtime.state != RuntimeState.BUSY)
        .filter(_.lastActivityAt < deadline)
        .map { runtime =>
          info(s"Reclaiming runtime ${runtime.id} after ${idleTimeoutMillis}ms idle")
          stop(runtime)
        }
    }
  }

  def markLost(runtime: NotebookRuntime): NotebookRuntime = {
    if (RuntimeState.terminal.contains(runtime.state)) {
      runtime
    } else {
      val now = System.currentTimeMillis()
      val lost = runtime.copy(
        state = RuntimeState.LOST,
        internalRuntimeHandle = None,
        failureMessage = Some("the runtime did not survive a server restart"),
        stoppedAt = Some(now),
        lastActivityAt = now,
        version = runtime.version + 1)
      store.updateRuntime(lost, runtime.version)
      lost
    }
  }

  private def markFailed(runtime: NotebookRuntime, message: String): NotebookRuntime = {
    val failed = runtime.copy(
      state = RuntimeState.FAILED,
      failureMessage = Some(message),
      lastActivityAt = System.currentTimeMillis(),
      version = runtime.version + 1)
    store.updateRuntime(failed, runtime.version)
    failed
  }

  private def transition(
      runtime: NotebookRuntime,
      during: RuntimeState.Value,
      action: () => Unit,
      after: RuntimeState.Value): NotebookRuntime = {
    val marked = runtime.copy(
      state = during,
      lastActivityAt = System.currentTimeMillis(),
      version = runtime.version + 1)
    store.updateRuntime(marked, runtime.version)
    action()
    val settled = marked.copy(
      state = after,
      lastActivityAt = System.currentTimeMillis(),
      version = marked.version + 1)
    store.updateRuntime(settled, marked.version)
    settled
  }

  def configurationOf(request: CreateRuntimeRequest): Map[String, String] =
    Option(request).flatMap(r => Option(r.getConfiguration))
      .map(_.asScala.toMap)
      .getOrElse(Map.empty)
}
