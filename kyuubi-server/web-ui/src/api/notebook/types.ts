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

export type CellType = 'CODE' | 'MARKDOWN'
export type CellLanguage = 'SQL' | 'PYTHON' | 'MARKDOWN'

export type ExecutionState =
  | 'QUEUED'
  | 'STARTING'
  | 'RUNNING'
  | 'CANCELING'
  | 'CANCELED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CLOSED'
  | 'LOST'

/** States after which nothing more will happen, so polling must stop. */
export const TERMINAL_EXECUTION_STATES: ExecutionState[] = [
  'CANCELED',
  'SUCCEEDED',
  'FAILED',
  'CLOSED',
  'LOST'
]

export interface NotebookFolder {
  id: string
  parentId: string | null
  name: string
  path: string
  owner: string
  version: number
}

export interface NotebookCell {
  id: string
  notebookId: string
  position: number
  cellType: CellType
  language: CellLanguage
  source: string
  metadata: Record<string, string>
  configuration: Record<string, string>
  version: number
}

export interface Notebook {
  id: string
  folderId: string | null
  path: string
  name: string
  description: string | null
  owner: string
  role: string | null
  version: number
  cells?: NotebookCell[]
}

export interface NotebookPage<T> {
  items: T[]
  nextCursor: string | null
  hasMore: boolean
}

export interface NotebookSession {
  id: string
  notebookId: string
  owner: string
  state: string
  version: number
}

export interface CellExecution {
  id: string
  notebookId: string
  notebookSessionId: string
  runtimeId: string
  cellId: string | null
  language: CellLanguage
  source: string
  state: ExecutionState
  submittedAt: number
  startedAt: number | null
  finishedAt: number | null
  errorCode: string | null
  errorMessage: string | null
}

export interface ExecutionLogPage {
  lines: string[]
  nextOffset: number
  hasMore: boolean
}

export interface ColumnSchema {
  name: string
  dataType: string
  position: number
}

export interface ExecutionSchema {
  columns: ColumnSchema[]
}

export interface ExecutionResultPage {
  rows: string[][]
  nextCursor: string | null
  hasMore: boolean
}

export interface NotebookPermission {
  principalType: string
  principalId: string
  role: string
}

export interface NotebookRevision {
  revisionNumber: number
  createdAt: number
  createdBy: string
  reason: string | null
  protectedRevision: boolean
}

export interface RuntimeSpec {
  id: string
  displayName: string
  language: string
  version: string
  enabled: boolean
}

export interface CurrentUser {
  user: string
  admin: boolean
}

export interface PythonEnvironment {
  id: string
  owner: string
  name: string
  runtimeSpecId: string
  pythonVersion: string | null
  activeRevisionNumber: number | null
  state: string
  version: number
}

export interface InstalledPackage {
  name: string
  version: string
  /** Provided by the server image and shared by every environment; not removable by a user. */
  fromImage: boolean
}

export interface PythonPackageList {
  revisionNumber: number | null
  packages: InstalledPackage[]
  requirements: string[]
}

export interface PythonEnvironmentRevision {
  revisionNumber: number
  state: string
  requirements: string[]
  resolvedPackages: string[]
  createdAt: number
  createdBy: string
  failureMessage: string | null
  active: boolean
}

export interface PythonPackageOperation {
  id: string
  environmentId: string
  action: string
  requestedPackages: string[]
  state: string
  errorCode: string | null
  errorMessage: string | null
}

export const TERMINAL_OPERATION_STATES = ['SUCCEEDED', 'FAILED', 'CANCELED']

export interface ExecutionOutput {
  sequence: number
  outputType: string
  stream: string | null
  mimeType: string
  data: string
}

export interface ExecutionOutputPage {
  outputs: ExecutionOutput[]
  lastSequence: number
  hasMore: boolean
}
