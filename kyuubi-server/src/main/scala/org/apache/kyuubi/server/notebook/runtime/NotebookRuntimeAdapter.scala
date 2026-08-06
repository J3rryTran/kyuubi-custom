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

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.api.ExecutionState.ExecutionState
import org.apache.kyuubi.server.notebook.api.RuntimeState.RuntimeState

/** A runtime the adapter has started. The handle is opaque to everything above the adapter. */
case class AdapterRuntime(handle: String, location: Option[String])

case class AdapterRuntimeStatus(state: RuntimeState, failureMessage: Option[String])

/** A submitted execution. `handle` identifies the work inside the adapter's own world. */
case class AdapterExecution(handle: String)

case class AdapterExecutionStatus(
    state: ExecutionState,
    startedAt: Option[Long],
    finishedAt: Option[Long],
    errorCode: Option[String],
    errorMessage: Option[String],
    hasResultSet: Boolean)

case class AdapterLogPage(lines: Seq[String], nextOffset: Long, hasMore: Boolean)

case class AdapterResultPage(rows: Seq[Seq[String]], nextOffset: Long, hasMore: Boolean)

/**
 * What a language runtime must be able to do for the notebook services.
 *
 * The services own persistence, authorization and state transitions; an adapter owns only the
 * conversation with its backend. Keeping the split here is what allows the SQL and CPython
 * runtimes to coexist without the REST layer knowing which is which.
 */
trait NotebookRuntimeAdapter {

  def runtimeType: String

  def runtimeSpec: RuntimeSpec

  def startRuntime(runtime: NotebookRuntime, configuration: Map[String, String]): AdapterRuntime

  def getRuntimeStatus(runtime: NotebookRuntime): AdapterRuntimeStatus

  def execute(
      runtime: NotebookRuntime,
      execution: CellExecution,
      configuration: Map[String, String]): AdapterExecution

  def getExecutionStatus(execution: CellExecution): AdapterExecutionStatus

  def interruptExecution(execution: CellExecution): Unit

  /** Releases whatever the adapter holds for this execution; must be idempotent. */
  def closeExecution(execution: CellExecution): Unit

  def restartRuntime(runtime: NotebookRuntime): AdapterRuntime

  def stopRuntime(runtime: NotebookRuntime): Unit

  def fetchLogs(execution: CellExecution, offset: Long, maxLines: Int): AdapterLogPage

  /**
   * Rich outputs of a finished execution. A tabular runtime returns nothing here: its result is
   * served by the schema and results endpoints instead.
   */
  def fetchOutputs(execution: CellExecution, afterSequence: Long, limit: Int): Seq[AdapterOutput] =
    Seq.empty
}

/** One rendered output of a cell, already reduced to a MIME type and its payload. */
case class AdapterOutput(
    sequence: Long,
    outputType: String,
    stream: Option[String],
    mimeType: String,
    data: String)

/** Adds the result surface that only a tabular runtime such as SQL can answer. */
trait TabularNotebookRuntimeAdapter extends NotebookRuntimeAdapter {

  def fetchSchema(execution: CellExecution): ExecutionSchema

  def fetchResults(execution: CellExecution, offset: Long, maxRows: Int): AdapterResultPage
}

/**
 * Resolves a runtime spec or language to its adapter. Registration happens once at startup, so a
 * lookup miss is a configuration error rather than a transient condition.
 */
class RuntimeAdapterRegistry(adapters: Seq[NotebookRuntimeAdapter]) {

  private val bySpecId: Map[String, NotebookRuntimeAdapter] =
    adapters.map(adapter => adapter.runtimeSpec.id -> adapter).toMap

  def specs: Seq[RuntimeSpec] = adapters.map(_.runtimeSpec)

  def get(runtimeSpecId: String): NotebookRuntimeAdapter =
    bySpecId.getOrElse(
      runtimeSpecId,
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_SPEC_NOT_FOUND,
        s"runtime spec $runtimeSpecId is not available"))

  def spec(runtimeSpecId: String): RuntimeSpec = get(runtimeSpecId).runtimeSpec

  /** The default spec for a language, used when a submission does not name a runtime. */
  def defaultSpecFor(language: CellLanguage.Value): RuntimeSpec =
    adapters.map(_.runtimeSpec).find(_.language == language.toString).getOrElse {
      throw new NotebookException(
        NotebookErrorCode.UNSUPPORTED_LANGUAGE,
        s"no runtime is available for $language")
    }

  def tabular(runtimeSpecId: String): Option[TabularNotebookRuntimeAdapter] =
    get(runtimeSpecId) match {
      case adapter: TabularNotebookRuntimeAdapter => Some(adapter)
      case _ => None
    }
}
