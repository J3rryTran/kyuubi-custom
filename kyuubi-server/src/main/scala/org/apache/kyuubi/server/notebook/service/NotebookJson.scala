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

package org.apache.kyuubi.server.notebook.service

import java.nio.charset.StandardCharsets
import java.util.Base64

import com.fasterxml.jackson.databind.{DeserializationFeature, JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import org.apache.kyuubi.server.notebook.api.{NotebookDocument, NotebookException}

object NotebookJson {

  val mapper: ObjectMapper = new ObjectMapper()
    .registerModule(DefaultScalaModule)
    // Documents round-trip through revisions and imports; an unknown field from a newer writer
    // must not make an old server refuse to read its own history.
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

  def write(value: Any): String = mapper.writeValueAsString(value)

  def readDocument(json: String): NotebookDocument = {
    try {
      mapper.readValue(json, classOf[NotebookDocument])
    } catch {
      case e: Exception =>
        throw new NotebookException(
          org.apache.kyuubi.server.notebook.api.NotebookErrorCode.INVALID_REQUEST,
          "the notebook document could not be parsed",
          cause = e)
    }
  }

  def readTree(json: String): JsonNode = {
    try {
      mapper.readTree(json)
    } catch {
      case e: Exception =>
        throw new NotebookException(
          org.apache.kyuubi.server.notebook.api.NotebookErrorCode.INVALID_REQUEST,
          "the payload is not valid JSON",
          cause = e)
    }
  }

  /**
   * Cursors are opaque by contract: encoding keeps clients from constructing one by hand and
   * lets the sort key change without breaking them.
   */
  def encodeCursor(value: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  def decodeCursor(cursor: String): String = {
    try {
      new String(Base64.getUrlDecoder.decode(cursor), StandardCharsets.UTF_8)
    } catch {
      case _: IllegalArgumentException => throw NotebookException.invalid("cursor is malformed")
    }
  }
}
