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

package org.apache.kyuubi.server.api.v1

import javax.ws.rs._
import javax.ws.rs.core.{MediaType, Response}

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.store.ExecutionFilter

/** Runtime specifications a user may choose from; no startup command or secret is exposed. */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class RuntimeSpecsResource extends NotebookApiSupport {

  @GET
  def list(): Seq[RuntimeSpec] = notebooks.runtimes.specs

  @GET
  @Path("{runtimeSpecId}")
  def get(@PathParam("runtimeSpecId") runtimeSpecId: String): RuntimeSpec =
    notebooks.runtimes.spec(runtimeSpecId)
}

/**
 * Notebook sessions and the runtimes and executions inside them.
 *
 * A session is addressed directly rather than through its notebook, because it belongs to the
 * user who opened it: sharing a notebook never shares somebody else's compute.
 */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebookSessionsResource extends NotebookApiSupport {

  @GET
  @Path("{sessionId: [^:/]+}")
  def get(@PathParam("sessionId") sessionId: String): NotebookSessionView =
    NotebookSessionView(notebooks.sessions.require(principal, sessionId))

  @DELETE
  @Path("{sessionId: [^:/]+}")
  def delete(@PathParam("sessionId") sessionId: String): Response = {
    notebooks.sessions.stop(principal, sessionId)
    Response.noContent().build()
  }

  @POST
  @Path("{sessionId: [^:/]+}:restart")
  def restart(@PathParam("sessionId") sessionId: String): NotebookSessionView =
    NotebookSessionView(notebooks.sessions.restart(principal, sessionId))

  @POST
  @Path("{sessionId: [^:/]+}:reset")
  def reset(@PathParam("sessionId") sessionId: String): NotebookSessionView =
    NotebookSessionView(notebooks.sessions.reset(principal, sessionId))

  @POST
  @Path("{sessionId: [^:/]+}:stop")
  def stop(@PathParam("sessionId") sessionId: String): NotebookSessionView =
    NotebookSessionView(notebooks.sessions.stop(principal, sessionId))

  @GET
  @Path("{sessionId: [^:/]+}/runtimes")
  def listRuntimes(@PathParam("sessionId") sessionId: String): Seq[NotebookRuntimeView] = {
    val session = notebooks.sessions.require(principal, sessionId)
    notebooks.runtimes.list(session).map(NotebookRuntimeView.apply)
  }

  @POST
  @Path("{sessionId: [^:/]+}/runtimes")
  def createRuntime(
      @PathParam("sessionId") sessionId: String,
      request: CreateRuntimeRequest): NotebookRuntimeView = {
    val session = notebooks.sessions.require(principal, sessionId)
    val specId = Option(request).flatMap(r => Option(r.getRuntimeSpecId))
      .map(_.trim).filter(_.nonEmpty)
      .getOrElse(throw NotebookException.invalid("runtimeSpecId must be provided"))
    NotebookRuntimeView(
      notebooks.runtimes.create(session, specId, notebooks.runtimes.configurationOf(request)))
  }

  @POST
  @Path("{sessionId: [^:/]+}/executions")
  def submit(
      @PathParam("sessionId") sessionId: String,
      request: SubmitExecutionRequest): CellExecutionView =
    CellExecutionView(notebooks.executions.submit(principal, sessionId, request))

  @GET
  @Path("{sessionId: [^:/]+}/executions")
  def listExecutions(
      @PathParam("sessionId") sessionId: String,
      @QueryParam("limit") limit: java.lang.Integer): Seq[CellExecutionView] = {
    notebooks.sessions.require(principal, sessionId)
    notebooks.executions.list(
      principal,
      ExecutionFilter(
        notebookSessionId = Some(sessionId),
        limit = Option(limit).map(_.intValue()).getOrElse(50)))
      .map(CellExecutionView.apply)
  }
}

