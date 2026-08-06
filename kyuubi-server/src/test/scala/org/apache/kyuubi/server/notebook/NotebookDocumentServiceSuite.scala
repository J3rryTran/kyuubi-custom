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

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.notebook.api._

class NotebookDocumentServiceSuite extends NotebookTestBase {

  test("notebooks are rooted at their owner and paths are derived") {
    val (notebook, _) = createNotebook(alice, "sales")
    assert(notebook.owner === "alice")
    assert(notebook.createdBy === "alice")
    assert(notebook.path === "/alice/sales")
    assert(notebook.version === 1L)

    val folder = createFolder(alice, "reports")
    assert(folder.path === "/alice/reports")
    val (nested, _) = createNotebook(alice, "weekly", Some(folder.id))
    assert(nested.path === "/alice/reports/weekly")
  }

  test("two users may use the same notebook name") {
    createNotebook(alice, "shared-name")
    val (bobs, _) = createNotebook(bob, "shared-name")
    assert(bobs.path === "/bob/shared-name")
  }

  test("a duplicate path is rejected") {
    createNotebook(alice, "duplicate")
    interceptNotebook(NotebookErrorCode.PATH_CONFLICT)(createNotebook(alice, "duplicate"))
  }

  test("a name that would escape its folder is rejected") {
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST)(createNotebook(alice, "../escape"))
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST)(createNotebook(alice, ".."))
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST)(createNotebook(alice, "  "))
  }

  test("another user's notebook is invisible rather than forbidden") {
    val (notebook, _) = createNotebook(alice, "private")
    // Reporting NOT_FOUND keeps id probing from confirming that a notebook exists.
    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      documents.getNotebook(bob, notebook.id)
    }
    assert(documents.getNotebook(root, notebook.id).id === notebook.id)
  }

  test("cells keep a contiguous order through insert, delete and reorder") {
    val (notebook, _) = createNotebook(
      alice,
      "ordered",
      cells = Seq(("CODE", "SQL", "select 1"), ("CODE", "PYTHON", "print(1)")))
    val middle = addCell(alice, notebook.id, "select 2", position = Some(1))
    assert(middle.position === 1)
    assert(documents.listCells(notebook.id).map(_.source) ===
      Seq("select 1", "select 2", "print(1)"))

    documents.deleteCell(alice, notebook.id, middle.id)
    assert(documents.listCells(notebook.id).map(_.position) === Seq(0, 1))

    val ids = documents.listCells(notebook.id).map(_.id).reverse
    val request = new ReorderCellsRequest
    request.setCellIds(ids.asJava)
    val reordered = documents.reorderCells(alice, notebook.id, request)
    assert(reordered.map(_.id) === ids)
    assert(reordered.map(_.position) === Seq(0, 1))
  }

  test("reorder must list every cell exactly once") {
    val (notebook, created) = createNotebook(
      alice,
      "partial-reorder",
      cells = Seq(("CODE", "SQL", "a"), ("CODE", "SQL", "b")))
    val request = new ReorderCellsRequest
    request.setCellIds(Seq(created.head.id).asJava)
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      documents.reorderCells(alice, notebook.id, request)
    }
  }

  test("a markdown cell may not claim an executable language") {
    val (notebook, _) = createNotebook(alice, "markdown-check")
    val request = new CreateCellRequest
    request.setCellType("MARKDOWN")
    request.setLanguage("SQL")
    request.setSource("# title")
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      documents.createCell(alice, notebook.id, request)
    }
  }

  test("a code cell may not use a non-executable language") {
    val (notebook, _) = createNotebook(alice, "language-check")
    val request = new CreateCellRequest
    request.setCellType("CODE")
    request.setLanguage("MARKDOWN")
    request.setSource("x")
    interceptNotebook(NotebookErrorCode.UNSUPPORTED_LANGUAGE) {
      documents.createCell(alice, notebook.id, request)
    }
  }

  test("a stale version is rejected") {
    val (notebook, _) = createNotebook(alice, "versioned")
    val request = new UpdateNotebookRequest
    request.setDescription("first")
    documents.updateNotebook(alice, notebook.id, request)

    val stale = new UpdateNotebookRequest
    stale.setDescription("second")
    stale.setVersion(java.lang.Long.valueOf(notebook.version))
    interceptNotebook(NotebookErrorCode.VERSION_CONFLICT) {
      documents.updateNotebook(alice, notebook.id, stale)
    }
  }

  test("renaming a folder rewrites the paths of everything below it") {
    val parent = createFolder(alice, "old-name")
    val child = createFolder(alice, "child", Some(parent.id))
    val (notebook, _) = createNotebook(alice, "leaf", Some(child.id))
    assert(notebook.path === "/alice/old-name/child/leaf")

    val request = new UpdateFolderRequest
    request.setName("new-name")
    documents.updateFolder(alice, parent.id, request)

    assert(documents.getFolder(alice, child.id).path === "/alice/new-name/child")
    assert(documents.getNotebook(alice, notebook.id).path === "/alice/new-name/child/leaf")
  }

  test("a folder cannot be moved inside itself") {
    val parent = createFolder(alice, "outer")
    val child = createFolder(alice, "inner", Some(parent.id))
    val request = new UpdateFolderRequest
    request.setParentId(child.id)
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      documents.updateFolder(alice, parent.id, request)
    }
  }

  test("deleting a folder cascades to its subtree and frees the paths") {
    val folder = createFolder(alice, "doomed")
    val child = createFolder(alice, "sub", Some(folder.id))
    val (notebook, _) = createNotebook(alice, "inside", Some(child.id))

    documents.deleteFolder(alice, folder.id, None)

    interceptNotebook(NotebookErrorCode.FOLDER_NOT_FOUND)(documents.getFolder(alice, child.id))
    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      documents.getNotebook(alice, notebook.id)
    }
    // The path is free again, which is what the tombstone rewrite is for.
    assert(createFolder(alice, "doomed").path === "/alice/doomed")
  }

  test("a clone is an independent copy owned by whoever cloned it") {
    val (source, _) = createNotebook(
      alice,
      "template",
      cells = Seq(("CODE", "SQL", "select 1")))
    val request = new CloneNotebookRequest
    request.setName("copy")
    val (cloned, clonedCells) = documents.cloneNotebook(alice, source.id, request)
    assert(cloned.id !== source.id)
    assert(clonedCells.map(_.source) === Seq("select 1"))
    assert(clonedCells.head.id !== documents.listCells(source.id).head.id)

    documents.deleteCell(alice, cloned.id, clonedCells.head.id)
    assert(documents.listCells(source.id).size === 1)
  }

  test("a notebook cannot be moved into another user's space") {
    val (notebook, _) = createNotebook(alice, "mine")
    val bobsFolder = createFolder(bob, "bobs")
    val request = new MoveNotebookRequest
    request.setFolderId(bobsFolder.id)
    // Bob's folder is not even visible to alice, so the move stops at the folder lookup.
    interceptNotebook(NotebookErrorCode.FOLDER_NOT_FOUND) {
      documents.moveNotebook(alice, notebook.id, request)
    }
  }

  test("a cell source larger than the limit is rejected") {
    val (notebook, _) = createNotebook(alice, "oversized")
    val request = new CreateCellRequest
    request.setCellType("CODE")
    request.setLanguage("SQL")
    request.setSource("x" * (conf.get(NotebookConf.NOTEBOOK_CELL_SOURCE_MAX_SIZE).toInt + 1))
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      documents.createCell(alice, notebook.id, request)
    }
  }

  test("listing is paginated and only shows accessible notebooks") {
    (1 to 5).foreach(i => createNotebook(bob, f"page-$i%02d"))
    val first = documents.listNotebooks(bob, None, None, Some("page-"), None, None, None, Some(2))
    assert(first.items.size === 2)
    assert(first.hasMore)
    val second =
      documents.listNotebooks(bob, None, None, Some("page-"), None, None, first.nextCursor, Some(2))
    assert(second.items.map(_.name) === Seq("page-03", "page-04"))

    val aliceView =
      documents.listNotebooks(alice, None, None, Some("page-"), None, None, None, Some(10))
    assert(aliceView.items.isEmpty)
  }

  test("search covers names, descriptions and cell sources") {
    val (notebook, _) = createNotebook(
      alice,
      "searchable",
      cells = Seq(("CODE", "SQL", "select needle from t")))
    val update = new UpdateNotebookRequest
    update.setDescription("haystack")
    documents.updateNotebook(alice, notebook.id, update)

    def search(term: String): Seq[String] =
      documents.listNotebooks(alice, None, None, None, Some(term), None, None, Some(10))
        .items.map(_.id)

    assert(search("needle").contains(notebook.id))
    assert(search("haystack").contains(notebook.id))
    assert(search("searchab").contains(notebook.id))
    assert(search("absent-term").isEmpty)
  }

  test("a wildcard in a search term is matched literally") {
    val (percent, _) = createNotebook(alice, "literal-percent", cells = Seq(("CODE", "SQL", "50%")))
    createNotebook(alice, "literal-other", cells = Seq(("CODE", "SQL", "nothing")))
    val hits = documents.listNotebooks(alice, None, None, None, Some("%"), None, None, Some(10))
    assert(hits.items.map(_.id) === Seq(percent.id))
  }
}
