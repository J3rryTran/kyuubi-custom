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

import request from '@/utils/request'
import type {
  CellExecution,
  CurrentUser,
  ExecutionLogPage,
  ExecutionResultPage,
  ExecutionSchema,
  Notebook,
  NotebookFolder,
  NotebookPage,
  NotebookPermission,
  NotebookRevision,
  NotebookSession,
  PythonEnvironment,
  PythonEnvironmentRevision,
  PythonPackageList,
  PythonPackageOperation,
  ExecutionOutputPage,
  RuntimeSpec
} from './types'

/**
 * The notebook API is the only surface this view uses; the raw `/sessions` and `/operations`
 * endpoints the old SQL editor calls are deliberately absent, together with the admin endpoint
 * it used for cleanup.
 */
const call = <T>(config: Record<string, unknown>): Promise<T> =>
  request(config) as unknown as Promise<T>

// Folders -----------------------------------------------------------------------------------

export const listFolders = () =>
  call<NotebookFolder[]>({ url: 'api/v1/notebook-folders', method: 'get' })

export const createFolder = (name: string, parentId: string | null) =>
  call<NotebookFolder>({
    url: 'api/v1/notebook-folders',
    method: 'post',
    data: { name, parentId }
  })

export const renameFolder = (folderId: string, name: string, version: number) =>
  call<NotebookFolder>({
    url: `api/v1/notebook-folders/${folderId}`,
    method: 'patch',
    data: { name, version }
  })

export const deleteFolder = (folderId: string) =>
  call<void>({ url: `api/v1/notebook-folders/${folderId}`, method: 'delete' })

// Notebooks ---------------------------------------------------------------------------------

export const listNotebooks = (params: Record<string, unknown> = {}) =>
  call<NotebookPage<Notebook>>({
    url: 'api/v1/notebooks',
    method: 'get',
    params
  })

export const searchNotebooks = (q: string) =>
  call<NotebookPage<Notebook>>({
    url: 'api/v1/notebooks:search',
    method: 'get',
    params: { q, limit: 50 }
  })

export const getNotebook = (notebookId: string) =>
  call<Notebook>({
    url: `api/v1/notebooks/${notebookId}`,
    method: 'get',
    params: { includeCells: true }
  })

export const createNotebook = (name: string, folderId: string | null) =>
  call<Notebook>({
    url: 'api/v1/notebooks',
    method: 'post',
    data: { name, folderId }
  })

export const updateNotebook = (
  notebookId: string,
  data: Record<string, unknown>
) =>
  call<Notebook>({
    url: `api/v1/notebooks/${notebookId}`,
    method: 'patch',
    data
  })

export const deleteNotebook = (notebookId: string) =>
  call<void>({ url: `api/v1/notebooks/${notebookId}`, method: 'delete' })

export const cloneNotebook = (notebookId: string, name: string) =>
  call<Notebook>({
    url: `api/v1/notebooks/${notebookId}:clone`,
    method: 'post',
    data: { name }
  })

export const exportNotebook = (notebookId: string, format: string) =>
  call<unknown>({
    url: `api/v1/notebooks/${notebookId}:export`,
    method: 'get',
    params: { format }
  })

export const importNotebook = (
  name: string,
  content: string,
  format?: string
) =>
  call<Notebook>({
    url: 'api/v1/notebooks:import',
    method: 'post',
    data: { name, content, format }
  })

// Cells -------------------------------------------------------------------------------------

export const createCell = (notebookId: string, data: Record<string, unknown>) =>
  call<Notebook['cells']>({
    url: `api/v1/notebooks/${notebookId}/cells`,
    method: 'post',
    data
  })

export const updateCell = (
  notebookId: string,
  cellId: string,
  data: Record<string, unknown>
) =>
  call<unknown>({
    url: `api/v1/notebooks/${notebookId}/cells/${cellId}`,
    method: 'patch',
    data
  })

export const deleteCell = (notebookId: string, cellId: string) =>
  call<void>({
    url: `api/v1/notebooks/${notebookId}/cells/${cellId}`,
    method: 'delete'
  })

export const reorderCells = (notebookId: string, cellIds: string[]) =>
  call<unknown>({
    url: `api/v1/notebooks/${notebookId}/cells:reorder`,
    method: 'put',
    data: { cellIds }
  })

// Sessions and executions -------------------------------------------------------------------

export const createSession = (notebookId: string) =>
  call<NotebookSession>({
    url: `api/v1/notebooks/${notebookId}/sessions`,
    method: 'post',
    data: {}
  })

export const listSessions = (notebookId: string) =>
  call<NotebookSession[]>({
    url: `api/v1/notebooks/${notebookId}/sessions`,
    method: 'get'
  })

export const restartSession = (sessionId: string) =>
  call<NotebookSession>({
    url: `api/v1/notebook-sessions/${sessionId}:restart`,
    method: 'post'
  })

export const stopSession = (sessionId: string) =>
  call<NotebookSession>({
    url: `api/v1/notebook-sessions/${sessionId}:stop`,
    method: 'post'
  })

