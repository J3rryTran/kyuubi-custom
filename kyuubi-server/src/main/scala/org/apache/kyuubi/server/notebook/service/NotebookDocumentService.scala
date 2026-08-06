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
import java.util.UUID

import scala.collection.JavaConverters._

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.api.CellLanguage.CellLanguage
import org.apache.kyuubi.server.notebook.api.CellType.CellType
import org.apache.kyuubi.server.notebook.store._

/**
 * Folder, notebook and cell lifecycle.
 *
 * Two invariants run through the whole class:
 *   - ownership and audit fields come from the authenticated principal, never from the request;
 *   - paths are derived from the owner and the folder chain, so a client cannot place an object
 *     outside its own namespace.
 */
class NotebookDocumentService(
    conf: KyuubiConf,
    store: NotebookStore,
    permissions: NotebookPermissionService,
    revisions: NotebookRevisionService) extends Logging {

  private val maxCellSourceSize = conf.get(NOTEBOOK_CELL_SOURCE_MAX_SIZE)
  private val maxCells = conf.get(NOTEBOOK_MAX_CELLS)
  private val maxPageSize = conf.get(NOTEBOOK_MAX_PAGE_SIZE)

  // -------------------------------------------------------------------------------------------
  // Folders
  // -------------------------------------------------------------------------------------------

  def createFolder(principal: NotebookPrincipal, request: CreateFolderRequest): NotebookFolder = {
    val name = NotebookPaths.validateName(request.getName)
    val parent = Option(request.getParentId).filter(_.nonEmpty).map(loadFolder)
    parent.foreach(permissions.requireFolderAccess(_, principal))
    val parentPath = parent.map(_.path).getOrElse(NotebookPaths.rootPath(principal.user))
    val path = NotebookPaths.childPath(parentPath, name)
    requireFreePath(path)
    val now = System.currentTimeMillis()
    val folder = NotebookFolder(
      id = UUID.randomUUID().toString,
      parentId = parent.map(_.id),
      name = name,
      path = path,
      owner = principal.user,
      createdAt = now,
      createdBy = principal.user,
      updatedAt = now,
      updatedBy = principal.user,
      version = 1L,
      deleted = false)
    store.createFolder(folder)
    folder
  }

  def getFolder(principal: NotebookPrincipal, folderId: String): NotebookFolder = {
    val folder = loadFolder(folderId)
    permissions.requireFolderAccess(folder, principal)
    folder
  }

  def listFolders(
      principal: NotebookPrincipal,
      parentId: Option[String],
      parentIdSpecified: Boolean): Seq[NotebookFolder] =
    store.listFolders(FolderFilter(principal.user, parentId, parentIdSpecified))

  def updateFolder(
      principal: NotebookPrincipal,
      folderId: String,
      request: UpdateFolderRequest): NotebookFolder = {
    val folder = loadFolder(folderId)
    permissions.requireFolderAccess(folder, principal)
    val expectedVersion = expectedVersionOf(request.getVersion, folder.version)
    val name = Option(request.getName).map(NotebookPaths.validateName).getOrElse(folder.name)
    val reparent = Option(request.getParentId)
    val newParent = reparent match {
      case None => folder.parentId.map(loadFolder)
      case Some(value) if value.isEmpty => None
      case Some(value) => Some(loadFolder(value))
    }
    newParent.foreach { parent =>
      permissions.requireFolderAccess(parent, principal)
      if (parent.id == folder.id || parent.path.startsWith(folder.path + NotebookPaths.SEPARATOR)) {
        throw NotebookException.invalid("a folder cannot be moved into itself")
      }
    }
    val parentPath = newParent.map(_.path).getOrElse(NotebookPaths.rootPath(folder.owner))
    val newPath = NotebookPaths.childPath(parentPath, name)
    if (newPath != folder.path) {
      requireFreePath(newPath)
      requireSameOwner(newPath, folder.owner)
    }
    val now = System.currentTimeMillis()
    val updated = folder.copy(
      parentId = newParent.map(_.id),
      name = name,
      path = newPath,
      updatedAt = now,
      updatedBy = principal.user,
      version = expectedVersion + 1)
    val (folderUpdates, notebookUpdates) = subtreeUpdates(folder.path, newPath, folder.owner)
    if (!store.moveFolderSubtree(updated, expectedVersion, folderUpdates, notebookUpdates)) {
      throw NotebookException.versionConflict(s"folder $folderId was modified concurrently")
    }
    updated
  }

  def deleteFolder(
      principal: NotebookPrincipal,
      folderId: String,
      version: Option[Long]): Unit = {
    val folder = loadFolder(folderId)
    permissions.requireFolderAccess(folder, principal)
    val expectedVersion =
      expectedVersionOf(version.map(java.lang.Long.valueOf).orNull, folder.version)
    val descendantFolders = store.listFoldersUnder(folder.path, folder.owner)
    val descendantNotebooks = store.listNotebooksUnder(folder.path, folder.owner)
    val directNotebooks = store.listNotebooks(
      NotebookFilter(owner = Some(folder.owner), folderId = Some(folder.id), limit = maxCells))
    val notebooks = (descendantNotebooks ++ directNotebooks).groupBy(_.id).values.map(_.head).toSeq
    val deleted = store.deleteFolderSubtree(
      folder,
      expectedVersion,
      descendantFolders,
      notebooks,
      principal.user,
      System.currentTimeMillis())
    if (!deleted) {
      throw NotebookException.versionConflict(s"folder $folderId was modified concurrently")
    }
  }

  // -------------------------------------------------------------------------------------------
  // Notebooks
  // -------------------------------------------------------------------------------------------

  def createNotebook(
      principal: NotebookPrincipal,
      request: CreateNotebookRequest): (Notebook, Seq[NotebookCell]) = {
    val name = NotebookPaths.validateName(request.getName)
    val folder = Option(request.getFolderId).filter(_.nonEmpty).map(loadFolder)
    folder.foreach(permissions.requireFolderAccess(_, principal))
    val parentPath = folder.map(_.path).getOrElse(NotebookPaths.rootPath(principal.user))
    val path = NotebookPaths.childPath(parentPath, name)
    requireFreePath(path)
    val now = System.currentTimeMillis()
    val notebookId = UUID.randomUUID().toString
    val requestedCells = Option(request.getCells).map(_.asScala.toSeq).getOrElse(Seq.empty)
    if (requestedCells.size > maxCells) {
      throw NotebookException.invalid(s"a notebook may contain at most $maxCells cells")
    }
    val cells = requestedCells.zipWithIndex.map { case (cellRequest, index) =>
      buildCell(notebookId, index, cellRequest, now)
    }
    val notebook = Notebook(
      id = notebookId,
      folderId = folder.map(_.id),
      path = path,
      name = name,
      description = trimmed(request.getDescription),
      owner = principal.user,
      defaultCatalog = trimmed(request.getDefaultCatalog),
      defaultSchema = trimmed(request.getDefaultSchema),
      runtimeProfile = trimmed(request.getRuntimeProfile),
      formatVersion = NotebookDocument.CURRENT_FORMAT_VERSION,
      createdAt = now,
      createdBy = principal.user,
      updatedAt = now,
      updatedBy = principal.user,
      version = 1L,
      deleted = false)
    store.createNotebook(notebook, cells)
    revisions.recordAutomatic(notebook, cells, principal.user, "created")
    (notebook, cells)
  }

  def getNotebook(principal: NotebookPrincipal, notebookId: String): Notebook = {
    val notebook = loadNotebook(notebookId)
    permissions.requireRead(notebook, principal)
    notebook
  }

  def roleOf(notebook: Notebook, principal: NotebookPrincipal): Option[String] =
    permissions.effectiveRole(notebook, principal).map(_.toString)

  def listCells(notebookId: String): Seq[NotebookCell] = store.listCells(notebookId)

  def listNotebooks(
      principal: NotebookPrincipal,
      owner: Option[String],
      folderId: Option[String],
      nameContains: Option[String],
      search: Option[String],
      language: Option[String],
      cursor: Option[String],
      limit: Option[Int]): NotebookPage[NotebookView] = {
    val pageSize = boundedLimit(limit)
    language.foreach(validateLanguageFilter)
    val filter = NotebookFilter(
      accessibleTo = if (principal.admin) None else Some(principal.user),
      owner = owner,
      folderId = folderId,
      nameContains = nameContains,
      search = search,
      language = language.map(_.toUpperCase),
      afterPath = cursor.map(NotebookJson.decodeCursor),
      // One extra row tells us whether another page exists without a second count query.
      limit = pageSize + 1)
    val found = store.listNotebooks(filter)
    val hasMore = found.size > pageSize
    val page = found.take(pageSize)
    NotebookPage(
      page.map(notebook => NotebookView(notebook, roleOf(notebook, principal))),
      page.lastOption.filter(_ => hasMore).map(notebook =>
        NotebookJson.encodeCursor(notebook.path)),
      hasMore)
  }

  def updateNotebook(
      principal: NotebookPrincipal,
      notebookId: String,
      request: UpdateNotebookRequest): Notebook = {
    val notebook = loadNotebook(notebookId)
    permissions.requireWrite(notebook, principal)
    val expectedVersion = expectedVersionOf(request.getVersion, notebook.version)
    val name = Option(request.getName).map(NotebookPaths.validateName).getOrElse(notebook.name)
    val newPath = if (name == notebook.name) {
      notebook.path
    } else {
      val parentPath =
        notebook.path.substring(0, notebook.path.lastIndexOf(NotebookPaths.SEPARATOR))
      val candidate = NotebookPaths.childPath(parentPath, name)
      requireFreePath(candidate)
      candidate
    }
    val now = System.currentTimeMillis()
    val updated = notebook.copy(
      name = name,
      path = newPath,
      description = optionalUpdate(request.getDescription, notebook.description),
      defaultCatalog = optionalUpdate(request.getDefaultCatalog, notebook.defaultCatalog),
      defaultSchema = optionalUpdate(request.getDefaultSchema, notebook.defaultSchema),
      runtimeProfile = optionalUpdate(request.getRuntimeProfile, notebook.runtimeProfile),
      updatedAt = now,
      updatedBy = principal.user,
      version = expectedVersion + 1)
    if (!store.updateNotebook(updated, expectedVersion)) {
      throw NotebookException.versionConflict(s"notebook $notebookId was modified concurrently")
    }
    revisions.recordAutomatic(updated, store.listCells(notebookId), principal.user, "updated")
    updated
  }

  def deleteNotebook(
      principal: NotebookPrincipal,
      notebookId: String,
      version: Option[Long]): Unit = {
    val notebook = loadNotebook(notebookId)
    permissions.requireOwner(notebook, principal)
    val expectedVersion =
      expectedVersionOf(version.map(java.lang.Long.valueOf).orNull, notebook.version)
    val now = System.currentTimeMillis()
    val tombstonePath = NotebookPaths.tombstonePath(notebook.path, notebook.id)
    val deleted = store.deleteNotebook(
      notebookId,
      expectedVersion,
      Tombstone(tombstonePath, NotebookPaths.hash(tombstonePath), principal.user, now))
    if (!deleted) {
      throw NotebookException.versionConflict(s"notebook $notebookId was modified concurrently")
    }
    // Grants of a deleted notebook are dropped so a later notebook reusing the path cannot
    // inherit them.
    store.deletePermissions(notebookId)
    store.deleteSchedule(notebookId)
  }

  def cloneNotebook(
      principal: NotebookPrincipal,
      notebookId: String,
      request: CloneNotebookRequest): (Notebook, Seq[NotebookCell]) = {
    val source = loadNotebook(notebookId)
    permissions.requireRead(source, principal)
    val cells = store.listCells(notebookId)
    val createRequest = new CreateNotebookRequest
    createRequest.setName(
      Option(request).flatMap(r => Option(r.getName)).getOrElse(source.name + " copy"))
    createRequest.setFolderId(Option(request).map(_.getFolderId).orNull)
    createRequest.setDescription(source.description.orNull)
    createRequest.setDefaultCatalog(source.defaultCatalog.orNull)
    createRequest.setDefaultSchema(source.defaultSchema.orNull)
    createRequest.setRuntimeProfile(source.runtimeProfile.orNull)
    createRequest.setCells(cells.map { cell =>
      val cellRequest = new CreateCellRequest
      cellRequest.setCellType(cell.cellType.toString)
      cellRequest.setLanguage(cell.language.toString)
      cellRequest.setSource(cell.source)
      cellRequest.setMetadata(cell.metadata.asJava)
      cellRequest.setConfiguration(cell.configuration.asJava)
      cellRequest
    }.asJava)
    createNotebook(principal, createRequest)
  }

  def moveNotebook(
      principal: NotebookPrincipal,
      notebookId: String,
      request: MoveNotebookRequest): Notebook = {
    val notebook = loadNotebook(notebookId)
    permissions.requireOwner(notebook, principal)
    val expectedVersion = expectedVersionOf(request.getVersion, notebook.version)
    val name = Option(request.getName).map(NotebookPaths.validateName).getOrElse(notebook.name)
    val folder = Option(request.getFolderId).filter(_.nonEmpty).map(loadFolder)
    folder.foreach(permissions.requireFolderAccess(_, principal))
    val parentPath = folder.map(_.path).getOrElse(NotebookPaths.rootPath(notebook.owner))
    val newPath = NotebookPaths.childPath(parentPath, name)
    requireSameOwner(newPath, notebook.owner)
    if (newPath != notebook.path) {
      requireFreePath(newPath)
    }
    val updated = notebook.copy(
      folderId = folder.map(_.id),
      name = name,
      path = newPath,
      updatedAt = System.currentTimeMillis(),
      updatedBy = principal.user,
      version = expectedVersion + 1)
    if (!store.updateNotebook(updated, expectedVersion)) {
      throw NotebookException.versionConflict(s"notebook $notebookId was modified concurrently")
    }
    updated
  }

  // -------------------------------------------------------------------------------------------
  // Cells
  // -------------------------------------------------------------------------------------------

  def createCell(
      principal: NotebookPrincipal,
      notebookId: String,
      request: CreateCellRequest): NotebookCell = {
    val notebook = loadNotebook(notebookId)
    permissions.requireWrite(notebook, principal)
    val currentCount = store.countCells(notebookId)
    if (currentCount >= maxCells) {
      throw NotebookException.invalid(s"a notebook may contain at most $maxCells cells")
    }
    val position = Option(request.getPosition).map(_.intValue()).getOrElse(currentCount)
    if (position < 0 || position > currentCount) {
      throw NotebookException.invalid(s"position must be between 0 and $currentCount")
    }
    val now = System.currentTimeMillis()
    val cell = buildCell(notebookId, position, request, now)
    if (!store.insertCell(cell, notebook.version, principal.user, now)) {
      throw NotebookException.versionConflict(s"notebook $notebookId was modified concurrently")
    }
    revisions.recordAutomatic(notebook, store.listCells(notebookId), principal.user, "cell added")
    cell
  }

  def getCell(
      principal: NotebookPrincipal,
      notebookId: String,
      cellId: String): NotebookCell = {
    val notebook = loadNotebook(notebookId)
    permissions.requireRead(notebook, principal)
    loadCell(notebookId, cellId)
  }

  def updateCell(
      principal: NotebookPrincipal,
      notebookId: String,
      cellId: String,
      request: UpdateCellRequest): NotebookCell = {
    val notebook = loadNotebook(notebookId)
    permissions.requireWrite(notebook, principal)
    val cell = loadCell(notebookId, cellId)
    val expectedVersion = expectedVersionOf(request.getVersion, cell.version)
    val cellType = Option(request.getCellType)
      .map(value => parseCellType(value)).getOrElse(cell.cellType)
    val language = Option(request.getLanguage)
      .map(value => parseLanguage(value)).getOrElse(cell.language)
    val source = Option(request.getSource).getOrElse(cell.source)
    validateSource(source)
    validateTypeAndLanguage(cellType, language)
    val now = System.currentTimeMillis()
    val updated = cell.copy(
      cellType = cellType,
      language = language,
      source = source,
      metadata = Option(request.getMetadata).map(_.asScala.toMap).getOrElse(cell.metadata),
      configuration =
        Option(request.getConfiguration).map(_.asScala.toMap).getOrElse(cell.configuration),
      updatedAt = now,
      version = expectedVersion + 1)
    val applied =
      store.updateCell(updated, expectedVersion, notebook.version, principal.user, now)
    if (!applied) {
      throw NotebookException.versionConflict(s"cell $cellId was modified concurrently")
    }
    revisions.recordAutomatic(notebook, store.listCells(notebookId), principal.user, "cell updated")
    updated
  }

  def updateCellConfig(
      principal: NotebookPrincipal,
      notebookId: String,
      cellId: String,
      request: UpdateCellConfigRequest): NotebookCell = {
    val updateRequest = new UpdateCellRequest
    updateRequest.setConfiguration(request.getConfiguration)
    updateRequest.setVersion(request.getVersion)
    updateCell(principal, notebookId, cellId, updateRequest)
  }

  def deleteCell(principal: NotebookPrincipal, notebookId: String, cellId: String): Unit = {
    val notebook = loadNotebook(notebookId)
    permissions.requireWrite(notebook, principal)
    loadCell(notebookId, cellId)
    val now = System.currentTimeMillis()
    if (!store.deleteCell(notebookId, cellId, notebook.version, principal.user, now)) {
      throw NotebookException.versionConflict(s"notebook $notebookId was modified concurrently")
    }
    revisions.recordAutomatic(notebook, store.listCells(notebookId), principal.user, "cell removed")
  }

  def reorderCells(
      principal: NotebookPrincipal,
      notebookId: String,
      request: ReorderCellsRequest): Seq[NotebookCell] = {
    val notebook = loadNotebook(notebookId)
    permissions.requireWrite(notebook, principal)
    val expectedVersion = expectedVersionOf(request.getVersion, notebook.version)
    val requested = Option(request.getCellIds).map(_.asScala.toSeq).getOrElse(Seq.empty)
    val existing = store.listCells(notebookId).map(_.id)
    if (requested.toSet != existing.toSet || requested.size != existing.size) {
      throw NotebookException.invalid(
        "cellIds must list every cell of the notebook exactly once")
    }
    val now = System.currentTimeMillis()
    if (!store.reorderCells(notebookId, requested, expectedVersion, principal.user, now)) {
      throw NotebookException.versionConflict(s"notebook $notebookId was modified concurrently")
    }
    val reordered = store.listCells(notebookId)
    revisions.recordAutomatic(notebook, reordered, principal.user, "cells reordered")
    reordered
  }

  // -------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------

  def loadNotebook(notebookId: String): Notebook =
    store.getNotebook(notebookId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"notebook $notebookId was not found")
    }

  def loadFolder(folderId: String): NotebookFolder =
    store.getFolder(folderId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.FOLDER_NOT_FOUND,
        s"folder $folderId was not found")
    }

  private def loadCell(notebookId: String, cellId: String): NotebookCell =
    store.getCell(notebookId, cellId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.CELL_NOT_FOUND,
        s"cell $cellId was not found")
    }

  def boundedLimit(limit: Option[Int]): Int = {
    val requested = limit.getOrElse(50)
    if (requested <= 0) {
      throw NotebookException.invalid("limit must be positive")
    }
    math.min(requested, maxPageSize)
  }

  private[service] def buildCell(
      notebookId: String,
      position: Int,
      request: CreateCellRequest,
      now: Long): NotebookCell = {
    val cellType = parseCellType(Option(request.getCellType).getOrElse(CellType.CODE.toString))
    val language = Option(request.getLanguage).map(parseLanguage).getOrElse {
      if (cellType == CellType.MARKDOWN) CellLanguage.MARKDOWN else CellLanguage.SQL
    }
    val source = Option(request.getSource).getOrElse("")
    validateSource(source)
    validateTypeAndLanguage(cellType, language)
    NotebookCell(
      id = UUID.randomUUID().toString,
      notebookId = notebookId,
      position = position,
      cellType = cellType,
      language = language,
      source = source,
      metadata = Option(request.getMetadata).map(_.asScala.toMap).getOrElse(Map.empty),
      configuration = Option(request.getConfiguration).map(_.asScala.toMap).getOrElse(Map.empty),
      createdAt = now,
      updatedAt = now,
      version = 1L)
  }

  private def validateSource(source: String): Unit = {
    val size = source.getBytes(StandardCharsets.UTF_8).length
    if (size > maxCellSourceSize) {
      throw NotebookException.invalid(
        s"cell source exceeds the $maxCellSourceSize byte limit",
        Map("size" -> size.toString, "limit" -> maxCellSourceSize.toString))
    }
  }

  private def validateTypeAndLanguage(cellType: CellType, language: CellLanguage): Unit = {
    if (cellType == CellType.MARKDOWN && language != CellLanguage.MARKDOWN) {
      throw NotebookException.invalid("a MARKDOWN cell must use the MARKDOWN language")
    }
    if (cellType == CellType.CODE && !CellLanguage.executable.contains(language)) {
      throw new NotebookException(
        NotebookErrorCode.UNSUPPORTED_LANGUAGE,
        s"a CODE cell must use one of ${CellLanguage.executable.mkString(", ")}")
    }
  }

  private def parseCellType(raw: String): CellType =
    CellType.values.find(_.toString.equalsIgnoreCase(raw.trim)).getOrElse {
      throw NotebookException.invalid(s"cellType must be one of ${CellType.values.mkString(", ")}")
    }

  private def parseLanguage(raw: String): CellLanguage =
    CellLanguage.values.find(_.toString.equalsIgnoreCase(raw.trim)).getOrElse {
      throw new NotebookException(
        NotebookErrorCode.UNSUPPORTED_LANGUAGE,
        s"language must be one of ${CellLanguage.values.mkString(", ")}")
    }

  private def validateLanguageFilter(raw: String): Unit = parseLanguage(raw)

  private def requireFreePath(path: String): Unit = {
    val hash = NotebookPaths.hash(path)
    if (store.getFolderByPathHash(hash).isDefined || store.getNotebookByPathHash(hash).isDefined) {
      throw NotebookException.pathConflict(s"$path already exists")
    }
  }

  private def requireSameOwner(path: String, owner: String): Unit = {
    if (!NotebookPaths.ownerOf(path).contains(owner)) {
      throw NotebookException.accessDenied("an object cannot be moved into another user's space")
    }
  }

  /** New paths for everything under `oldPrefix` once the subtree root moves to `newPrefix`. */
  private def subtreeUpdates(
      oldPrefix: String,
      newPrefix: String,
      owner: String): (Seq[PathUpdate], Seq[PathUpdate]) = {
    if (oldPrefix == newPrefix) {
      (Seq.empty, Seq.empty)
    } else {
      val folders = store.listFoldersUnder(oldPrefix, owner)
        .map(f => PathUpdate(f.id, newPrefix + f.path.substring(oldPrefix.length)))
      val notebooks = store.listNotebooksUnder(oldPrefix, owner)
        .map(n => PathUpdate(n.id, newPrefix + n.path.substring(oldPrefix.length)))
      (folders, notebooks)
    }
  }

  private def expectedVersionOf(requested: java.lang.Long, current: Long): Long = {
    Option(requested).map(_.longValue()) match {
      case None => current
      case Some(value) if value == current => value
      case Some(_) => throw NotebookException.versionConflict(
          "the object was modified since it was read")
    }
  }

  private def trimmed(value: String): Option[String] =
    Option(value).map(_.trim).filter(_.nonEmpty)

  /** An absent field leaves the stored value alone; an explicit empty string clears it. */
  private def optionalUpdate(requested: String, current: Option[String]): Option[String] =
    Option(requested) match {
      case None => current
      case Some(value) if value.trim.isEmpty => None
      case Some(value) => Some(value.trim)
    }
}
