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

import org.apache.kyuubi.server.notebook.api.NotebookErrorCode.NotebookErrorCode

/**
 * Stable error codes of the notebook API. Clients branch on the code, so a value is never
 * renamed or reused once released.
 */
object NotebookErrorCode extends Enumeration {
  type NotebookErrorCode = Value

  val NOTEBOOK_NOT_FOUND, FOLDER_NOT_FOUND, CELL_NOT_FOUND = Value
  val PATH_CONFLICT, VERSION_CONFLICT, ACCESS_DENIED, UNSUPPORTED_LANGUAGE = Value
  val NOTEBOOK_SESSION_NOT_FOUND, RUNTIME_SPEC_NOT_FOUND, RUNTIME_NOT_FOUND = Value
  val RUNTIME_LOST, RUNTIME_RESTART_REQUIRED = Value
  val EXECUTION_NOT_FOUND, EXECUTION_TIMEOUT, EXECUTION_CANCELED = Value
  val KYUUBI_SESSION_LOST, KYUUBI_OPERATION_LOST, KYUUBI_UNAVAILABLE = Value
  val PYTHON_RUNTIME_UNAVAILABLE, PYTHON_EXECUTION_FAILED, PYTHON_INTERRUPT_FAILED = Value
  val PYTHON_ENVIRONMENT_NOT_FOUND, PYTHON_ENVIRONMENT_BUSY = Value
  val PYTHON_PACKAGE_INVALID, PYTHON_PACKAGE_DENIED = Value
  val PYTHON_PACKAGE_INSTALL_FAILED, PYTHON_PACKAGE_UNINSTALL_FAILED = Value
  val PYTHON_ENVIRONMENT_QUOTA_EXCEEDED, PACKAGE_INDEX_UNAVAILABLE = Value
  val NO_TABULAR_RESULT, OUTPUT_EXPIRED, RESULT_EXPIRED, RATE_LIMITED = Value
  val INVALID_REQUEST, NOTEBOOK_DISABLED, INTERNAL_ERROR = Value
}

/**
 * The only exception type the notebook REST layer is expected to surface. `message` is returned
 * to the caller verbatim, so it must never carry a stack trace, secret, internal handle or
 * filesystem path; put anything of that nature in the cause instead, which is logged only.
 */
class NotebookException(
    val code: NotebookErrorCode,
    val message: String,
    val retryable: Boolean = false,
    val details: Map[String, String] = Map.empty,
    cause: Throwable = null)
  extends RuntimeException(message, cause)

object NotebookException {

  def notFound(code: NotebookErrorCode, message: String): NotebookException =
    new NotebookException(code, message)

  def accessDenied(message: String): NotebookException =
    new NotebookException(NotebookErrorCode.ACCESS_DENIED, message)

  def invalid(message: String, details: Map[String, String] = Map.empty): NotebookException =
    new NotebookException(NotebookErrorCode.INVALID_REQUEST, message, details = details)

  def versionConflict(message: String): NotebookException =
    new NotebookException(NotebookErrorCode.VERSION_CONFLICT, message)

  def pathConflict(message: String): NotebookException =
    new NotebookException(NotebookErrorCode.PATH_CONFLICT, message)

  /**
   * HTTP status for a code. Codes describing a lost or unavailable dependency map to 503 so a
   * client can distinguish "try again later" from "your request was wrong".
   */
  def httpStatus(code: NotebookErrorCode): Int = code match {
    case NotebookErrorCode.NOTEBOOK_NOT_FOUND | NotebookErrorCode.FOLDER_NOT_FOUND |
        NotebookErrorCode.CELL_NOT_FOUND | NotebookErrorCode.NOTEBOOK_SESSION_NOT_FOUND |
        NotebookErrorCode.RUNTIME_SPEC_NOT_FOUND | NotebookErrorCode.RUNTIME_NOT_FOUND |
        NotebookErrorCode.EXECUTION_NOT_FOUND | NotebookErrorCode.PYTHON_ENVIRONMENT_NOT_FOUND =>
      404
    case NotebookErrorCode.PATH_CONFLICT | NotebookErrorCode.VERSION_CONFLICT |
        NotebookErrorCode.PYTHON_ENVIRONMENT_BUSY | NotebookErrorCode.RUNTIME_RESTART_REQUIRED =>
      409
    case NotebookErrorCode.ACCESS_DENIED => 403
    case NotebookErrorCode.RATE_LIMITED => 429
    case NotebookErrorCode.PYTHON_ENVIRONMENT_QUOTA_EXCEEDED => 507
    case NotebookErrorCode.EXECUTION_TIMEOUT => 504
    case NotebookErrorCode.KYUUBI_UNAVAILABLE | NotebookErrorCode.KYUUBI_SESSION_LOST |
        NotebookErrorCode.KYUUBI_OPERATION_LOST | NotebookErrorCode.PYTHON_RUNTIME_UNAVAILABLE |
        NotebookErrorCode.PACKAGE_INDEX_UNAVAILABLE | NotebookErrorCode.RUNTIME_LOST |
        NotebookErrorCode.NOTEBOOK_DISABLED =>
      503
    case NotebookErrorCode.INTERNAL_ERROR => 500
    case _ => 400
  }
}

case class NotebookErrorBody(
    code: String,
    message: String,
    requestId: String,
    retryable: Boolean,
    details: Map[String, String])

case class NotebookErrorResponse(error: NotebookErrorBody)