@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebookRuntimesResource extends NotebookApiSupport {

  @GET
  @Path("{runtimeId: [^:/]+}")
  def get(@PathParam("runtimeId") runtimeId: String): NotebookRuntimeView =
    NotebookRuntimeView(
      notebooks.runtimes.refresh(notebooks.runtimes.require(principal, runtimeId)))

  @POST
  @Path("{runtimeId: [^:/]+}:interrupt")
  def interrupt(@PathParam("runtimeId") runtimeId: String): NotebookRuntimeView =
    NotebookRuntimeView(
      notebooks.runtimes.interrupt(notebooks.runtimes.require(principal, runtimeId)))

  @POST
  @Path("{runtimeId: [^:/]+}:restart")
  def restart(@PathParam("runtimeId") runtimeId: String): NotebookRuntimeView =
    NotebookRuntimeView(
      notebooks.runtimes.restart(notebooks.runtimes.require(principal, runtimeId)))

  @POST
  @Path("{runtimeId: [^:/]+}:stop")
  def stop(@PathParam("runtimeId") runtimeId: String): NotebookRuntimeView =
    NotebookRuntimeView(notebooks.runtimes.stop(notebooks.runtimes.require(principal, runtimeId)))

  @DELETE
  @Path("{runtimeId: [^:/]+}")
  def delete(@PathParam("runtimeId") runtimeId: String): Response = {
    notebooks.runtimes.stop(notebooks.runtimes.require(principal, runtimeId))
    Response.noContent().build()
  }
}

@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class ExecutionsResource extends NotebookApiSupport {

  @GET
  @Path("{executionId: [^:/]+}")
  def get(@PathParam("executionId") executionId: String): CellExecutionView =
    CellExecutionView(notebooks.executions.get(principal, executionId))

  @POST
  @Path("{executionId: [^:/]+}:cancel")
  def cancel(@PathParam("executionId") executionId: String): CellExecutionView =
    CellExecutionView(notebooks.executions.cancel(principal, executionId))

  @POST
  @Path("{executionId: [^:/]+}:close")
  def close(@PathParam("executionId") executionId: String): CellExecutionView =
    CellExecutionView(notebooks.executions.close(principal, executionId))

  /**
   * Long-polls for events. An empty page after the wait elapses is a normal answer, not an
   * error; the client simply asks again from the same sequence.
   */
  @GET
  @Path("{executionId: [^:/]+}/events")
  def events(
      @PathParam("executionId") executionId: String,
      @QueryParam("afterSequence") @DefaultValue("0") afterSequence: Long,
      @QueryParam("waitMillis") @DefaultValue("0") waitMillis: Long,
      @QueryParam("limit") @DefaultValue("200") limit: Int): ExecutionEventPage =
    notebooks.executions.events(principal, executionId, afterSequence, waitMillis, limit)

  @GET
  @Path("{executionId: [^:/]+}/logs")
  def logs(
      @PathParam("executionId") executionId: String,
      @QueryParam("offset") @DefaultValue("0") offset: Long,
      @QueryParam("maxLines") @DefaultValue("200") maxLines: Int): ExecutionLogPage =
    notebooks.executions.logs(principal, executionId, offset, maxLines)

  @GET
  @Path("{executionId: [^:/]+}/outputs")
  def outputs(
      @PathParam("executionId") executionId: String,
      @QueryParam("afterSequence") @DefaultValue("0") afterSequence: Long,
      @QueryParam("limit") @DefaultValue("200") limit: Int): ExecutionOutputPage =
    notebooks.executions.outputs(principal, executionId, afterSequence, limit)

  @GET
  @Path("{executionId: [^:/]+}/schema")
  def schema(@PathParam("executionId") executionId: String): ExecutionSchema =
    notebooks.executions.schema(principal, executionId)

  @GET
  @Path("{executionId: [^:/]+}/results")
  def results(
      @PathParam("executionId") executionId: String,
      @QueryParam("cursor") cursor: String,
      @QueryParam("maxRows") @DefaultValue("100") maxRows: Int): ExecutionResultPage =
    notebooks.executions.results(
      principal,
      executionId,
      Option(cursor).filter(_.nonEmpty),
      maxRows)
}
