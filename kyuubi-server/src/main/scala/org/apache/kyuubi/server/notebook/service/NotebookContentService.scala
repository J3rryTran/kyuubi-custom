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

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Import, export and revision restore - everything that turns a notebook into a portable
 * document and back.
 *
 * Imported documents are treated as hostile input: identifiers are regenerated, ownership and
 * permissions in the payload are ignored, and outputs are dropped rather than carried over,
 * because a stored output can contain arbitrary HTML from whoever produced the file.
 */
class NotebookContentService(
    conf: KyuubiConf,
    store: NotebookStore,
    documents: NotebookDocumentService,
    revisions: NotebookRevisionService,
    permissions: NotebookPermissionService) extends Logging {

  private val maxImportSize = conf.get(NOTEBOOK_IMPORT_MAX_SIZE)
  private val maxCells = conf.get(NOTEBOOK_MAX_CELLS)

  def export(
      principal: NotebookPrincipal,
      notebookId: String,
      format: String): (String, NotebookDocument) = {
    val notebook = documents.getNotebook(principal, notebookId)
    val cells = store.listCells(notebookId)
    val document = revisions.toDocument(notebook, cells)
    parseFormat(format) match {
      case NotebookFormat.KYUUBI => (NotebookJson.write(document), document)
      case NotebookFormat.IPYNB => (IpynbCodec.toIpynb(document), document)
    }
  }

  def importNotebook(
      principal: NotebookPrincipal,
      request: ImportNotebookRequest): (Notebook, Seq[NotebookCell]) = {
    val content = Option(request.getContent).getOrElse {
      throw NotebookException.invalid("content must not be empty")
    }
    val size = content.getBytes(StandardCharsets.UTF_8).length
    if (size > maxImportSize) {
      throw NotebookException.invalid(
        s"the document exceeds the $maxImportSize byte import limit",
        Map("size" -> size.toString, "limit" -> maxImportSize.toString))
    }
    val tree = NotebookJson.readTree(content)
    val format = Option(request.getFormat).filter(_.nonEmpty)
      .map(parseFormat)
      .getOrElse(detectFormat(tree))
    val document = format match {
      case NotebookFormat.KYUUBI => NotebookJson.readDocument(content)
      case NotebookFormat.IPYNB => IpynbCodec.fromIpynb(tree)
    }
    if (document.cells.size > maxCells) {
      throw NotebookException.invalid(s"a notebook may contain at most $maxCells cells")
    }
    val name = Option(request.getName).filter(_.nonEmpty).getOrElse(document.name)
    val createRequest = new CreateNotebookRequest
    createRequest.setName(name)
    createRequest.setFolderId(request.getFolderId)
    createRequest.setDescription(document.description.orNull)
    createRequest.setDefaultCatalog(document.defaultCatalog.orNull)
    createRequest.setDefaultSchema(document.defaultSchema.orNull)
    createRequest.setRuntimeProfile(document.runtimeProfile.orNull)
    createRequest.setCells(document.cells.sortBy(_.position).map(toCellRequest).asJava)
    documents.createNotebook(principal, createRequest)
  }

  def restoreRevision(
      principal: NotebookPrincipal,
      notebookId: String,
      revisionNumber: Long): (Notebook, Seq[NotebookCell]) = {
    val notebook = documents.loadNotebook(notebookId)
    permissions.requireWrite(notebook, principal)
    val revision = revisions.load(notebookId, revisionNumber)
    val document = NotebookJson.readDocument(revision.documentSnapshot)
    val now = System.currentTimeMillis()
    val cells = document.cells.sortBy(_.position).zipWithIndex.map { case (cell, index) =>
      documents.buildCell(notebookId, index, toCellRequest(cell), now)
    }
    val restored = notebook.copy(
      description = document.description,
      defaultCatalog = document.defaultCatalog,
      defaultSchema = document.defaultSchema,
      runtimeProfile = document.runtimeProfile,
      updatedAt = now,
      updatedBy = principal.user,
      version = notebook.version + 1)
    if (!store.replaceCells(notebookId, cells, restored, notebook.version)) {
      throw NotebookException.versionConflict(s"notebook $notebookId was modified concurrently")
    }
    revisions.create(
      restored,
      cells,
      principal.user,
      Some(s"restored from revision $revisionNumber"),
      protectedRevision = true)
    (restored, cells)
  }

  private def toCellRequest(cell: NotebookDocumentCell): CreateCellRequest = {
    val request = new CreateCellRequest
    request.setCellType(cell.cellType)
    request.setLanguage(cell.language)
    request.setSource(cell.source)
    request.setMetadata(sanitizeMetadata(cell.metadata).asJava)
    request.setConfiguration(sanitizeMetadata(cell.configuration).asJava)
    request
  }

  /**
   * Keeps imported metadata to short, printable scalars. Jupyter files carry arbitrary vendor
   * metadata, and it is echoed back to browsers.
   */
  private def sanitizeMetadata(metadata: Map[String, String]): Map[String, String] = {
    Option(metadata).getOrElse(Map.empty)
      .filter { case (key, value) => key != null && value != null }
      .filter { case (key, _) =>
        key.length <= 128 && IpynbCodec.SAFE_KEY.pattern.matcher(key)
          .matches()
      }
      .map { case (key, value) => key -> value.take(1024) }
  }

  private def parseFormat(raw: String): NotebookFormat.Value =
    Option(raw).map(_.trim).filter(_.nonEmpty) match {
      case None => NotebookFormat.KYUUBI
      case Some(value) => NotebookFormat.values.find(_.toString.equalsIgnoreCase(value))
          .getOrElse(throw NotebookException.invalid(
            s"format must be one of ${NotebookFormat.values.mkString(", ")}"))
    }

  private def detectFormat(tree: JsonNode): NotebookFormat.Value =
    if (tree.has("nbformat")) NotebookFormat.IPYNB else NotebookFormat.KYUUBI
}

