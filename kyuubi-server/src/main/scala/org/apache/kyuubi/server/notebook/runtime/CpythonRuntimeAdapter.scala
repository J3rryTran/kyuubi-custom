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

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.python.PythonEnvironmentBuilder

/** Resolves the interpreter and scratch directory a runtime should use. */
trait PythonRuntimeContext {

  /** Interpreter of the caller's active environment revision, if they have one. */
  def interpreterFor(owner: String): Option[Path]

  /** Environment revision the runtime is bound to, recorded so a later activation is visible. */
  def activeRevisionIdFor(owner: String): Option[String]
}

/**
 * A persistent CPython process per notebook runtime.
 *
 * One process serves every cell of a runtime generation, which is what makes a name bound in one
 * cell visible in the next - the Jupyter model, and the reason [[NotebookRuntimeService]] reuses
 * a runtime instead of starting one per execution.
 *
 * The process is confined by the operating system, not by anything inside the interpreter: a cell
 * can import `os`, so a Python-level guard would be advisory. Limits are passed to the driver,
 * which applies them with `setrlimit` before any user code runs.
 */
class CpythonRuntimeAdapter(
    conf: KyuubiConf,
    context: PythonRuntimeContext,
    builder: PythonEnvironmentBuilder,
    instanceUri: () => String) extends NotebookRuntimeAdapter with Logging {

  import CpythonRuntimeAdapter._

  private val fallbackPython = conf.get(PYTHON_EXECUTABLE)
  private val executionTimeout = conf.get(PYTHON_EXECUTION_TIMEOUT_SECONDS)
  private val mapper = new ObjectMapper()

  private val processes = new ConcurrentHashMap[String, KernelProcess]()

  /** Result of a finished cell, kept until the execution service reads it. */
  private val results = new ConcurrentHashMap[String, ExecutionOutcome]()

  override val runtimeType: String = RUNTIME_TYPE

  override def runtimeSpec: RuntimeSpec = RuntimeSpec(
    id = SPEC_ID,
    displayName = "CPython 3",
    language = CellLanguage.PYTHON.toString,
    version = builder.pythonVersion.getOrElse("unknown"),
    // Reported as disabled rather than failing at first use, so the UI can grey the Run button
    // instead of letting a user submit work that cannot start.
    enabled = conf.get(PYTHON_ENABLED) && builder.available,
    configurableKeys = Seq.empty,
    limits = Map(
      "cpuSeconds" -> conf.get(PYTHON_LIMIT_CPU_SECONDS).toString,
      "memoryMb" -> conf.get(PYTHON_LIMIT_MEMORY_MB).toString,
      "processes" -> conf.get(PYTHON_LIMIT_PROCESSES).toString,
      "executionTimeoutSeconds" -> executionTimeout.toString))

  override def startRuntime(
      runtime: NotebookRuntime,
      configuration: Map[String, String]): AdapterRuntime = {
    val workDir = Files.createTempDirectory(s"kyuubi-notebook-${runtime.id}-")
    val kernelScript = extractKernel(workDir)
    val interpreter = context.interpreterFor(runtime.owner)
      .map(_.toString)
      .getOrElse(fallbackPython)

    val command = Seq(interpreter, kernelScript.toString)
    val processBuilder = new ProcessBuilder(command.asJava)
    processBuilder.directory(workDir.toFile)
    val environment = processBuilder.environment()
    // The kernel inherits nothing from the server beyond what it needs. A credential in the
    // server's environment must not be readable from a user's cell.
    environment.clear()
    environment.put("PATH", sys.env.getOrElse("PATH", "/usr/local/bin:/usr/bin:/bin"))
    environment.put("HOME", workDir.toString)
    environment.put("TMPDIR", workDir.toString)
    environment.put("LANG", "C.UTF-8")
    environment.put("PYTHONDONTWRITEBYTECODE", "1")
    // `%pip install` inside a cell lands here rather than in the managed environment, which is
    // what keeps an activated revision immutable while still letting an ad-hoc install work.
    environment.put("PYTHONUSERBASE", workDir.resolve("user-site").toString)
    environment.put("PIP_USER", "1")
    conf.get(PYTHON_PACKAGE_INDEX_URL).foreach(environment.put("PIP_INDEX_URL", _))
    environment.put("KYUUBI_PY_LIMIT_CPU_SECONDS", conf.get(PYTHON_LIMIT_CPU_SECONDS).toString)
    environment.put("KYUUBI_PY_LIMIT_MEMORY_MB", conf.get(PYTHON_LIMIT_MEMORY_MB).toString)
    environment.put("KYUUBI_PY_LIMIT_PROCESSES", conf.get(PYTHON_LIMIT_PROCESSES).toString)

    val process = processBuilder.start()
    val kernel = new KernelProcess(process, workDir, mapper)
    if (!kernel.awaitReady(READY_TIMEOUT_SECONDS)) {
      kernel.destroy(builder)
      throw new NotebookException(
        NotebookErrorCode.PYTHON_RUNTIME_UNAVAILABLE,
        "the Python runtime did not start",
        retryable = true)
    }
    processes.put(runtime.id, kernel)
    AdapterRuntime(kernel.handle, Some(instanceUri()))
  }

  override def getRuntimeStatus(runtime: NotebookRuntime): AdapterRuntimeStatus = {
    Option(processes.get(runtime.id)) match {
      case Some(kernel) if kernel.isAlive => AdapterRuntimeStatus(RuntimeState.IDLE, None)
      case Some(_) =>
        AdapterRuntimeStatus(RuntimeState.LOST, Some("the Python process exited"))
      case None =>
        // Nothing in this JVM owns it; after a restart that is the truth, not a healthy idle.
        AdapterRuntimeStatus(RuntimeState.LOST, Some("the Python process is not held here"))
    }
  }

  override def execute(
      runtime: NotebookRuntime,
      execution: CellExecution,
      configuration: Map[String, String]): AdapterExecution = {
    val kernel = Option(processes.get(runtime.id)).filter(_.isAlive).getOrElse {
      throw new NotebookException(
        NotebookErrorCode.RUNTIME_LOST,
        "the Python runtime is gone; restart it",
        retryable = true)
    }
    results.put(execution.id, ExecutionOutcome.running())
    kernel.submit(execution.id, execution.sourceSnapshot, executionTimeout) { outcome =>
      results.put(execution.id, outcome)
    }
    AdapterExecution(execution.id)
  }

  override def getExecutionStatus(execution: CellExecution): AdapterExecutionStatus = {
    Option(results.get(execution.id)) match {
      case None =>
        AdapterExecutionStatus(
          ExecutionState.LOST,
          None,
          None,
          Some(NotebookErrorCode.RUNTIME_LOST.toString),
          Some("the execution is not tracked by this server"),
          hasResultSet = false)
      case Some(outcome) => outcome.toStatus
    }
  }

  override def interruptExecution(execution: CellExecution): Unit = {
    processes.values().asScala.find(_.owns(execution.id)).foreach(_.interrupt())
  }

  override def closeExecution(execution: CellExecution): Unit = {
    results.remove(execution.id)
  }

  override def restartRuntime(runtime: NotebookRuntime): AdapterRuntime = {
    stopRuntime(runtime)
    startRuntime(runtime, Map.empty)
  }

  override def stopRuntime(runtime: NotebookRuntime): Unit = {
    Option(processes.remove(runtime.id)).foreach(_.destroy(builder))
  }

  override def fetchLogs(
      execution: CellExecution,
      offset: Long,
      maxLines: Int): AdapterLogPage = {
    val lines = Option(results.get(execution.id)).map(_.logLines).getOrElse(Seq.empty)
    val page = lines.drop(offset.toInt).take(maxLines)
    AdapterLogPage(page, offset + page.size, lines.size > offset + page.size)
  }

  /**
   * Streams, the expression value, rich representations and the traceback, in that order, with a
   * sequence that is stable for as long as this server holds the outcome. Outputs do not survive
   * a restart, which is consistent with the execution itself becoming LOST.
   */
  override def fetchOutputs(
      execution: CellExecution,
      afterSequence: Long,
      limit: Int): Seq[AdapterOutput] = {
    Option(results.get(execution.id)) match {
      case None => Seq.empty
      case Some(outcome) =>
        val streams = outcome.logLines.map(line =>
          ("STREAM", Some("stdout"), "text/plain", line))
        val value = outcome.result.map(text => ("EXECUTE_RESULT", None, "text/plain", text))
        val rich = outcome.rich.map { case (mime, data) => ("DISPLAY_DATA", None, mime, data) }
        val error = outcome.errorMessage.map(text => ("ERROR", None, "text/plain", text))
        val all = streams ++ value.toSeq ++ rich ++ error.toSeq
        all.zipWithIndex
          .map { case ((kind, stream, mime, data), index) =>
            (index + 1L, kind, stream, mime, data)
          }
          .filter(_._1 > afterSequence)
          .take(limit)
          .map { case (sequence, kind, stream, mime, data) =>
            AdapterOutput(sequence, kind, stream, mime, data)
          }
    }
  }

  /** The environment revision a runtime started against, to detect a later activation. */
  def boundRevision(owner: String): Option[String] = context.activeRevisionIdFor(owner)

  private def extractKernel(workDir: Path): Path = {
    val target = workDir.resolve(KERNEL_SCRIPT)
    val stream = Option(Utils.classLoader.getResourceAsStream(s"python/$KERNEL_SCRIPT")).getOrElse {
      throw new NotebookException(
        NotebookErrorCode.PYTHON_RUNTIME_UNAVAILABLE,
        "the notebook kernel script is missing from the server jar")
    }
    try Files.copy(stream, target)
    finally stream.close()
    target
  }
}

