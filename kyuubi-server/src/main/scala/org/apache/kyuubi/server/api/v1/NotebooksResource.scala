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

import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.store.ExecutionFilter

/**
 * Notebook, cell, revision, permission and schedule endpoints.
 *
 * The `{notebookId}` templates exclude `:` so that an action path such as `{id}:clone` cannot be
 * swallowed by the plain `{id}` template.
 */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebooksResource extends NotebookApiSupport {

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[NotebookView]))),
    description = "Create a notebook owned by the caller.")
  @POST
  def create(request: CreateNotebookRequest): NotebookView = {
    val (notebook, cells) = notebooks.documents.createNotebook(principal, request)
    NotebookView(
      notebook,
      Some(PermissionRole.OWNER.toString),
      Some(cells.map(NotebookCellView.apply)))
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "List notebooks the caller may read.")
  @GET
  def list(
      @QueryParam("owner") owner: String,
      @QueryParam("folderId") folderId: String,
      @QueryParam("name") name: String,
      @QueryParam("cursor") cursor: String,
      @QueryParam("limit") limit: java.lang.Integer): NotebookPage[NotebookView] =
    notebooks.documents.listNotebooks(
      principal,
      Option(owner).filter(_.nonEmpty),
      Option(folderId).filter(_.nonEmpty),
      Option(name).filter(_.nonEmpty),
      None,
      None,
      Option(cursor).filter(_.nonEmpty),
      Option(limit).map(_.intValue()))

  @GET
  @Path("{notebookId: [^:/]+}")
  def get(
      @PathParam("notebookId") notebookId: String,
      @QueryParam("includeCells") includeCells: java.lang.Boolean): NotebookView = {
    val notebook = notebooks.documents.getNotebook(principal, notebookId)
    notebookView(notebook, Option(includeCells).forall(_.booleanValue()))
  }

  @PATCH
  @Path("{notebookId: [^:/]+}")
  def update(
      @PathParam("notebookId") notebookId: String,
      request: UpdateNotebookRequest): NotebookView =
    notebookView(notebooks.documents.updateNotebook(principal, notebookId, request), false)

  @DELETE
  @Path("{notebookId: [^:/]+}")
  def delete(
      @PathParam("notebookId") notebookId: String,
      @QueryParam("version") version: java.lang.Long): Response = {
    notebooks.documents.deleteNotebook(principal, notebookId, Option(version).map(_.longValue()))
    Response.noContent().build()
  }

  @POST
  @Path("{notebookId: [^:/]+}:clone")
  def cloneNotebook(
      @PathParam("notebookId") notebookId: String,
      request: CloneNotebookRequest): NotebookView = {
    val (notebook, cells) = notebooks.documents.cloneNotebook(principal, notebookId, request)
    NotebookView(
      notebook,
      Some(PermissionRole.OWNER.toString),
      Some(cells.map(NotebookCellView.apply)))
  }

  @POST
  @Path("{notebookId: [^:/]+}:move")
  def move(
      @PathParam("notebookId") notebookId: String,
      request: MoveNotebookRequest): NotebookView =
    notebookView(notebooks.documents.moveNotebook(principal, notebookId, request), false)

  @GET
  @Path("{notebookId: [^:/]+}:export")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def export(
      @PathParam("notebookId") notebookId: String,
      @QueryParam("format") format: String): Response = {
    val (payload, _) = notebooks.content.export(principal, notebookId, format)
    Response.ok(payload, MediaType.APPLICATION_JSON).build()
  }

  // -------------------------------------------------------------------------------------------
  // Cells
  // -------------------------------------------------------------------------------------------

  @GET
  @Path("{notebookId: [^:/]+}/cells")
  def listCells(@PathParam("notebookId") notebookId: String): Seq[NotebookCellView] = {
    notebooks.documents.getNotebook(principal, notebookId)
    notebooks.documents.listCells(notebookId).map(NotebookCellView.apply)
  }

  @POST
  @Path("{notebookId: [^:/]+}/cells")
  def createCell(
      @PathParam("notebookId") notebookId: String,
      request: CreateCellRequest): NotebookCellView =
    NotebookCellView(notebooks.documents.createCell(principal, notebookId, request))

  @GET
  @Path("{notebookId: [^:/]+}/cells/{cellId}")
  def getCell(
      @PathParam("notebookId") notebookId: String,
      @PathParam("cellId") cellId: String): NotebookCellView =
    NotebookCellView(notebooks.documents.getCell(principal, notebookId, cellId))

  @PATCH
  @Path("{notebookId: [^:/]+}/cells/{cellId}")
  def updateCell(
      @PathParam("notebookId") notebookId: String,
      @PathParam("cellId") cellId: String,
      request: UpdateCellRequest): NotebookCellView =
    NotebookCellView(notebooks.documents.updateCell(principal, notebookId, cellId, request))

  @DELETE
  @Path("{notebookId: [^:/]+}/cells/{cellId}")
  def deleteCell(
      @PathParam("notebookId") notebookId: String,
      @PathParam("cellId") cellId: String): Response = {
    notebooks.documents.deleteCell(principal, notebookId, cellId)
    Response.noContent().build()
  }

  @GET
  @Path("{notebookId: [^:/]+}/cells/{cellId}/config")
  def getCellConfig(
      @PathParam("notebookId") notebookId: String,
      @PathParam("cellId") cellId: String): Map[String, String] =
    notebooks.documents.getCell(principal, notebookId, cellId).configuration

  @PATCH
  @Path("{notebookId: [^:/]+}/cells/{cellId}/config")
  def updateCellConfig(
      @PathParam("notebookId") notebookId: String,
      @PathParam("cellId") cellId: String,
      request: UpdateCellConfigRequest): NotebookCellView =
    NotebookCellView(notebooks.documents.updateCellConfig(principal, notebookId, cellId, request))

  @PUT
  @Path("{notebookId: [^:/]+}/cells:reorder")
  def reorderCells(
      @PathParam("notebookId") notebookId: String,
      request: ReorderCellsRequest): Seq[NotebookCellView] =
    notebooks.documents.reorderCells(principal, notebookId, request).map(NotebookCellView.apply)

  // -------------------------------------------------------------------------------------------
  // Revisions
  // -------------------------------------------------------------------------------------------

  @GET
  @Path("{notebookId: [^:/]+}/revisions")
  def listRevisions(
      @PathParam("notebookId") notebookId: String,
      @QueryParam("cursor") cursor: String,
      @QueryParam("limit") limit: java.lang.Integer): NotebookPage[NotebookRevisionView] = {
    notebooks.documents.getNotebook(principal, notebookId)
    notebooks.revisions.list(
      notebookId,
      notebooks.documents.boundedLimit(Option(limit).map(_.intValue())),
      Option(cursor).filter(_.nonEmpty))
  }

  @POST
  @Path("{notebookId: [^:/]+}/revisions")
  def createRevision(
      @PathParam("notebookId") notebookId: String,
      request: CreateRevisionRequest): NotebookRevisionView = {
    val notebook = notebooks.documents.getNotebook(principal, notebookId)
    notebooks.permissions.requireWrite(notebook, principal)
    val revision = notebooks.revisions.create(
      notebook,
      notebooks.documents.listCells(notebookId),
      principal.user,
      Option(request).flatMap(r => Option(r.getReason)).filter(_.nonEmpty),
      protectedRevision = true)
    NotebookRevisionView(revision, None)
  }

  @GET
  @Path("{notebookId: [^:/]+}/revisions/{revisionNumber}")
  def getRevision(
      @PathParam("notebookId") notebookId: String,
      @PathParam("revisionNumber") revisionNumber: Long): NotebookRevisionView = {
    notebooks.documents.getNotebook(principal, notebookId)
    notebooks.revisions.get(notebookId, revisionNumber)
  }

  @POST
  @Path("{notebookId: [^:/]+}/revisions/{revisionNumber}:restore")
  def restoreRevision(
      @PathParam("notebookId") notebookId: String,
      @PathParam("revisionNumber") revisionNumber: Long): NotebookView = {
    val (notebook, cells) =
      notebooks.content.restoreRevision(principal, notebookId, revisionNumber)
    NotebookView(
      notebook,
      notebooks.documents.roleOf(notebook, principal),
      Some(cells.map(NotebookCellView.apply)))
  }

  @DELETE
  @Path("{notebookId: [^:/]+}/revisions/{revisionNumber}")
  def deleteRevision(
      @PathParam("notebookId") notebookId: String,
      @PathParam("revisionNumber") revisionNumber: Long): Response = {
    val notebook = notebooks.documents.getNotebook(principal, notebookId)
    notebooks.permissions.requireOwner(notebook, principal)
    notebooks.revisions.delete(notebookId, revisionNumber)
    Response.noContent().build()
  }

  // -------------------------------------------------------------------------------------------
  // Sessions and executions
  // -------------------------------------------------------------------------------------------

  @POST
  @Path("{notebookId: [^:/]+}/sessions")
  def createSession(
      @PathParam("notebookId") notebookId: String,
      request: CreateSessionRequest): NotebookSessionView =
    NotebookSessionView(notebooks.sessions.create(principal, notebookId, request))

  @GET
  @Path("{notebookId: [^:/]+}/sessions")
  def listSessions(@PathParam("notebookId") notebookId: String): Seq[NotebookSessionView] =
    notebooks.sessions.list(principal, notebookId).map(NotebookSessionView.apply)

  @GET
  @Path("{notebookId: [^:/]+}/executions")
  def listExecutions(
      @PathParam("notebookId") notebookId: String,
      @QueryParam("limit") limit: java.lang.Integer): Seq[CellExecutionView] = {
    val notebook = notebooks.documents.getNotebook(principal, notebookId)
    notebooks.executions.list(
      principal,
      ExecutionFilter(
        notebookId = Some(notebook.id),
        limit = Option(limit).map(_.intValue()).getOrElse(50)))
      .map(CellExecutionView.apply)
  }

  // -------------------------------------------------------------------------------------------
  // Permissions and schedule
  // -------------------------------------------------------------------------------------------

  @GET
  @Path("{notebookId: [^:/]+}/permissions")
  def listPermissions(
      @PathParam("notebookId") notebookId: String): Seq[NotebookPermissionView] =
    notebooks.permissions.list(notebooks.documents.loadNotebook(notebookId), principal)

  @PUT
  @Path("{notebookId: [^:/]+}/permissions")
  def setPermissions(
      @PathParam("notebookId") notebookId: String,
      request: SetPermissionsRequest): Seq[NotebookPermissionView] =
    notebooks.permissions.replace(
      notebooks.documents.loadNotebook(notebookId),
      principal,
      request)

  @GET
  @Path("{notebookId: [^:/]+}/schedule")
  def getSchedule(@PathParam("notebookId") notebookId: String): NotebookScheduleView =
    notebooks.schedules.get(notebooks.documents.loadNotebook(notebookId), principal).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"notebook $notebookId has no schedule")
    }

  @PUT
  @Path("{notebookId: [^:/]+}/schedule")
  def setSchedule(
      @PathParam("notebookId") notebookId: String,
      request: SetScheduleRequest): NotebookScheduleView =
    notebooks.schedules.set(notebooks.documents.loadNotebook(notebookId), principal, request)

  @DELETE
  @Path("{notebookId: [^:/]+}/schedule")
  def deleteSchedule(@PathParam("notebookId") notebookId: String): Response = {
    notebooks.schedules.delete(notebooks.documents.loadNotebook(notebookId), principal)
    Response.noContent().build()
  }
}
