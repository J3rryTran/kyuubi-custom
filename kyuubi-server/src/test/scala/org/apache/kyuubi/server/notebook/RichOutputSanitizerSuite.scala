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
import org.apache.kyuubi.server.notebook.python.RichOutputSanitizer

/**
 * The sanitizer is defence in depth rather than the boundary - the browser renders rich output
 * in a sandboxed iframe - so these assertions are about the common vectors never reaching it,
 * not about a proof of completeness.
 */
class RichOutputSanitizerSuite extends KyuubiFunSuite {

  private def sanitized(html: String): String = RichOutputSanitizer.sanitizeHtml(html)

  test("a pandas style table survives intact") {
    val table =
      """<table border="1" class="dataframe">
        |<thead><tr><th>a</th><th>b</th></tr></thead>
        |<tbody><tr><td>1</td><td>2</td></tr></tbody>
        |</table>""".stripMargin
    val result = sanitized(table)
    assert(result.contains("<table"))
    assert(result.contains("<td>1</td>"))
    assert(result.contains("dataframe"))
  }

  test("script elements go with their content") {
    val result = sanitized("<div>before<script>fetch('/api/v1/me')</script>after</div>")
    assert(!result.toLowerCase.contains("<script"))
    assert(!result.contains("fetch("))
    assert(result.contains("before"))
    assert(result.contains("after"))
  }

  test("the other resource-loading elements are removed") {
    Seq(
      "<iframe src='https://evil.example.com'></iframe>",
      "<object data='x'></object>",
      "<embed src='x'>",
      "<link rel='stylesheet' href='https://evil.example.com/x.css'>",
      "<meta http-equiv='refresh' content='0;url=https://evil.example.com'>",
      "<base href='https://evil.example.com/'>",
      "<form action='https://evil.example.com'></form>",
      "<style>body{background:url('https://evil.example.com')}</style>").foreach { payload =>
      val result = sanitized(payload).toLowerCase
      assert(
        !result.contains("evil.example.com"),
        s"$payload was not neutralised, got $result")
    }
  }

  test("event handler attributes are stripped") {
    Seq(
      """<img src="x" onerror="fetch('/api/v1/me')">""",
      """<div onclick='alert(1)'>x</div>""",
      """<body onload=alert(1)>""",
      """<svg onload="alert(1)"></svg>""").foreach { payload =>
      val result = sanitized(payload).toLowerCase
      assert(!result.contains("onerror"), s"$payload kept an event handler")
      assert(!result.contains("onclick"), s"$payload kept an event handler")
      assert(!result.contains("onload"), s"$payload kept an event handler")
    }
  }

  test("executable URLs are removed from links and sources") {
    Seq(
      """<a href="javascript:alert(1)">x</a>""",
      """<a href='JaVaScRiPt:alert(1)'>x</a>""",
      """<img src="data:text/html;base64,PHNjcmlwdD4=">""",
      """<a href="vbscript:msgbox">x</a>""").foreach { payload =>
      val result = sanitized(payload).toLowerCase
      assert(!result.contains("javascript:"), s"$payload kept a javascript URL")
      assert(!result.contains("vbscript:"), s"$payload kept a vbscript URL")
      assert(!result.contains("data:text/html"), s"$payload kept a data URL")
    }
  }

  test("comments are dropped, so nothing can hide inside one") {
    assert(!sanitized("<div>a<!-- <script>alert(1)</script> -->b</div>").contains("script"))
  }

  test("svg keeps its drawing but loses its script") {
    val svg =
      """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script>""" +
        """<path d="M0 0 L10 10" stroke="black"/></svg>"""
    val result = RichOutputSanitizer.sanitizeSvg(svg)
    assert(result.contains("<path"))
    assert(!result.toLowerCase.contains("<script"))
  }

  test("a base64 payload that does not decode is dropped rather than passed on") {
    assert(RichOutputSanitizer.sanitizeBase64("aGVsbG8=").contains("aGVsbG8="))
    assert(RichOutputSanitizer.sanitizeBase64("<script>alert(1)</script>").isEmpty)
    assert(RichOutputSanitizer.sanitizeBase64("").isEmpty)
  }

  test("an unknown mime type is reduced to plain text") {
    val result = RichOutputSanitizer.sanitize("application/x-made-up", "<b>x</b>")
    assert(result.map(_._1).contains("text/plain"))
  }

  test("png survives, and an unparseable png is dropped") {
    assert(RichOutputSanitizer.sanitize("image/png", "aGVsbG8=").map(_._1).contains("image/png"))
    assert(RichOutputSanitizer.sanitize("image/png", "!!!not base64!!!").isEmpty)
  }
}
