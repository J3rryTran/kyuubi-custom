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

package org.apache.kyuubi.server.notebook.runtime

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.operation.{FetchOrientation, OperationHandle, OperationState}
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.service.BackendService
import org.apache.kyuubi.session.SessionHandle
import org.apache.kyuubi.shaded.hive.service.rpc.thrift.{TColumnValue, TProtocolVersion, TRowSet}

/**
 * Runs SQL cells through the Kyuubi backend that hosts this notebook server.
 *
 * The adapter talks to the same [[BackendService]] the REST session and operation resources use,
 * which is what "use the existing Kyuubi primitives" means in practice: one Kyuubi session per
 * notebook runtime, one asynchronous operation per execution. Handles never leave this class.
 *
 * Cleanup is deliberately done with `cancelOperation`/`closeOperation`/`closeSession` rather than
 * the admin endpoints, so an ordinary notebook user needs no administrator rights.
 */
class KyuubiSqlRuntimeAdapter(
    backendService: () => BackendService,
    instanceUri: () => String,
    maxResultRows: Int)
  extends TabularNotebookRuntimeAdapter with Logging {

  import KyuubiSqlRuntimeAdapter._

  override val runtimeType: String = RUNTIME_TYPE

  override val runtimeSpec: RuntimeSpec = RuntimeSpec(
    id = SPEC_ID,
    displayName = "Kyuubi SQL",
    language = CellLanguage.SQL.toString,
    version = org.apache.kyuubi.KYUUBI_VERSION,
    enabled = true,
    configurableKeys = Seq("kyuubi.engine.share.level", "spark.sql.shuffle.partitions"),
    limits = Map("maxResultRows" -> maxResultRows.toString))

  override def startRuntime(
      runtime: NotebookRuntime,
      configuration: Map[String, String]): AdapterRuntime = {
    val handle = backendService().openSession(
      TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11,
      runtime.owner,
      "",
      LOCAL_IP,
      configuration ++ Map(KYUUBI_SESSION_TAG -> s"notebook-runtime-${runtime.id}"))
    AdapterRuntime(handle.identifier.toString, Some(instanceUri()))
  }

  override def getRuntimeStatus(runtime: NotebookRuntime): AdapterRuntimeStatus = {
    runtime.internalRuntimeHandle match {
      case None => AdapterRuntimeStatus(RuntimeState.STOPPED, None)
      case Some(handle) =>
        try {
          backendService().sessionManager.getSession(SessionHandle.fromUUID(handle))
          AdapterRuntimeStatus(RuntimeState.IDLE, None)
        } catch {
          // The session manager forgets a session that was closed or lost with its server, and
          // there is no way to tell those apart from here, so it is reported as LOST rather
          // than guessed to be healthy.
          case NonFatal(_) =>
            AdapterRuntimeStatus(RuntimeState.LOST, Some("the SQL session is gone"))
        }
    }
  }

  override def execute(
      runtime: NotebookRuntime,
      execution: CellExecution,
      configuration: Map[String, String]): AdapterExecution = {
    val sessionHandle = requireSession(runtime)
    val operationHandle =
      try {
        backendService().executeStatement(
          sessionHandle,
          execution.sourceSnapshot,
          configuration,
          // Always asynchronous: a synchronous call would tie the statement to the request
          // thread and make a browser refresh lose the work.
          runAsync = true,
          queryTimeout = 0L)
      } catch {
        case NonFatal(e) =>
          throw new NotebookException(
            NotebookErrorCode.KYUUBI_SESSION_LOST,
            "the SQL session is no longer usable; restart the notebook session",
            retryable = true,
            cause = e)
      }
    AdapterExecution(operationHandle.identifier.toString)
  }

  override def getExecutionStatus(execution: CellExecution): AdapterExecutionStatus = {
    val handle = execution.internalOperationHandle.getOrElse {
      return AdapterExecutionStatus(ExecutionState.QUEUED, None, None, None, None, false)
    }
    try {
      val status = backendService().getOperationStatus(OperationHandle(handle), None)
      AdapterExecutionStatus(
        state = normalize(status.state),
        startedAt = Option(status.start).filter(_ > 0),
        finishedAt = Option(status.completed).filter(_ > 0),
        errorCode = status.exception.map(_ => SQL_EXECUTION_FAILED),
        errorMessage = status.exception.map(e => safeMessage(e.getMessage)),
        hasResultSet = status.hasResultSet)
    } catch {
      case NonFatal(e) =>
        debug(s"Operation for execution ${execution.id} is no longer known", e)
        AdapterExecutionStatus(
          ExecutionState.LOST,
          None,
          None,
          Some(NotebookErrorCode.KYUUBI_OPERATION_LOST.toString),
          Some("the SQL operation is no longer known to the server"),
          hasResultSet = false)
    }
  }

  override def interruptExecution(execution: CellExecution): Unit = {
    execution.internalOperationHandle.foreach { handle =>
      try backendService().cancelOperation(OperationHandle(handle))
      catch { case NonFatal(e) => warn(s"Failed to cancel execution ${execution.id}", e) }
    }
  }

  override def closeExecution(execution: CellExecution): Unit = {
    execution.internalOperationHandle.foreach { handle =>
      try backendService().closeOperation(OperationHandle(handle))
      catch { case NonFatal(e) => debug(s"Failed to close execution ${execution.id}", e) }
    }
  }

  override def restartRuntime(runtime: NotebookRuntime): AdapterRuntime = {
    stopRuntime(runtime)
    startRuntime(runtime, Map.empty)
  }

  override def stopRuntime(runtime: NotebookRuntime): Unit = {
    runtime.internalRuntimeHandle.foreach { handle =>
      try backendService().closeSession(SessionHandle.fromUUID(handle))
      catch {
        case NonFatal(e) => debug(s"Failed to close SQL session of runtime ${runtime.id}", e)
      }
    }
  }

  /**
   * Reads the operation log from the start and slices it, because Kyuubi's log cursor is
   * stateful and would otherwise make two clients steal each other's lines.
   */
  override def fetchLogs(execution: CellExecution, offset: Long, maxLines: Int): AdapterLogPage = {
    val handle = execution.internalOperationHandle.getOrElse {
      return AdapterLogPage(Seq.empty, offset, hasMore = false)
    }
    try {
      val response = backendService().sessionManager.operationManager.getOperationLogRowSet(
        OperationHandle(handle),
        FetchOrientation.FETCH_FIRST,
        (offset + maxLines).toInt.max(maxLines))
      val all = Option(response.getResults).map(stringColumn).getOrElse(Seq.empty)
      val page = all.drop(offset.toInt).take(maxLines)
      AdapterLogPage(page, offset + page.size, hasMore = all.size > offset + page.size)
    } catch {
      case NonFatal(e) =>
        debug(s"Log for execution ${execution.id} is unavailable", e)
        AdapterLogPage(Seq.empty, offset, hasMore = false)
    }
  }

  override def fetchSchema(execution: CellExecution): ExecutionSchema = {
    val handle = execution.internalOperationHandle.getOrElse {
      throw new NotebookException(
        NotebookErrorCode.NO_TABULAR_RESULT,
        "the execution produced no result set")
    }
    try {
      val columns = backendService().getResultSetMetadata(OperationHandle(handle))
        .getSchema.getColumns.asScala
      ExecutionSchema(columns.map { column =>
        val primitive = column.getTypeDesc.getTypes.get(0).getPrimitiveEntry
        ColumnSchema(
          column.getColumnName,
          primitive.getType.toString,
          column.getPosition,
          Option(column.getComment).filter(_.nonEmpty))
      })
    } catch {
      case e: NotebookException => throw e
      case NonFatal(e) =>
        throw new NotebookException(
          NotebookErrorCode.RESULT_EXPIRED,
          "the result of this execution is no longer available",
          cause = e)
    }
  }

  override def fetchResults(
      execution: CellExecution,
      offset: Long,
      maxRows: Int): AdapterResultPage = {
    val handle = execution.internalOperationHandle.getOrElse {
      throw new NotebookException(
        NotebookErrorCode.NO_TABULAR_RESULT,
        "the execution produced no result set")
    }
    // Cursor policy lives in the execution service, which applies it to every runtime; here the
    // read is simply forward, restarting the Kyuubi cursor only for the first page.
    val capped = math.min(maxRows, maxResultRows)
    try {
      val orientation =
        if (offset == 0) FetchOrientation.FETCH_FIRST else FetchOrientation.FETCH_NEXT
      val response = backendService().fetchResults(
        OperationHandle(handle),
        orientation,
        capped,
        fetchLog = false)
      val rows = toRows(response.getResults)
      AdapterResultPage(rows, offset + rows.size, rows.size == capped)
    } catch {
      case e: NotebookException => throw e
      case NonFatal(e) =>
        throw new NotebookException(
          NotebookErrorCode.RESULT_EXPIRED,
          "the result of this execution is no longer available",
          cause = e)
    }
  }

  private def requireSession(runtime: NotebookRuntime): SessionHandle = {
    val handle = runtime.internalRuntimeHandle.getOrElse {
      throw new NotebookException(
        NotebookErrorCode.RUNTIME_LOST,
        "the runtime has no live SQL session; restart it",
        retryable = true)
    }
    SessionHandle.fromUUID(handle)
  }

  private def stringColumn(rowSet: TRowSet): Seq[String] = {
    if (rowSet.getColumns == null || rowSet.getColumns.isEmpty) {
      Seq.empty
    } else {
      rowSet.getColumns.get(0).getStringVal.getValues.asScala
    }
  }

  /**
   * Values are rendered as strings, and a SQL NULL stays null rather than becoming the text
   * "null", which a client could not tell from a genuine value.
   */
  private def toRows(rowSet: TRowSet): Seq[Seq[String]] = {
    if (rowSet == null || rowSet.getRows == null) {
      Seq.empty
    } else {
      rowSet.getRows.asScala.map { row =>
        row.getColVals.asScala.map(renderValue)
      }
    }
  }

  private def renderValue(value: TColumnValue): String = value.getSetField match {
    case TColumnValue._Fields.STRING_VAL =>
      if (value.getStringVal.isSetValue) value.getStringVal.getValue else null
    case TColumnValue._Fields.BOOL_VAL =>
      if (value.getBoolVal.isSetValue) value.getBoolVal.isValue.toString else null
    case TColumnValue._Fields.BYTE_VAL =>
      if (value.getByteVal.isSetValue) value.getByteVal.getValue.toString else null
    case TColumnValue._Fields.I16_VAL =>
      if (value.getI16Val.isSetValue) value.getI16Val.getValue.toString else null
    case TColumnValue._Fields.I32_VAL =>
      if (value.getI32Val.isSetValue) value.getI32Val.getValue.toString else null
    case TColumnValue._Fields.I64_VAL =>
      if (value.getI64Val.isSetValue) value.getI64Val.getValue.toString else null
    case TColumnValue._Fields.DOUBLE_VAL =>
      if (value.getDoubleVal.isSetValue) value.getDoubleVal.getValue.toString else null
    case _ => null
  }

  /** Normalizes Kyuubi operation states onto the notebook execution lifecycle. */
  private def normalize(state: OperationState.OperationState): ExecutionState.ExecutionState =
    state match {
      case OperationState.INITIALIZED | OperationState.PENDING => ExecutionState.QUEUED
      case OperationState.RUNNING | OperationState.COMPILED => ExecutionState.RUNNING
      case OperationState.FINISHED => ExecutionState.SUCCEEDED
      case OperationState.CANCELED => ExecutionState.CANCELED
      case OperationState.CLOSED => ExecutionState.CLOSED
      case OperationState.TIMEOUT | OperationState.ERROR => ExecutionState.FAILED
      // An unknown state is never optimistically reported as success.
      case OperationState.UNKNOWN => ExecutionState.LOST
    }

  /** Engine errors can be long and carry stack traces; only the first line is safe to surface. */
  private def safeMessage(message: String): String =
    Option(message).map(_.split("\n").head.take(1024)).getOrElse("the statement failed")
}

object KyuubiSqlRuntimeAdapter {
  val SPEC_ID = "kyuubi-sql"
  val RUNTIME_TYPE = "SQL"

  /** Error code carried on a failed SQL execution; the message itself comes from the engine. */
  val SQL_EXECUTION_FAILED = "SQL_EXECUTION_FAILED"

  private val LOCAL_IP = "127.0.0.1"
  private val KYUUBI_SESSION_TAG = "kyuubi.session.name"
}
