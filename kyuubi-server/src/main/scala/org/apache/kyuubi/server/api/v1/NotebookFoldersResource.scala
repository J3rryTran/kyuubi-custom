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

@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebookFoldersResource extends NotebookApiSupport {

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[NotebookFolderView]))),
    description = "Create a folder in the caller's notebook space.")
  @POST
  def create(request: CreateFolderRequest): NotebookFolderView =
    NotebookFolderView(notebooks.documents.createFolder(principal, request))

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "List the caller's folders, optionally restricted to one parent.")
  @GET
  def list(@QueryParam("parentId") parentId: String): Seq[NotebookFolderView] = {
    // An explicitly empty parentId means "the roots", which is different from not filtering.
    val specified = parentId != null
    notebooks.documents
      .listFolders(principal, Option(parentId).filter(_.nonEmpty), specified)
      .map(NotebookFolderView.apply)
  }

  @GET
  @Path("{folderId}")
  def get(@PathParam("folderId") folderId: String): NotebookFolderView =
    NotebookFolderView(notebooks.documents.getFolder(principal, folderId))

  @PATCH
  @Path("{folderId}")
  def update(
      @PathParam("folderId") folderId: String,
      request: UpdateFolderRequest): NotebookFolderView =
    NotebookFolderView(notebooks.documents.updateFolder(principal, folderId, request))

  @DELETE
  @Path("{folderId}")
  def delete(
      @PathParam("folderId") folderId: String,
      @QueryParam("version") version: java.lang.Long): Response = {
    notebooks.documents.deleteFolder(principal, folderId, Option(version).map(_.longValue()))
    Response.noContent().build()
  }
}