private object Utils {
  def classLoader: ClassLoader = org.apache.kyuubi.Utils.getContextOrKyuubiClassLoader
}

object CpythonRuntimeAdapter {
  val SPEC_ID = "cpython3"
  val RUNTIME_TYPE = "PYTHON"

  private val KERNEL_SCRIPT = "kyuubi_notebook_kernel.py"
  private val READY_TIMEOUT_SECONDS = 60

  /** What the driver reported for one cell, in the shape the adapter contract expects. */
  case class ExecutionOutcome(
      state: ExecutionState.Value,
      startedAt: Option[Long],
      finishedAt: Option[Long],
      errorMessage: Option[String],
      logLines: Seq[String],
      rich: Seq[(String, String)],
      result: Option[String]) {

    def toStatus: AdapterExecutionStatus = AdapterExecutionStatus(
      state,
      startedAt,
      finishedAt,
      errorMessage.map(_ => "PYTHON_EXECUTION_FAILED"),
      errorMessage,
      hasResultSet = false)
  }

  object ExecutionOutcome {
    def running(): ExecutionOutcome = ExecutionOutcome(
      ExecutionState.RUNNING,
      Some(System.currentTimeMillis()),
      None,
      None,
      Seq.empty,
      Seq.empty,
      None)
  }

  /**
   * Owns one interpreter process and serializes the cells sent to it.
   *
   * A single kernel runs one cell at a time by construction: the driver reads one request, runs
   * it, and writes one response, which is also what makes an interrupt unambiguous.
   */
  class KernelProcess(process: Process, val workDir: Path, mapper: ObjectMapper) extends Logging {

