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
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.server.notebook.api._

/**
 * Collection-level notebook actions.
 *
 * `notebooks:import` and `notebooks:search` are siblings of `notebooks` rather than children of
 * it, so they cannot live in [[NotebooksResource]]; each gets its own sub-resource reached from
 * [[ApiRootResource]].
 */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebookImportResource extends NotebookApiSupport {

  @POST
  def importNotebook(request: ImportNotebookRequest): NotebookView = {
    val (notebook, cells) = notebooks.content.importNotebook(principal, request)
    NotebookView(
      notebook,
      Some(PermissionRole.OWNER.toString),
      Some(cells.map(NotebookCellView.apply)))
  }
}

@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebookSearchResource extends NotebookApiSupport {

  @GET
  def search(
      @QueryParam("q") query: String,
      @QueryParam("folderId") folderId: String,
      @QueryParam("language") language: String,
      @QueryParam("cursor") cursor: String,
      @QueryParam("limit") limit: java.lang.Integer): NotebookPage[NotebookView] =
    notebooks.documents.listNotebooks(
      principal,
      None,
      Option(folderId).filter(_.nonEmpty),
      None,
      Option(query).filter(_.nonEmpty),
      Option(language).filter(_.nonEmpty),
      Option(cursor).filter(_.nonEmpty),
      Option(limit).map(_.intValue()))
}

/** Identity and permissions of the caller. Never returns a token. */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class CurrentUserResource extends NotebookApiSupport {

  @GET
  def me(): CurrentUserView = {
    val caller = principal
    CurrentUserView(
      user = caller.user,
      admin = caller.admin,
      permissions = UserPermissionsView(
        manageNotebooks = true,
        manageRuntimes = false,
        managePythonEnvironments = false))
  }
}

/** Sanitized health of the notebook subsystem. */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebookStatusResource extends NotebookApiSupport {

  @GET
  def status(): NotebookStatusView = notebooks.status()
}
