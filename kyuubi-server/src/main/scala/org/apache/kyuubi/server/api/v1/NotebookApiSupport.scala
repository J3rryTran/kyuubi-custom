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

import java.util.UUID
import javax.ws.rs.core.{MediaType, Response}
import javax.ws.rs.ext.{ExceptionMapper, Provider}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.server.api.ApiRequestContext
import org.apache.kyuubi.server.notebook.NotebookManager
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.service.NotebookPrincipal

/**
 * Shared plumbing for the notebook resources. The resources stay thin: they parse parameters,
 * resolve the caller and delegate.
 */
trait NotebookApiSupport extends ApiRequestContext {

  protected def notebooks: NotebookManager =
    fe.notebookManager.getOrElse(throw NotebookManager.disabled())

  /**
   * The caller, taken from the authenticated security context only. A request body or query
   * parameter can never influence it, which is what keeps `owner` from being forgeable.
   */
  protected def principal: NotebookPrincipal = {
    val user = fe.getRealUser()
    NotebookPrincipal(user, fe.isAdministrator(user))
  }

  protected def notebookView(notebook: Notebook, includeCells: Boolean): NotebookView = {
    val role = notebooks.documents.roleOf(notebook, principal)
    if (includeCells) {
      NotebookView(
        notebook,
        role,
        Some(notebooks.documents.listCells(notebook.id).map(NotebookCellView.apply)))
    } else {
      NotebookView(notebook, role)
    }
  }
}

/**
 * Renders [[NotebookException]] as the documented error envelope.
 *
 * The message reaches the client verbatim, so the mapper adds nothing of its own beyond the code
 * and a request id; the cause is logged and never serialized.
 */
@Provider
class NotebookExceptionMapper extends ExceptionMapper[NotebookException] with Logging {

  override def toResponse(exception: NotebookException): Response = {
    val requestId = UUID.randomUUID().toString
    if (Option(exception.getCause).isDefined) {
      warn(s"Notebook request $requestId failed with ${exception.code}", exception.getCause)
    } else {
      debug(s"Notebook request $requestId failed with ${exception.code}: ${exception.message}")
    }
    Response.status(NotebookException.httpStatus(exception.code))
      .`type`(MediaType.APPLICATION_JSON)
      .entity(NotebookErrorResponse(NotebookErrorBody(
        code = exception.code.toString,
        message = exception.message,
        requestId = requestId,
        retryable = exception.retryable,
        details = exception.details)))
      .build()
  }
}
