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

package org.apache.kyuubi.server.notebook.python

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api.{InstalledPackage, NotebookErrorCode, NotebookException}

case class BuildResult(location: Path, resolvedPackages: Seq[String], log: String)

/**
 * Creates and populates the virtualenv behind an environment revision.
 *
 * Every external command is run as an argument vector through [[ProcessBuilder]] and never
 * through a shell, and every requirement has already been through [[RequirementSpec]]. Those two
 * facts together are what stop a package name from becoming a pip option, a URL or a command.
 */
class PythonEnvironmentBuilder(conf: KyuubiConf) extends Logging {

  private val python = conf.get(PYTHON_EXECUTABLE)
  private val indexUrl = conf.get(PYTHON_PACKAGE_INDEX_URL)
  private val constraintsFile = conf.get(PYTHON_PACKAGE_CONSTRAINTS_FILE)
  private val installTimeout = conf.get(PYTHON_PACKAGE_INSTALL_TIMEOUT_SECONDS)
  private val maxSizeMb = conf.get(PYTHON_ENVIRONMENT_MAX_SIZE_MB)
  private val systemSitePackages = conf.get(PYTHON_VENV_SYSTEM_SITE_PACKAGES)

  /** True when the host can actually build environments, which the runtime spec reports. */
  def available: Boolean = {
    try {
      val result = run(Seq(python, "-c", "import venv, ensurepip"), None, 30)
      result.exitCode == 0
    } catch {
      case NonFatal(e) =>
        debug(s"Python is not usable at $python", e)
        false
    }
  }

  def pythonVersion: Option[String] = {
    try {
      val result = run(
        Seq(python, "-c", "import sys; print('.'.join(map(str, sys.version_info[:3])))"),
        None,
        30)
      if (result.exitCode == 0) Some(result.output.trim) else None
    } catch {
      case NonFatal(_) => None
    }
  }

  /**
   * Builds a revision from scratch out of a requirement list.
   *
   * A revision is rebuilt rather than copied from its predecessor: copying a virtualenv relies on
   * paths inside it staying valid, while replaying a pinned list is reproducible and is what makes
   * `resolvedPackages` meaningful.
   */
  def build(location: Path, requirements: Seq[RequirementSpec]): BuildResult = {
    val log = new StringBuilder
    Files.createDirectories(location.getParent)
    deleteRecursively(location)

    append(log, s"Creating virtualenv at ${location.getFileName}")
    // Without --system-site-packages a virtualenv is fully isolated, and packages baked into
    // the image would be invisible to every environment - the reason for baking them at all.
    val venvArgs =
      if (systemSitePackages) Seq("--system-site-packages", location.toString)
      else Seq(location.toString)
    val venv = run(Seq(python, "-m", "venv") ++ venvArgs, None, installTimeout)
    append(log, venv.output)
    if (venv.exitCode != 0) {
      deleteRecursively(location)
      throw failure("the virtualenv could not be created", log.toString)
    }

    if (requirements.nonEmpty) {
      append(log, s"Installing ${requirements.size} requirement(s)")
      val install =
        run(pipCommand(location, "install", requirements.map(_.render)), None, installTimeout)
      append(log, install.output)
      if (install.exitCode != 0) {
        deleteRecursively(location)
        throw new NotebookException(
          NotebookErrorCode.PYTHON_PACKAGE_INSTALL_FAILED,
          "the packages could not be installed",
          details = Map("log" -> tail(log.toString)))
      }
    }

    // The interpreter must actually come up in the new environment before it can be activated;
    // a revision that cannot start would otherwise break every runtime that binds to it.
    val check =
      run(Seq(interpreterOf(location).toString, "-c", "import sys; print(sys.version)"), None, 60)
    append(log, check.output)
    if (check.exitCode != 0) {
      deleteRecursively(location)
      throw failure("the built environment does not start", log.toString)
    }

    val sizeMb = directorySizeBytes(location) / (1024 * 1024)
    if (sizeMb > maxSizeMb) {
      deleteRecursively(location)
      throw new NotebookException(
        NotebookErrorCode.PYTHON_ENVIRONMENT_QUOTA_EXCEEDED,
        s"the environment would use ${sizeMb}MiB, over the ${maxSizeMb}MiB limit")
    }

    BuildResult(location, freeze(location), log.toString)
  }

  /** Pinned `name==version` lines, which is what a later rebuild replays. */
  def freeze(location: Path): Seq[String] = {
    val result = run(pipCommand(location, "freeze", Seq.empty), None, 120)
    if (result.exitCode != 0) {
      Seq.empty
    } else {
      result.output.split("\n").map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#") && line.contains("=="))
        .toSeq
    }
  }

  def installedPackages(location: Path): Seq[InstalledPackage] =
    freeze(location).flatMap { line =>
      line.split("==", 2) match {
        case Array(name, version) => Some(InstalledPackage(name, version))
        case _ => None
      }
    }

  def interpreterOf(location: Path): Path = location.resolve("bin").resolve("python")

