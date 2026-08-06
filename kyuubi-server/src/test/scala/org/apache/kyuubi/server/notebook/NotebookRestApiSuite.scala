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

import java.util.UUID
import javax.ws.rs.client.Entity
import javax.ws.rs.core.MediaType

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode

import org.apache.kyuubi.{KyuubiFunSuite, RestFrontendTestHelper, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._

/**
 * Exercises the notebook endpoints over real HTTP, which is what verifies the JAX-RS routing -
 * in particular that the `{id}:action` paths are not swallowed by the plain `{id}` template.
 */
class NotebookRestApiSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  /**
   * The server would otherwise open the default SQLite database under KYUUBI_HOME, which
   * outlives the JVM: a second run of this suite would then collide with the notebooks the
   * first one created and fail with PATH_CONFLICT.
   */
  private val databaseFile =
    Utils.createTempDir().resolve(s"notebook-rest-${UUID.randomUUID()}.db")

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(METADATA_STORE_JDBC_DATABASE_TYPE, "SQLITE")
    .set(METADATA_STORE_JDBC_URL, s"jdbc:sqlite:${databaseFile.toAbsolutePath}")

  /** Also unique per run, so a shared database could not make these tests interfere either. */
  private val tag = UUID.randomUUID().toString.take(8)

  private val mapper = new ObjectMapper()

  /**
   * Bodies are built as a tree rather than as string literals. Hand-escaped JSON inside a Scala
   * interpolated string is a trap: `s"""...\"..."""` unescapes the quote and produces invalid
   * JSON, while the plain triple-quoted form does not.
   */
  private def json(fields: (String, String)*): ObjectNode = {
    val node = mapper.createObjectNode()
    fields.foreach { case (key, value) => node.put(key, value) }
    node
  }

  private def post(path: String, body: JsonNode): (Int, JsonNode) = {
    val response = webTarget.path(path).request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(body.toString, MediaType.APPLICATION_JSON_TYPE))
    (response.getStatus, readBody(response.readEntity(classOf[String])))
  }

  /**
   * Query parameters go through `queryParam`, never inside `path`: `WebTarget.path` percent-
   * encodes its argument, so a `?` embedded there becomes part of the path and misses the route.
   */
  private def get(path: String, params: (String, String)*): (Int, JsonNode) = {
    val target = params.foldLeft(webTarget.path(path)) { case (current, (key, value)) =>
      current.queryParam(key, value)
    }
    val response = target.request(MediaType.APPLICATION_JSON_TYPE).get()
    (response.getStatus, readBody(response.readEntity(classOf[String])))
  }

  /** Error bodies are not always JSON, so a parse failure is reported, not thrown. */
  private def readBody(body: String): JsonNode = {
    if (body == null || body.trim.isEmpty) {
      mapper.createObjectNode()
    } else {
      try {
        mapper.readTree(body)
      } catch {
        case _: Exception => mapper.createObjectNode().put("nonJsonBody", body)
      }
    }
  }

  test("the current-user endpoint reports the authenticated identity and no token") {
    val (status, body) = get("api/v1/me")
    assert(status === 200)
    assert(body.path("user").asText().nonEmpty)
    assert(body.has("admin"))
    assert(!body.toString.toLowerCase.contains("token"))
  }

  test("the status endpoint reports persistence health without private data") {
    val (status, body) = get("api/v1/notebook-status")
    assert(status === 200)
    assert(body.path("persistence").asText() === "HEALTHY")
    assert(body.path("kyuubiSql").asText() === "HEALTHY")
    // Whether Python is usable depends on the host, but it must report one of the two real
    // answers rather than a placeholder that hides an absent subsystem.
    assert(Seq("HEALTHY", "UNAVAILABLE").contains(
      body.path("pythonRuntimeManager").asText()))
  }

  test("a notebook can be created, read back and listed over HTTP") {
    val request = json("name" -> s"rest-created-$tag")
    val cell = mapper.createObjectNode()
    cell.put("cellType", "CODE").put("language", "SQL").put("source", "select 1")
    request.putArray("cells").add(cell)

    val (createStatus, created) = post("api/v1/notebooks", request)
    assert(createStatus === 200, created.toString)
    val id = created.path("id").asText()
    assert(id.nonEmpty)
    assert(created.path("path").asText().startsWith("/"))
    assert(created.path("cells").size() === 1)

    val (getStatus, fetched) = get(s"api/v1/notebooks/$id")
    assert(getStatus === 200, fetched.toString)
    assert(fetched.path("name").asText() === s"rest-created-$tag")
    assert(fetched.path("role").asText() === "OWNER")

    val (listStatus, listed) = get("api/v1/notebooks", "name" -> s"rest-created-$tag")
    assert(listStatus === 200, listed.toString)
    assert(listed.path("items").size() === 1)
    assert(listed.has("hasMore"))
  }

  test("an action path is routed to its action, not to the plain id template") {
    val (createStatus, created) =
      post("api/v1/notebooks", json("name" -> s"rest-clone-source-$tag"))
    assert(createStatus === 200, created.toString)
    val id = created.path("id").asText()

    val (cloneStatus, cloned) =
      post(s"api/v1/notebooks/$id:clone", json("name" -> s"rest-clone-copy-$tag"))
    assert(cloneStatus === 200, cloned.toString)
    assert(cloned.path("name").asText() === s"rest-clone-copy-$tag")
    assert(cloned.path("id").asText() !== id)
  }

  test("collection actions are reachable as siblings of the collection") {
    val document = mapper.createObjectNode()
    document.put("nbformat", 4)
    document.putArray("cells")

    val (importStatus, imported) = post(
      "api/v1/notebooks:import",
      json("name" -> s"rest-imported-$tag", "content" -> document.toString))
    assert(importStatus === 200, imported.toString)
    assert(imported.path("name").asText() === s"rest-imported-$tag")

    val (searchStatus, found) = get("api/v1/notebooks:search", "q" -> s"rest-imported-$tag")
    assert(searchStatus === 200, found.toString)
    assert(found.path("items").size() === 1)
  }

  test("a failure is rendered as the documented error envelope") {
    val (status, body) = get("api/v1/notebooks/does-not-exist")
    assert(status === 404)
    val error = body.path("error")
    assert(error.path("code").asText() === "NOTEBOOK_NOT_FOUND")
    assert(error.path("requestId").asText().nonEmpty)
    assert(!error.path("retryable").asBoolean())
    // A stack trace or internal class name would be a leak.
    assert(!body.toString.contains("org.apache.kyuubi.server.notebook"))
  }

  test("an invalid request is rejected with a safe message") {
    val (status, body) = post("api/v1/notebooks", json("name" -> "bad/name"))
    assert(status === 400, body.toString)
    assert(body.path("error").path("code").asText() === "INVALID_REQUEST")
  }
}
