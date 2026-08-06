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

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.service.NotebookJson

class NotebookContentSuite extends NotebookTestBase {

  test("revisions accumulate as the notebook changes") {
    val (notebook, _) = createNotebook(alice, "revised")
    addCell(alice, notebook.id, "select 1")
    val listed = revisions.list(notebook.id, 10, None)
    assert(listed.items.size >= 2)
    assert(listed.items.head.revisionNumber > listed.items.last.revisionNumber)
  }

  test("restore appends a protected revision instead of rewinding history") {
    val (notebook, _) = createNotebook(
      alice,
      "restorable",
      cells = Seq(("CODE", "SQL", "original")))
    val originalRevision = revisions.list(notebook.id, 10, None).items.head.revisionNumber

    val cell = documents.listCells(notebook.id).head
    val update = new UpdateCellRequest
    update.setSource("changed")
    documents.updateCell(alice, notebook.id, cell.id, update)
    assert(documents.listCells(notebook.id).head.source === "changed")

    content.restoreRevision(alice, notebook.id, originalRevision)
    assert(documents.listCells(notebook.id).head.source === "original")

    val after = revisions.list(notebook.id, 10, None).items
    assert(after.head.protectedRevision)
    assert(after.exists(_.revisionNumber == originalRevision))
  }

  test("a protected revision cannot be deleted") {
    val (notebook, _) = createNotebook(alice, "protected-revision")
    val request = new CreateRevisionRequest
    request.setReason("checkpoint")
    val checkpoint = revisions.create(
      documents.loadNotebook(notebook.id),
      documents.listCells(notebook.id),
      alice.user,
      Some("checkpoint"),
      protectedRevision = true)
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      revisions.delete(notebook.id, checkpoint.revisionNumber)
    }
  }

  test("export and import round-trip the native format") {
    val (notebook, _) = createNotebook(
      alice,
      "exportable",
      cells = Seq(("CODE", "SQL", "select 1"), ("MARKDOWN", "MARKDOWN", "# title")))
    val (payload, _) = content.export(alice, notebook.id, "KYUUBI")

    val request = new ImportNotebookRequest
    request.setContent(payload)
    request.setName("imported")
    val (imported, cells) = content.importNotebook(bob, request)

    assert(imported.owner === "bob")
    assert(imported.id !== notebook.id)
    assert(cells.map(_.source) === Seq("select 1", "# title"))
    assert(cells.map(_.cellType.toString) === Seq("CODE", "MARKDOWN"))
  }

  test("an imported document cannot dictate its owner") {
    val (notebook, _) = createNotebook(alice, "ownership-attempt")
    val (payload, _) = content.export(alice, notebook.id, "KYUUBI")
    val tampered = NotebookJson.readTree(payload).asInstanceOf[
      com.fasterxml.jackson.databind.node.ObjectNode]
    tampered.put("owner", "alice")
    tampered.put("createdBy", "alice")

    val request = new ImportNotebookRequest
    request.setContent(NotebookJson.write(tampered))
    request.setName("tampered")
    val (imported, _) = content.importNotebook(bob, request)
    assert(imported.owner === "bob")
    assert(imported.createdBy === "bob")
  }

  test("a jupyter notebook is imported and its outputs are dropped") {
    val ipynb =
      """{
        |  "nbformat": 4,
        |  "nbformat_minor": 5,
        |  "metadata": {"kernelspec": {"language": "python"}},
        |  "cells": [
        |    {"cell_type": "markdown", "source": ["# heading\n"], "metadata": {}},
        |    {"cell_type": "code", "source": ["value = 40\n", "print(value + 2)"],
        |     "metadata": {}, "execution_count": 3,
        |     "outputs": [{"output_type": "stream", "text": ["42"]}]}
        |  ]
        |}""".stripMargin

    val request = new ImportNotebookRequest
    request.setContent(ipynb)
    request.setName("from-jupyter")
    val (imported, cells) = content.importNotebook(alice, request)

    assert(imported.owner === "alice")
    assert(cells.size === 2)
    assert(cells.head.cellType === CellType.MARKDOWN)
    assert(cells(1).language === CellLanguage.PYTHON)
    assert(cells(1).source === "value = 40\nprint(value + 2)")
    // Outputs are not part of the cell model, so nothing from the file survives.
    assert(cells(1).metadata.isEmpty)
  }

  test("ipynb export carries the per-cell language and no outputs") {
    val (notebook, _) = createNotebook(
      alice,
      "to-jupyter",
      cells = Seq(("CODE", "SQL", "select 1")))
    val (payload, _) = content.export(alice, notebook.id, "IPYNB")
    val tree = NotebookJson.readTree(payload)

    assert(tree.path("nbformat").asInt() === 4)
    val cell = tree.path("cells").get(0)
    assert(cell.path("cell_type").asText() === "code")
    assert(cell.path("metadata").path("kyuubi").path("language").asText() === "SQL")
    assert(cell.path("outputs").size() === 0)
  }

  test("a re-imported ipynb keeps SQL cells as SQL") {
    val (notebook, _) = createNotebook(
      alice,
      "sql-round-trip",
      cells = Seq(("CODE", "SQL", "select 1")))
    val (payload, _) = content.export(alice, notebook.id, "IPYNB")

    val request = new ImportNotebookRequest
    request.setContent(payload)
    request.setName("sql-round-trip-back")
    val (_, cells) = content.importNotebook(alice, request)
    assert(cells.head.language === CellLanguage.SQL)
  }

  test("malformed content is refused with a safe message") {
    val request = new ImportNotebookRequest
    request.setContent("{not json")
    request.setName("broken")
    val thrown = interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      content.importNotebook(alice, request)
    }
    assert(!thrown.message.contains("com.fasterxml"))
  }

  test("an oversized document is refused before it is parsed") {
    val request = new ImportNotebookRequest
    request.setContent("x" * (conf.get(NotebookConf.NOTEBOOK_IMPORT_MAX_SIZE).toInt + 1))
    request.setName("huge")
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST)(content.importNotebook(alice, request))
  }

  test("a viewer may not restore a revision") {
    val (notebook, _) = createNotebook(alice, "restore-guard")
    val revision = revisions.list(notebook.id, 1, None).items.head.revisionNumber
    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      content.restoreRevision(bob, notebook.id, revision)
    }
  }
}