  /**
   * Distributions the image provides. A user cannot remove one of these from their environment,
   * so the service refuses the request rather than reporting a removal that leaves the package
   * importable.
   */
  lazy val baseInstalledPackages: Seq[InstalledPackage] = {
    if (!systemSitePackages) {
      Seq.empty
    } else {
      try {
        val result =
          run(Seq(python, "-m", "pip", "freeze", "--disable-pip-version-check"), None, 60)
        if (result.exitCode != 0) {
          Seq.empty
        } else {
          result.output.split("\n").map(_.trim)
            .filter(line => line.nonEmpty && line.contains("=="))
            .flatMap { line =>
              line.split("==", 2) match {
                case Array(name, version) => Some(InstalledPackage(name, version, true))
                case _ => None
              }
            }.toSeq
        }
      } catch {
        case NonFatal(e) =>
          debug("Could not read the base package list", e)
          Seq.empty
      }
    }
  }

  lazy val basePackages: Set[String] =
    baseInstalledPackages.map(entry => RequirementSpec.normalize(entry.name)).toSet

  def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val stream = Files.walk(path)
      try {
        stream.sorted(java.util.Comparator.reverseOrder[Path]())
          .iterator().asScala
          .foreach(entry => Files.deleteIfExists(entry))
      } finally {
        stream.close()
      }
    }
  }

  def directorySizeBytes(path: Path): Long = {
    if (!Files.exists(path)) {
      0L
    } else {
      val stream = Files.walk(path)
      try {
        stream.filter(Files.isRegularFile(_)).mapToLong { entry =>
          try Files.size(entry)
          catch { case NonFatal(_) => 0L }
        }.sum()
      } finally {
        stream.close()
      }
    }
  }

  /**
   * pip invocation. The index URL and constraints come from server configuration only; a caller
   * supplies requirement names and nothing else, so there is no path by which a request can
   * redirect the install to another index.
   */
  private def pipCommand(location: Path, action: String, arguments: Seq[String]): Seq[String] = {
    val base = Seq(
      interpreterOf(location).toString,
      "-m",
      "pip",
      "--no-input",
      "--disable-pip-version-check")
    val actionArgs = action match {
      case "install" =>
        val index = indexUrl.map(url => Seq("--index-url", url)).getOrElse(Seq.empty)
        val constraints =
          constraintsFile.map(file => Seq("--constraint", file)).getOrElse(Seq.empty)
        Seq("install") ++ index ++ constraints ++ arguments
      // --local excludes what comes from the system interpreter. Without it a rebuild would
      // try to reinstall every baked package into the revision, undoing the saving.
      case "freeze" => Seq("freeze", "--local")
      case other => Seq(other) ++ arguments
    }
    base ++ actionArgs
  }

  private case class CommandResult(exitCode: Int, output: String)

  private def run(
      command: Seq[String],
      workDir: Option[File],
      timeoutSeconds: Int): CommandResult = {
    val builder = new ProcessBuilder(command.asJava)
    builder.redirectErrorStream(true)
    workDir.foreach(builder.directory)
    // The build inherits none of the server's environment beyond what it needs, so a credential
    // in the server process cannot leak into a package's setup script.
    val environment = builder.environment()
    environment.clear()
    environment.put("PATH", sys.env.getOrElse("PATH", "/usr/local/bin:/usr/bin:/bin"))
    environment.put("HOME", sys.props.getOrElse("java.io.tmpdir", "/tmp"))
    environment.put("LANG", "C.UTF-8")
    environment.put("PIP_DISABLE_PIP_VERSION_CHECK", "1")

    val process = builder.start()
    process.getOutputStream.close()
    val reader = new java.io.BufferedReader(
      new java.io.InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val output =
      try reader.lines().collect(Collectors.joining("\n"))
      finally reader.close()
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw new NotebookException(
        NotebookErrorCode.PYTHON_PACKAGE_INSTALL_FAILED,
        s"the operation exceeded its ${timeoutSeconds}s timeout")
    }
    CommandResult(process.exitValue(), output)
  }

  private def append(log: StringBuilder, text: String): Unit = {
    if (text != null && text.nonEmpty) {
      log.append(text)
      if (!text.endsWith("\n")) log.append("\n")
    }
  }

  /** pip output can be long; only the tail is useful and only the tail is returned. */
  private def tail(log: String): String = {
    val lines = log.split("\n")
    lines.takeRight(40).mkString("\n")
  }

  private def failure(message: String, log: String): NotebookException =
    new NotebookException(
      NotebookErrorCode.PYTHON_PACKAGE_INSTALL_FAILED,
      message,
      details = Map("log" -> tail(log)))
}

object PythonEnvironmentBuilder {

  /** Root for all environments, kept under the server's work directory by default. */
  def rootDir(conf: KyuubiConf): Path =
    conf.get(PYTHON_WORK_DIR).map(Paths.get(_)).getOrElse {
      Paths.get(sys.env.getOrElse("KYUUBI_HOME", sys.props("java.io.tmpdir")))
        .resolve("work").resolve("notebook-python")
    }
}
