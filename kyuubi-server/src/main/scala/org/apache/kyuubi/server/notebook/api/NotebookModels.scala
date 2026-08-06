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

import org.apache.kyuubi.server.notebook.api.CellLanguage.CellLanguage
import org.apache.kyuubi.server.notebook.api.CellType.CellType
import org.apache.kyuubi.server.notebook.api.FailurePolicy.FailurePolicy
import org.apache.kyuubi.server.notebook.api.OverlapPolicy.OverlapPolicy
import org.apache.kyuubi.server.notebook.api.PermissionRole.PermissionRole
import org.apache.kyuubi.server.notebook.api.PrincipalType.PrincipalType

/**
 * Internal domain model of the notebook subsystem. These types are persisted as-is and may carry
 * fields that must never reach a browser; REST responses are built from the `*View` types in
 * [[NotebookViews]] instead of serializing these directly.
 */
case class NotebookFolder(
    id: String,
    parentId: Option[String],
    name: String,
    path: String,
    owner: String,
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long,
    deleted: Boolean)

case class Notebook(
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
    deleted: Boolean)

case class NotebookCell(
    id: String,
    notebookId: String,
    position: Int,
    cellType: CellType,
    language: CellLanguage,
    source: String,
    metadata: Map[String, String],
    configuration: Map[String, String],
    createdAt: Long,
    updatedAt: Long,
    version: Long)

case class NotebookRevision(
    id: String,
    notebookId: String,
    revisionNumber: Long,
    documentSnapshot: String,
    createdAt: Long,
    createdBy: String,
    reason: Option[String],
    protectedRevision: Boolean)

case class NotebookPermission(
    notebookId: String,
    principalType: PrincipalType,
    principalId: String,
    role: PermissionRole,
    createdAt: Long,
    createdBy: String)

case class NotebookSchedule(
    id: String,
    notebookId: String,
    cronExpression: String,
    timezone: String,
    enabled: Boolean,
    runtimeProfile: Option[String],
    failurePolicy: FailurePolicy,
    overlapPolicy: OverlapPolicy,
    lastRunAt: Option[Long],
    nextRunAt: Option[Long],
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long)

/**
 * Portable document form of a notebook, used for revisions, export and import. It deliberately
 * contains no identifiers of the runtime world and no owner information: ownership is always
 * re-derived from the authenticated caller when a document is materialized.
 */
case class NotebookDocument(
    formatVersion: Int,
    name: String,
    description: Option[String],
    defaultCatalog: Option[String],
    defaultSchema: Option[String],
    runtimeProfile: Option[String],
    cells: Seq[NotebookDocumentCell])

case class NotebookDocumentCell(
    position: Int,
    cellType: String,
    language: String,
    source: String,
    metadata: Map[String, String],
    configuration: Map[String, String])

object NotebookDocument {

  /** Bumped when the document layout changes in a way importers must branch on. */
  val CURRENT_FORMAT_VERSION: Int = 1
}
