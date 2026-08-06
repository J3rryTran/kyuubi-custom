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

import java.util.Base64

/**
 * Strips the obviously executable parts out of rich output.
 *
 * This is defence in depth, not the boundary. A cell can emit any HTML it likes, and a
 * hand-written filter is the wrong thing to stake safety on: the browser is asked to render this
 * inside a sandboxed iframe, where scripts cannot run and there is no same-origin access
 * regardless of what slips through here. What this pass buys is that the common attacks never
 * reach the browser at all, and that a future consumer which forgets the iframe is not instantly
 * exploitable.
 */
object RichOutputSanitizer {

  /** Elements whose content is code or fetches a resource, removed with everything inside. */
  private val DANGEROUS_ELEMENTS =
    Seq("script", "style", "iframe", "object", "embed", "applet", "link", "meta", "base", "form")

  private val EVENT_ATTRIBUTE = "(?i)\\son[a-z-]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)".r

  /** `javascript:`, `vbscript:` and `data:text/html` are the URL forms that execute. */
  private val EXECUTABLE_URL =
    ("(?i)(href|src|xlink:href|action|formaction)\\s*=\\s*(\"|')?\\s*" +
      "(javascript|vbscript|data\\s*:\\s*text/html)[^\"'>\\s]*(\"|')?").r

  private val MAX_LENGTH = 1024 * 1024

  def sanitizeHtml(html: String): String = {
    if (html == null) {
      ""
    } else {
      var result = html.take(MAX_LENGTH)
      DANGEROUS_ELEMENTS.foreach { element =>
        // Paired form first, so the content between the tags goes with them.
        result = result.replaceAll(s"(?is)<\\s*$element\\b[^>]*>.*?<\\s*/\\s*$element\\s*>", "")
        result = result.replaceAll(s"(?is)<\\s*/?\\s*$element\\b[^>]*>", "")
      }
      result = EVENT_ATTRIBUTE.replaceAllIn(result, "")
      result = EXECUTABLE_URL.replaceAllIn(result, "")
      // A comment can hide an unbalanced tag from the passes above, and carries no display value.
      result.replaceAll("(?s)<!--.*?-->", "")
    }
  }

  /** SVG is HTML's rules plus its own script vectors, so the same pass applies. */
  def sanitizeSvg(svg: String): String = sanitizeHtml(svg)

  /**
   * Base64 payloads are re-decoded rather than trusted. A value that is not valid base64 is
   * dropped: it cannot be an image, so whatever it is has no business reaching an `img` tag.
   */
  def sanitizeBase64(data: String): Option[String] = {
    if (data == null || data.isEmpty || data.length > MAX_LENGTH) {
      None
    } else {
      try {
        Base64.getDecoder.decode(data.replaceAll("\\s", ""))
        Some(data.replaceAll("\\s", ""))
      } catch {
        case _: IllegalArgumentException => None
      }
    }
  }

  /** Applies the rule that fits the MIME type; an unknown type is reduced to plain text. */
  def sanitize(mimeType: String, data: String): Option[(String, String)] = mimeType match {
    case "text/html" => Some("text/html" -> sanitizeHtml(data))
    case "image/svg+xml" => Some("image/svg+xml" -> sanitizeSvg(data))
    case "image/png" | "image/jpeg" | "image/gif" =>
      sanitizeBase64(data).map(mimeType -> _)
    case "application/json" => Some("application/json" -> data.take(MAX_LENGTH))
    case "text/plain" => Some("text/plain" -> data.take(MAX_LENGTH))
    case _ => Some("text/plain" -> data.take(MAX_LENGTH))
  }
}
