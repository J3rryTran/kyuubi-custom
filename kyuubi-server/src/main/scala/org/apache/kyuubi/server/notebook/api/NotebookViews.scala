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

package org.apache.kyuubi.server.notebook.api

/**
 * Browser-facing representations. Enum-valued fields are plain strings so that the JSON contract
 * does not depend on how Jackson happens to encode a Scala `Enumeration`.
 *
 * Nothing here may carry an internal runtime handle, Kyuubi session or operation identifier,
 * kernel id, process id, filesystem path or credential.
 */
case class NotebookFolderView(
    id: String,
    parentId: Option[String],
    name: String,
    path: String,
    owner: String,
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long)

object NotebookFolderView {
  def apply(folder: NotebookFolder): NotebookFolderView = NotebookFolderView(
    folder.id,
    folder.parentId,
    folder.name,
    folder.path,
    folder.owner,
    folder.createdAt,
    folder.createdBy,
    folder.updatedAt,
    folder.updatedBy,
    folder.version)
}

case class NotebookView(
    id: String,
    folderId: Option[String],
    path: String,
    name: String,
    description: Option[String],
    owner: String,
    defaultCatalog: Option[String],
    defaultSchema: Option[String],
    runtimeProfile: Option[String],
    formatVersion: Int,
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long,
    role: Option[String],
    cells: Option[Seq[NotebookCellView]])

object NotebookView {

  def apply(notebook: Notebook, role: Option[String]): NotebookView =
    apply(notebook, role, None)

  def apply(
      notebook: Notebook,
      role: Option[String],
      cells: Option[Seq[NotebookCellView]]): NotebookView = NotebookView(
    notebook.id,
    notebook.folderId,
    notebook.path,
    notebook.name,
    notebook.description,
    notebook.owner,
    notebook.defaultCatalog,
    notebook.defaultSchema,
    notebook.runtimeProfile,
    notebook.formatVersion,
    notebook.createdAt,
    notebook.createdBy,
    notebook.updatedAt,
    notebook.updatedBy,
    notebook.version,
    role,
    cells)
}

case class NotebookCellView(
    id: String,
    notebookId: String,
    position: Int,
    cellType: String,
    language: String,
    source: String,
    metadata: Map[String, String],
    configuration: Map[String, String],
    createdAt: Long,
    updatedAt: Long,
    version: Long)

object NotebookCellView {
  def apply(cell: NotebookCell): NotebookCellView = NotebookCellView(
    cell.id,
    cell.notebookId,
    cell.position,
    cell.cellType.toString,
    cell.language.toString,
    cell.source,
    cell.metadata,
    cell.configuration,
    cell.createdAt,
    cell.updatedAt,
    cell.version)
}

case class NotebookRevisionView(
    revisionNumber: Long,
    notebookId: String,
    createdAt: Long,
    createdBy: String,
    reason: Option[String],
    protectedRevision: Boolean,
    document: Option[NotebookDocument])

object NotebookRevisionView {
  def apply(revision: NotebookRevision, document: Option[NotebookDocument]): NotebookRevisionView =
    NotebookRevisionView(
      revision.revisionNumber,
      revision.notebookId,
      revision.createdAt,
      revision.createdBy,
      revision.reason,
      revision.protectedRevision,
      document)
}

case class NotebookPermissionView(
    principalType: String,
    principalId: String,
    role: String,
    createdAt: Long,
    createdBy: String)

object NotebookPermissionView {
  def apply(permission: NotebookPermission): NotebookPermissionView = NotebookPermissionView(
    permission.principalType.toString,
    permission.principalId,
    permission.role.toString,
    permission.createdAt,
    permission.createdBy)
}

case class NotebookScheduleView(
    notebookId: String,
    cronExpression: String,
    timezone: String,
    enabled: Boolean,
    runtimeProfile: Option[String],
    failurePolicy: String,
    overlapPolicy: String,
    lastRunAt: Option[Long],
    nextRunAt: Option[Long],
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long)

object NotebookScheduleView {
  def apply(schedule: NotebookSchedule): NotebookScheduleView = NotebookScheduleView(
    schedule.notebookId,
    schedule.cronExpression,
    schedule.timezone,
    schedule.enabled,
    schedule.runtimeProfile,
    schedule.failurePolicy.toString,
    schedule.overlapPolicy.toString,
    schedule.lastRunAt,
    schedule.nextRunAt,
    schedule.createdAt,
    schedule.createdBy,
    schedule.updatedAt,
    schedule.updatedBy,
    schedule.version)
}

/** Cursor-paginated envelope. `nextCursor` is opaque and must not be parsed by clients. */
case class NotebookPage[T](items: Seq[T], nextCursor: Option[String], hasMore: Boolean)

case class CurrentUserView(
    user: String,
    admin: Boolean,
    permissions: UserPermissionsView)

case class UserPermissionsView(
    manageNotebooks: Boolean,
    manageRuntimes: Boolean,
    managePythonEnvironments: Boolean)

case class NotebookStatusView(
    notebookService: String,
    persistence: String,
    kyuubiSql: String,
    pythonRuntimeManager: String,
    activeSessions: Int,
    activeRuntimes: Int,
    queuedExecutions: Int)
