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

package org.apache.kyuubi.server.notebook.store

import org.apache.kyuubi.server.notebook.api._

/**
 * Filter for notebook listing and search.
 *
 * `accessibleTo` is the authorization boundary and is applied inside the query rather than after
 * it, so that pagination counts only rows the caller may see. `None` means unrestricted and is
 * only ever passed for an administrator.
 */
case class NotebookFilter(
    accessibleTo: Option[String] = None,
    owner: Option[String] = None,
    folderId: Option[String] = None,
    nameContains: Option[String] = None,
    search: Option[String] = None,
    language: Option[String] = None,
    afterPath: Option[String] = None,
    limit: Int = 50)

case class FolderFilter(
    owner: String,
    parentId: Option[String] = None,
    parentIdSpecified: Boolean = false)

/**
 * Persistence boundary of the notebook subsystem. Every mutating method that takes an
 * `expectedVersion` returns false when the row moved on, which the service layer turns into a
 * `VERSION_CONFLICT`; callers must never retry blindly.
 */
trait NotebookStore extends AutoCloseable {

  def initSchema(): Unit

  /** Cheap liveness probe used by the status endpoint. */
  def healthCheck(): Unit

  def createFolder(folder: NotebookFolder): Unit
  def getFolder(id: String): Option[NotebookFolder]
  def getFolderByPathHash(pathHash: String): Option[NotebookFolder]
  def listFolders(filter: FolderFilter): Seq[NotebookFolder]
  def listFoldersUnder(pathPrefix: String, owner: String): Seq[NotebookFolder]
  def updateFolder(folder: NotebookFolder, expectedVersion: Long): Boolean
  def deleteFolder(id: String, expectedVersion: Long, tombstone: Tombstone): Boolean

  /**
   * Applies a rename or reparent together with the path rewrite of everything below it, as one
   * transaction. Descendant paths are computed by the caller rather than in SQL because string
   * concatenation is spelled differently in every dialect.
   */
  def moveFolderSubtree(
      folder: NotebookFolder,
      expectedVersion: Long,
      folderPathUpdates: Seq[PathUpdate],
      notebookPathUpdates: Seq[PathUpdate]): Boolean

  /** Soft-deletes a folder and everything below it as one transaction. */
  def deleteFolderSubtree(
      folder: NotebookFolder,
      expectedVersion: Long,
      descendantFolders: Seq[NotebookFolder],
      descendantNotebooks: Seq[Notebook],
      updatedBy: String,
      now: Long): Boolean

  def createNotebook(notebook: Notebook, cells: Seq[NotebookCell]): Unit
  def getNotebook(id: String): Option[Notebook]
  def getNotebookByPathHash(pathHash: String): Option[Notebook]
  def listNotebooks(filter: NotebookFilter): Seq[Notebook]
  def listNotebooksUnder(pathPrefix: String, owner: String): Seq[Notebook]
  def updateNotebook(notebook: Notebook, expectedVersion: Long): Boolean
  def deleteNotebook(id: String, expectedVersion: Long, tombstone: Tombstone): Boolean

  def listCells(notebookId: String): Seq[NotebookCell]
  def getCell(notebookId: String, cellId: String): Option[NotebookCell]
  def countCells(notebookId: String): Int

