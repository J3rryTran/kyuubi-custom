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

package org.apache.kyuubi.server.notebook

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, NotebookException}
import org.apache.kyuubi.server.notebook.python.RequirementSpec

/**
 * The parser is the boundary that stops a package name from becoming a pip option, a URL or a
 * command, so the rejection cases matter more here than the acceptance ones.
 */
class RequirementSpecSuite extends KyuubiFunSuite {

  private def rejects(raw: String): Unit = {
    val thrown = intercept[NotebookException](RequirementSpec.parse(raw))
    assert(
      thrown.code === NotebookErrorCode.PYTHON_PACKAGE_INVALID,
      s"expected $raw to be rejected as invalid but got ${thrown.code}")
  }

  test("the four documented forms are accepted and re-rendered canonically") {
    assert(RequirementSpec.parse("pandas").render === "pandas")
    assert(RequirementSpec.parse("pandas==2.3.1").render === "pandas==2.3.1")
    assert(RequirementSpec.parse("numpy>=2,<3").render === "numpy>=2,<3")
    assert(RequirementSpec.parse("celery[redis]==5.3.6").render === "celery[redis]==5.3.6")
  }

  test("whitespace inside a constraint is normalized away") {
    assert(RequirementSpec.parse("  numpy >= 2 , < 3  ").render === "numpy>=2,<3")
  }

  test("names with the legal separators survive") {
    Seq("zope.interface", "typing_extensions", "ruamel-yaml").foreach { name =>
      assert(RequirementSpec.parse(name).name === name)
    }
  }

  test("pip options are rejected") {
    Seq(
      "--index-url=https://evil.example.com/simple",
      "-e .",
      "--trusted-host evil.example.com",
      "-r requirements.txt").foreach(rejects)
  }

  test("anything that names a location rather than a distribution is rejected") {
    Seq(
      "requests @ https://evil.example.com/x.whl",
      "https://evil.example.com/x.whl",
      "git+https://github.com/psf/requests",
      "./local-package",
      "/tmp/package",
      "..\\windows\\path").foreach(rejects)
  }

  test("shell metacharacters are rejected") {
    Seq(
      "pandas; rm -rf /",
      "pandas && curl evil.example.com",
      "pandas | sh",
      "pandas`id`",
      "pandas$(id)",
      "pandas'\"").foreach(rejects)
  }

  test("empty, oversized and control-character input is rejected") {
    rejects("")
    rejects("   ")
    rejects("a" * 201)
    rejects("pandas\nnumpy")
    // Surrounding whitespace is trimmed, but an internal gap would be a second pip argument.
    assert(RequirementSpec.parse("pandas ").name === "pandas")
    rejects("pandas numpy")
    rejects("pandas --upgrade")
  }

  test("a malformed version constraint is rejected") {
    Seq("pandas==", "pandas=2", "pandas>>2", "pandas==>2").foreach(rejects)
  }

  test("names are compared after PEP 503 normalization") {
    assert(RequirementSpec.normalize("Zope.Interface") === "zope-interface")
    assert(RequirementSpec.normalize("typing_extensions") === "typing-extensions")
    assert(RequirementSpec.normalize("ruamel-yaml") === RequirementSpec.normalize("ruamel.yaml"))
  }
}
