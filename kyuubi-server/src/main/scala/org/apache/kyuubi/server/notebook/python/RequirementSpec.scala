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

import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, NotebookException}

/**
 * A requirement the server is willing to install.
 *
 * Only the parsed form is ever handed to pip, and pip is invoked as an argument vector built
 * from these fields. Nothing a caller writes can therefore become an option, a URL, a path or a
 * shell token, which is the whole point of parsing rather than passing the text through.
 */
case class RequirementSpec(name: String, extras: Seq[String], constraint: Option[String]) {

  /** Canonical `name[extra1,extra2]<constraint>` form, safe to pass as one pip argument. */
  def render: String = {
    val extraPart = if (extras.isEmpty) "" else extras.mkString("[", ",", "]")
    s"$name$extraPart${constraint.getOrElse("")}"
  }
}

object RequirementSpec {

  /** PEP 508 names: letters and digits, separated by `-`, `_` or `.`. */
  private val NAME = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"

  private val VERSION = "(?:[<>!=~]=|[<>])\\s*[A-Za-z0-9][A-Za-z0-9.*+!_-]*"

  private val PATTERN =
    ("^(" + NAME + ")" +
      "(?:\\[(" + NAME + "(?:\\s*,\\s*" + NAME + ")*)\\])?" +
      "\\s*(" + VERSION + "(?:\\s*,\\s*" + VERSION + ")*)?$").r

  private val MAX_LENGTH = 200

  /**
   * Tokens that would turn a requirement into something other than a package name. `<` and `>`
   * are deliberately absent: they are how a version constraint is written.
   */
  private val FORBIDDEN_ANYWHERE: Seq[(String, String)] = Seq(
    "@" -> "direct references are not accepted",
    "://" -> "URLs are not accepted",
    "/" -> "paths are not accepted",
    "\\" -> "paths are not accepted",
    ";" -> "shell metacharacters are not accepted",
    "&" -> "shell metacharacters are not accepted",
    "|" -> "shell metacharacters are not accepted",
    "`" -> "shell metacharacters are not accepted",
    "$" -> "shell metacharacters are not accepted",
    "\"" -> "quotes are not accepted",
    "'" -> "quotes are not accepted")

  def parse(raw: String): RequirementSpec = {
    val value = Option(raw).map(_.trim).getOrElse("")
    if (value.isEmpty) {
      throw invalid("a requirement must not be empty")
    }
    if (value.length > MAX_LENGTH) {
      throw invalid(s"a requirement must be at most $MAX_LENGTH characters")
    }
    if (value.exists(_.isControl)) {
      throw invalid("a requirement must not contain control characters")
    }
    // A dash is legal inside a name but an option anywhere a pip argument starts.
    if (value.startsWith("-")) {
      throw invalid("pip options are not accepted")
    }
    FORBIDDEN_ANYWHERE.foreach { case (token, message) =>
      if (value.contains(token)) throw invalid(message)
    }
    PATTERN.findFirstMatchIn(value) match {
      case Some(matched) =>
        RequirementSpec(
          name = matched.group(1),
          extras = Option(matched.group(2))
            .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq)
            .getOrElse(Seq.empty),
          constraint = Option(matched.group(3)).map(_.replaceAll("\\s+", "")).filter(_.nonEmpty))
      case None =>
        throw invalid(
          "a requirement must look like name, name==1.2.3, name>=1,<2 or name[extra]==1.2.3")
    }
  }

  /** Just the distribution name, used by uninstall and by allow/deny checks. */
  def parseName(raw: String): String = parse(raw).name

  /** PEP 503 normalization, so `Foo.Bar` and `foo-bar` compare equal against a configured list. */
  def normalize(name: String): String = name.toLowerCase.replaceAll("[-_.]+", "-")

  private def invalid(message: String): NotebookException =
    new NotebookException(NotebookErrorCode.PYTHON_PACKAGE_INVALID, message)
}
