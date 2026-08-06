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

import java.nio.file.Files
import java.util.UUID

import scala.collection.JavaConverters._

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.service._

/**
 * Wires the notebook services over a throwaway SQLite database, which exercises the real SQL
 * rather than a stub store.
 */
trait NotebookTestBase extends KyuubiFunSuite {

  protected var manager: NotebookManager = _
  protected var conf: KyuubiConf = _

  protected val alice = NotebookPrincipal("alice", admin = false)
  protected val bob = NotebookPrincipal("bob", admin = false)
  protected val root = NotebookPrincipal("root", admin = true)

  override def beforeAll(): Unit = {
    super.beforeAll()
    val databaseFile = Files.createTempDirectory("kyuubi-notebook-test")
      .resolve(s"notebook-${UUID.randomUUID()}.db")
    conf = new KyuubiConf(false)
      .set(METADATA_STORE_JDBC_DATABASE_TYPE, "SQLITE")
      .set(METADATA_STORE_JDBC_URL, s"jdbc:sqlite:${databaseFile.toAbsolutePath}")
      .set(METADATA_STORE_JDBC_DATABASE_SCHEMA_INIT, false)
    // The document-level suites never reach a runtime, so the backend accessor is a trap
    // rather than a stub: if one of them ever does, the test fails loudly.
    manager = new NotebookManager(
      () => throw new IllegalStateException("the backend service must not be used here"),
      () => "test-instance")
    manager.initialize(conf)
    manager.start()
  }

  override def afterAll(): Unit = {
    if (manager != null) manager.stop()
    super.afterAll()
  }

  protected def documents: NotebookDocumentService = manager.documents
  protected def revisions: NotebookRevisionService = manager.revisions
  protected def permissions: NotebookPermissionService = manager.permissions
  protected def content: NotebookContentService = manager.content
  protected def schedules: NotebookScheduleService = manager.schedules

  protected def createNotebook(
      principal: NotebookPrincipal,
      name: String,
      folderId: Option[String] = None,
      cells: Seq[(String, String, String)] = Seq.empty): (Notebook, Seq[NotebookCell]) = {
    val request = new CreateNotebookRequest
    request.setName(name)
    folderId.foreach(request.setFolderId)
    request.setCells(cells.map { case (cellType, language, source) =>
      val cell = new CreateCellRequest
      cell.setCellType(cellType)
      cell.setLanguage(language)
      cell.setSource(source)
      cell
    }.asJava)
    documents.createNotebook(principal, request)
  }

  protected def createFolder(
      principal: NotebookPrincipal,
      name: String,
      parentId: Option[String] = None): NotebookFolder = {
    val request = new CreateFolderRequest
    request.setName(name)
    parentId.foreach(request.setParentId)
    documents.createFolder(principal, request)
  }

  protected def addCell(
      principal: NotebookPrincipal,
      notebookId: String,
      source: String,
      language: String = "SQL",
      position: Option[Int] = None): NotebookCell = {
    val request = new CreateCellRequest
    request.setCellType("CODE")
    request.setLanguage(language)
    request.setSource(source)
    position.foreach(p => request.setPosition(Integer.valueOf(p)))
    documents.createCell(principal, notebookId, request)
  }

  protected def interceptNotebook(code: NotebookErrorCode.Value)(block: => Any)
      : NotebookException = {
    val thrown = intercept[NotebookException](block)
    assert(thrown.code === code, s"expected $code but got ${thrown.code}: ${thrown.message}")
    thrown
  }
}