object NotebookFormat extends Enumeration {
  val KYUUBI, IPYNB = Value
}

/**
 * Minimal Jupyter `.ipynb` interop.
 *
 * Only source and language survive the round trip. Stored outputs are dropped on import - they
 * are attacker-controlled rich content in a file that a user may have received from anywhere -
 * and are not written on export, which keeps the exported file free of result data that the
 * recipient may not be entitled to see.
 */
object IpynbCodec {

  val SAFE_KEY = "[A-Za-z0-9_.-]+".r

  private val PYTHON_KERNEL = "python3"

  def fromIpynb(tree: JsonNode): NotebookDocument = {
    val notebookLanguage = Option(tree.path("metadata").path("kernelspec").path("language").asText(
      null)).map(_.toUpperCase).getOrElse("PYTHON")
    val cellsNode = tree.path("cells")
    if (!cellsNode.isArray) {
      throw NotebookException.invalid("the ipynb document has no cells array")
    }
    val cells = cellsNode.asInstanceOf[ArrayNode].asScala.zipWithIndex.map { case (node, index) =>
      val kind = node.path("cell_type").asText("code")
      val source = joinSource(node.path("source"))
      val (cellType, language) = kind match {
        case "markdown" | "raw" => (CellType.MARKDOWN.toString, CellLanguage.MARKDOWN.toString)
        case _ =>
          val perCell = Option(node.path("metadata").path("kyuubi").path("language").asText(null))
          val resolved = perCell.map(_.toUpperCase).getOrElse(notebookLanguage)
          val language =
            if (resolved == CellLanguage.SQL.toString) CellLanguage.SQL else CellLanguage.PYTHON
          (CellType.CODE.toString, language.toString)
      }
      NotebookDocumentCell(index, cellType, language, source, Map.empty, Map.empty)
    }.toSeq
    NotebookDocument(
      formatVersion = NotebookDocument.CURRENT_FORMAT_VERSION,
      name = "imported notebook",
      description = None,
      defaultCatalog = None,
      defaultSchema = None,
      runtimeProfile = None,
      cells = cells)
  }

  def toIpynb(document: NotebookDocument): String = {
    val cells = document.cells.sortBy(_.position).map { cell =>
      val kind = if (cell.cellType == CellType.MARKDOWN.toString) "markdown" else "code"
      val base = Map[String, Any](
        "cell_type" -> kind,
        "metadata" -> Map("kyuubi" -> Map("language" -> cell.language)),
        "source" -> splitSource(cell.source))
      if (kind == "code") base ++ Map("execution_count" -> null, "outputs" -> Seq.empty[Any])
      else base
    }
    NotebookJson.write(Map(
      "nbformat" -> 4,
      "nbformat_minor" -> 5,
      "metadata" -> Map(
        "kernelspec" -> Map(
          "name" -> PYTHON_KERNEL,
          "display_name" -> "Python 3",
          "language" -> "python"),
        "kyuubi" -> Map("name" -> document.name)),
      "cells" -> cells))
  }

  /** `source` is either a string or an array of lines, and both forms appear in the wild. */
  private def joinSource(node: JsonNode): String = {
    if (node.isArray) {
      node.asInstanceOf[ArrayNode].asScala.map(_.asText("")).mkString
    } else {
      node.asText("")
    }
  }

  private def splitSource(source: String): Seq[String] = {
    if (source.isEmpty) {
      Seq.empty
    } else {
      val lines = source.split("\n", -1)
      lines.zipWithIndex.map { case (line, index) =>
        if (index == lines.length - 1) line else line + "\n"
      }.filter(_.nonEmpty).toSeq
    }
  }

}