  /**
   * Inserts a cell, shifts the positions of the cells at or after it, and bumps the notebook
   * version, as one transaction.
   */
  def insertCell(cell: NotebookCell, notebookVersion: Long, updatedBy: String, now: Long): Boolean
  def updateCell(
      cell: NotebookCell,
      expectedVersion: Long,
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean
  def deleteCell(
      notebookId: String,
      cellId: String,
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean

  /** Applies a complete ordering; the id list must be a permutation of the notebook's cells. */
  def reorderCells(
      notebookId: String,
      orderedCellIds: Seq[String],
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean

  def createRevision(revision: NotebookRevision): Unit
  def listRevisions(
      notebookId: String,
      limit: Int,
      afterRevisionNumber: Option[Long]): Seq[NotebookRevision]
  def getRevision(notebookId: String, revisionNumber: Long): Option[NotebookRevision]
  def nextRevisionNumber(notebookId: String): Long
  def deleteRevision(notebookId: String, revisionNumber: Long): Boolean

  /** Trims the oldest unprotected revisions, keeping at most `keep` of them. */
  def trimRevisions(notebookId: String, keep: Int): Int

  def listPermissions(notebookId: String): Seq[NotebookPermission]
  def replacePermissions(notebookId: String, permissions: Seq[NotebookPermission]): Unit
  def deletePermissions(notebookId: String): Unit

  def getSchedule(notebookId: String): Option[NotebookSchedule]
  def upsertSchedule(schedule: NotebookSchedule, expectedVersion: Option[Long]): Boolean
  def deleteSchedule(notebookId: String): Boolean
  def listEnabledSchedules(): Seq[NotebookSchedule]

  /** Replaces the whole cell list of a notebook, used by revision restore and import. */
  def replaceCells(
      notebookId: String,
      cells: Seq[NotebookCell],
      notebook: Notebook,
      expectedVersion: Long): Boolean

  // ---------------------------------------------------------------------------------------------
  // Runtime side
  // ---------------------------------------------------------------------------------------------

  def createSession(session: NotebookSession): Unit
  def getSession(id: String): Option[NotebookSession]
  def listSessions(notebookId: String): Seq[NotebookSession]
  def listLiveSessions(): Seq[NotebookSession]
  def updateSession(session: NotebookSession, expectedVersion: Long): Boolean

  def createRuntime(runtime: NotebookRuntime): Unit
  def getRuntime(id: String): Option[NotebookRuntime]
  def listRuntimes(notebookSessionId: String): Seq[NotebookRuntime]
  def updateRuntime(runtime: NotebookRuntime, expectedVersion: Long): Boolean

  def createExecution(execution: CellExecution): Unit
  def getExecution(id: String): Option[CellExecution]

  /** Backs idempotent submission: the same caller and request id must map to one execution. */
  def findExecutionByRequestId(submittedBy: String, clientRequestId: String): Option[CellExecution]
  def listExecutions(filter: ExecutionFilter): Seq[CellExecution]
  def updateExecution(execution: CellExecution, expectedVersion: Long): Boolean

  def appendEvent(event: ExecutionEvent): Unit
  def nextEventSequence(executionId: String): Long
  def listEvents(executionId: String, afterSequence: Long, limit: Int): Seq[ExecutionEvent]

  def createRun(run: NotebookRun): Unit
  def getRun(id: String): Option[NotebookRun]
  def listRuns(notebookId: String, limit: Int): Seq[NotebookRun]
  def updateRun(run: NotebookRun, expectedVersion: Long): Boolean

  // ---------------------------------------------------------------------------------------------
  // Python environments
  // ---------------------------------------------------------------------------------------------

  def createEnvironment(environment: PythonEnvironment): Unit
  def getEnvironment(id: String): Option[PythonEnvironment]
  def listEnvironments(owner: String): Seq[PythonEnvironment]
  def updateEnvironment(environment: PythonEnvironment, expectedVersion: Long): Boolean
  def deleteEnvironment(id: String): Boolean

  def createEnvironmentRevision(revision: PythonEnvironmentRevision): Unit
  def getEnvironmentRevision(id: String): Option[PythonEnvironmentRevision]
  def getEnvironmentRevisionByNumber(
      environmentId: String,
      revisionNumber: Long): Option[PythonEnvironmentRevision]
  def listEnvironmentRevisions(environmentId: String): Seq[PythonEnvironmentRevision]
  def updateEnvironmentRevision(revision: PythonEnvironmentRevision): Boolean
  def deleteEnvironmentRevision(id: String): Boolean
  def nextEnvironmentRevisionNumber(environmentId: String): Long

  def createPackageOperation(operation: PythonPackageOperation): Unit
  def getPackageOperation(id: String): Option[PythonPackageOperation]
  def findPackageOperationByRequestId(
      submittedBy: String,
      clientRequestId: String): Option[PythonPackageOperation]
  def listPackageOperations(environmentId: String, limit: Int): Seq[PythonPackageOperation]
  def updatePackageOperation(operation: PythonPackageOperation, expectedVersion: Long): Boolean

  /** Non-terminal operations, used to fail work that a restart interrupted. */
  def listUnfinishedPackageOperations(): Seq[PythonPackageOperation]
}

/**
 * Filter for execution listing. At least one of the scoping fields is always set by the service
 * layer, so a query can never accidentally read every user's executions.
 */
case class ExecutionFilter(
    notebookId: Option[String] = None,
    notebookSessionId: Option[String] = None,
    runtimeId: Option[String] = None,
    notebookRunId: Option[String] = None,
    states: Set[String] = Set.empty,
    limit: Int = 50)

/**
 * Values written over a soft-deleted row. The path and its hash are rewritten so the live path
 * becomes available again while the row itself is retained for auditing.
 */
case class Tombstone(path: String, pathHash: String, updatedBy: String, updatedAt: Long)

/** New path for one row of a subtree being moved. */
case class PathUpdate(id: String, path: String)