    val handle: String = java.util.UUID.randomUUID().toString

    private val writer = new BufferedWriter(
      new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8))
    private val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    @volatile private var current: Option[String] = None
    @volatile private var kernelPid: Option[Long] = None

    def isAlive: Boolean = process.isAlive

    def owns(executionId: String): Boolean = current.contains(executionId)

    def awaitReady(timeoutSeconds: Int): Boolean = {
      val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
      while (System.currentTimeMillis() < deadline) {
        if (!process.isAlive) return false
        val line = reader.readLine()
        if (line != null) {
          val node = mapper.readTree(line)
          if (node.path("type").asText() == "ready") {
            kernelPid = Option(node.path("pid").asLong(0L)).filter(_ > 0)
            return true
          }
        }
      }
      false
    }

    /** Runs a cell on the kernel's own thread and hands the outcome back when it finishes. */
    def submit(executionId: String, source: String, timeoutSeconds: Int)(
        onComplete: ExecutionOutcome => Unit): Unit = {
      current = Some(executionId)
      val started = System.currentTimeMillis()
      val thread = new Thread(
        new Runnable {
          override def run(): Unit = {
            try {
              val request = mapper.createObjectNode()
              request.put("type", "execute")
              request.put("id", executionId)
              request.put("source", source)
              writer.write(request.toString)
              writer.newLine()
              writer.flush()

              val line = reader.readLine()
              if (line == null) {
                onComplete(lost(started, "the Python process exited during the cell"))
              } else {
                onComplete(parse(mapper.readTree(line), started))
              }
            } catch {
              case NonFatal(e) =>
                onComplete(lost(started, "the Python runtime stopped responding"))
                warn(s"Python execution $executionId failed", e)
            } finally {
              current = None
            }
          }
        },
        s"notebook-python-$executionId")
      thread.setDaemon(true)
      thread.start()
    }

