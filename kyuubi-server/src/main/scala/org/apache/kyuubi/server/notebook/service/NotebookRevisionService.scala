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

import java.util.UUID

import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Snapshots of notebook content.
 *
 * A restore never rewinds history: it appends the restored content as a new revision, and that
 * new revision is protected so trimming cannot erase the evidence of the restore.
 */
class NotebookRevisionService(conf: KyuubiConf, store: NotebookStore) extends Logging {

  private val autoRevisionEnabled = conf.get(NOTEBOOK_AUTO_REVISION_ENABLED)
  private val maxRevisions = conf.get(NOTEBOOK_MAX_REVISIONS)

  def toDocument(notebook: Notebook, cells: Seq[NotebookCell]): NotebookDocument =
    NotebookDocument(
      formatVersion = NotebookDocument.CURRENT_FORMAT_VERSION,
      name = notebook.name,
      description = notebook.description,
      defaultCatalog = notebook.defaultCatalog,
      defaultSchema = notebook.defaultSchema,
      runtimeProfile = notebook.runtimeProfile,
      cells = cells.sortBy(_.position).map { cell =>
        NotebookDocumentCell(
          position = cell.position,
          cellType = cell.cellType.toString,
          language = cell.language.toString,
          source = cell.source,
          metadata = cell.metadata,
          configuration = cell.configuration)
      })

  /**
   * Records a revision as a side effect of an edit. A failure here must not fail the edit that
   * has already been committed, so it is logged and swallowed.
   */
  def recordAutomatic(
      notebook: Notebook,
      cells: Seq[NotebookCell],
      user: String,
      reason: String): Unit = {
    if (autoRevisionEnabled) {
      try {
        create(notebook, cells, user, Some(reason), protectedRevision = false)
      } catch {
        case NonFatal(e) => warn(s"Failed to record a revision of notebook ${notebook.id}", e)
      }
    }
  }

  def create(
      notebook: Notebook,
      cells: Seq[NotebookCell],
      user: String,
      reason: Option[String],
      protectedRevision: Boolean): NotebookRevision = {
    val revision = NotebookRevision(
      id = UUID.randomUUID().toString,
      notebookId = notebook.id,
      revisionNumber = store.nextRevisionNumber(notebook.id),
      documentSnapshot = NotebookJson.write(toDocument(notebook, cells)),
      createdAt = System.currentTimeMillis(),
      createdBy = user,
      reason = reason,
      protectedRevision = protectedRevision)
    store.createRevision(revision)
    store.trimRevisions(notebook.id, maxRevisions)
    revision
  }

  def list(
      notebookId: String,
      limit: Int,
      cursor: Option[String]): NotebookPage[NotebookRevisionView] = {
    val after = cursor.map(NotebookJson.decodeCursor).map(_.toLong)
    val found = store.listRevisions(notebookId, limit + 1, after)
    val hasMore = found.size > limit
    val page = found.take(limit)
    NotebookPage(
      page.map(NotebookRevisionView(_, None)),
      page.lastOption.filter(_ => hasMore)
        .map(revision => NotebookJson.encodeCursor(revision.revisionNumber.toString)),
      hasMore)
  }

  def get(notebookId: String, revisionNumber: Long): NotebookRevisionView = {
    val revision = load(notebookId, revisionNumber)
    NotebookRevisionView(revision, Some(NotebookJson.readDocument(revision.documentSnapshot)))
  }

  def load(notebookId: String, revisionNumber: Long): NotebookRevision =
    store.getRevision(notebookId, revisionNumber).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"revision $revisionNumber of notebook $notebookId was not found")
    }

  def delete(notebookId: String, revisionNumber: Long): Unit = {
    val revision = load(notebookId, revisionNumber)
    if (revision.protectedRevision) {
      throw NotebookException.invalid("a protected revision cannot be deleted")
    }
    if (!store.deleteRevision(notebookId, revisionNumber)) {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"revision $revisionNumber of notebook $notebookId was not found")
    }
  }
}