export const submitExecution = (
  sessionId: string,
  data: Record<string, unknown>
) =>
  call<CellExecution>({
    url: `api/v1/notebook-sessions/${sessionId}/executions`,
    method: 'post',
    data
  })

export const getExecution = (executionId: string) =>
  call<CellExecution>({
    url: `api/v1/executions/${executionId}`,
    method: 'get'
  })

export const cancelExecution = (executionId: string) =>
  call<CellExecution>({
    url: `api/v1/executions/${executionId}:cancel`,
    method: 'post'
  })

export const closeExecution = (executionId: string) =>
  call<CellExecution>({
    url: `api/v1/executions/${executionId}:close`,
    method: 'post'
  })

export const getExecutionLogs = (executionId: string, offset: number) =>
  call<ExecutionLogPage>({
    url: `api/v1/executions/${executionId}/logs`,
    method: 'get',
    params: { offset, maxLines: 200 }
  })

export const getExecutionSchema = (executionId: string) =>
  call<ExecutionSchema>({
    url: `api/v1/executions/${executionId}/schema`,
    method: 'get'
  })

export const getExecutionResults = (
  executionId: string,
  cursor: string | null,
  maxRows: number
) =>
  call<ExecutionResultPage>({
    url: `api/v1/executions/${executionId}/results`,
    method: 'get',
    params: cursor ? { cursor, maxRows } : { maxRows }
  })

export const listNotebookExecutions = (notebookId: string) =>
  call<CellExecution[]>({
    url: `api/v1/notebooks/${notebookId}/executions`,
    method: 'get',
    params: { limit: 100 }
  })

// Permissions, revisions, service ------------------------------------------------------------

export const listPermissions = (notebookId: string) =>
  call<NotebookPermission[]>({
    url: `api/v1/notebooks/${notebookId}/permissions`,
    method: 'get'
  })

export const setPermissions = (
  notebookId: string,
  permissions: NotebookPermission[]
) =>
  call<NotebookPermission[]>({
    url: `api/v1/notebooks/${notebookId}/permissions`,
    method: 'put',
    data: { permissions }
  })

export const listRevisions = (notebookId: string) =>
  call<NotebookPage<NotebookRevision>>({
    url: `api/v1/notebooks/${notebookId}/revisions`,
    method: 'get',
    params: { limit: 50 }
  })

export const createRevision = (notebookId: string, reason: string) =>
  call<NotebookRevision>({
    url: `api/v1/notebooks/${notebookId}/revisions`,
    method: 'post',
    data: { reason }
  })

export const restoreRevision = (notebookId: string, revisionNumber: number) =>
  call<Notebook>({
    url: `api/v1/notebooks/${notebookId}/revisions/${revisionNumber}:restore`,
    method: 'post'
  })

export const listRuntimeSpecs = () =>
  call<RuntimeSpec[]>({ url: 'api/v1/runtime-specs', method: 'get' })

export const getCurrentUser = () =>
  call<CurrentUser>({ url: 'api/v1/me', method: 'get' })

// Python environments ------------------------------------------------------------------------

export const listPythonEnvironments = () =>
  call<PythonEnvironment[]>({
    url: 'api/v1/python-environments',
    method: 'get'
  })

export const createPythonEnvironment = (name: string) =>
  call<PythonEnvironment>({
    url: 'api/v1/python-environments',
    method: 'post',
    data: { name }
  })

export const deletePythonEnvironment = (environmentId: string) =>
  call<void>({
    url: `api/v1/python-environments/${environmentId}`,
    method: 'delete'
  })

export const listPythonPackages = (environmentId: string) =>
  call<PythonPackageList>({
    url: `api/v1/python-environments/${environmentId}/packages`,
    method: 'get'
  })

export const listPythonRevisions = (environmentId: string) =>
  call<PythonEnvironmentRevision[]>({
    url: `api/v1/python-environments/${environmentId}/revisions`,
    method: 'get'
  })

export const installPythonPackages = (
  environmentId: string,
  packages: string[]
) =>
  call<PythonPackageOperation>({
    url: `api/v1/python-environments/${environmentId}/packages:install`,
    method: 'post',
    data: { packages, clientRequestId: `install-${Date.now()}` }
  })

export const uninstallPythonPackages = (
  environmentId: string,
  packages: string[]
) =>
  call<PythonPackageOperation>({
    url: `api/v1/python-environments/${environmentId}/packages:uninstall`,
    method: 'post',
    data: { packages, clientRequestId: `uninstall-${Date.now()}` }
  })

export const getPackageOperation = (operationId: string) =>
  call<PythonPackageOperation>({
    url: `api/v1/python-package-operations/${operationId}`,
    method: 'get'
  })

export const getPackageOperationLogs = (operationId: string) =>
  call<{ log: string; finished: boolean }>({
    url: `api/v1/python-package-operations/${operationId}/logs`,
    method: 'get'
  })

export const getExecutionOutputs = (
  executionId: string,
  afterSequence: number
) =>
  call<ExecutionOutputPage>({
    url: `api/v1/executions/${executionId}/outputs`,
    method: 'get',
    params: { afterSequence, limit: 200 }
  })