    private def parse(node: JsonNode, started: Long): ExecutionOutcome = {
      val status = node.path("status").asText("ok")
      val outputs = node.path("outputs")
      val lines =
        if (outputs.isArray) {
          outputs.asScala.flatMap { entry =>
            entry.path("text").asText("").split("\n", -1).filter(_.nonEmpty)
          }.toSeq
        } else {
          Seq.empty
        }
      val rich = node.path("rich")
      val richOutputs =
        if (rich.isArray) {
          rich.asScala.map(entry =>
            entry.path("mimeType").asText("") -> entry.path("data").asText("")).toSeq
        } else {
          Seq.empty
        }
      val state = status match {
        case "ok" => ExecutionState.SUCCEEDED
        case "interrupted" => ExecutionState.CANCELED
        case _ => ExecutionState.FAILED
      }
      ExecutionOutcome(
        state = state,
        startedAt = Some(started),
        finishedAt = Some(System.currentTimeMillis()),
        errorMessage = Option(node.path("error").asText(null)).filter(_.nonEmpty),
        logLines = lines,
        rich = richOutputs,
        result = Option(node.path("result").asText(null)).filter(_.nonEmpty))
    }

    private def lost(started: Long, message: String): ExecutionOutcome = ExecutionOutcome(
      ExecutionState.LOST,
      Some(started),
      Some(System.currentTimeMillis()),
      Some(message),
      Seq.empty,
      Seq.empty,
      None)

    /**
     * SIGINT raises inside the running cell, which leaves the interpreter and its names alive.
     * The pid comes from the kernel's ready message rather than `Process.pid()`, which does not
     * exist in the Java 8 API this module compiles against.
     */
    def interrupt(): Unit = {
      kernelPid match {
        case None => warn("The Python runtime did not report a pid; cannot interrupt")
        case Some(pid) =>
          try {
            new ProcessBuilder("kill", "-INT", pid.toString).start()
              .waitFor(5, TimeUnit.SECONDS)
          } catch {
            case NonFatal(e) => warn("Failed to interrupt the Python runtime", e)
          }
      }
    }

    def destroy(builder: PythonEnvironmentBuilder): Unit = {
      try {
        val shutdown = mapper.createObjectNode()
        shutdown.put("type", "shutdown")
        writer.write(shutdown.toString)
        writer.newLine()
        writer.flush()
      } catch {
        case NonFatal(_) => // the process may already be gone
      }
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        // destroyForcibly kills the process group's leader; children are reaped with it because
        // the kernel is started in its own working directory and holds no detached jobs.
        process.destroyForcibly()
      }
      try builder.deleteRecursively(workDir)
      catch { case NonFatal(e) => warn(s"Failed to clean up $workDir", e) }
    }
  }
}
