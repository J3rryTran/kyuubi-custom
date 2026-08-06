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

import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/notebook'
import type {
  CellExecution,
  ExecutionOutput,
  ExecutionSchema,
  Notebook,
  NotebookCell,
  NotebookSession
} from '@/api/notebook/types'
import { TERMINAL_EXECUTION_STATES } from '@/api/notebook/types'

export interface CellOutput {
  logs: string[]
  logOffset: number
  schema?: ExecutionSchema
  rows: string[][]
  cursor: string | null
  hasMore: boolean
  outputs: ExecutionOutput[]
  outputSequence: number
}

const POLL_INTERVAL_MS = 700
const PAGE_ROWS = 100

const isTerminal = (execution: CellExecution) =>
  TERMINAL_EXECUTION_STATES.includes(execution.state)

/**
 * Surfaces the server's error envelope, which carries a message safe to display. Falling back to
 * a generic sentence hides exactly the part the user needs, so the envelope wins whenever present.
 */
export const reportError = (error: unknown, fallback: string) => {
  const envelope = (
    error as { response?: { data?: { error?: { message?: string } } } }
  )?.response?.data?.error
  ElMessage.error(envelope?.message || fallback)
}

/**
 * State and behaviour of one open notebook.
 *
 * Executions are polled rather than pushed, which is what makes a browser refresh survivable:
 * `attachRunning` reloads whatever the server still knows about and resumes from there.
 */
export function useNotebook() {
  const notebook = ref<Notebook | null>(null)
  const cells = ref<NotebookCell[]>([])
  const session = ref<NotebookSession | null>(null)
  const loading = ref(false)
  const pythonEnabled = ref(false)

  /** Latest execution per cell id, and the output collected for it. */
  const executions = reactive<Record<string, CellExecution>>({})
  const outputs = reactive<Record<string, CellOutput>>({})
  const pollers = new Map<string, number>()

  const readOnly = () => notebook.value?.role === 'VIEWER'

  const loadRuntimeSpecs = async () => {
    try {
      const specs = await api.listRuntimeSpecs()
      pythonEnabled.value = specs.some(
        (spec) => spec.language === 'PYTHON' && spec.enabled
      )
    } catch (error) {
      pythonEnabled.value = false
    }
  }

  const open = async (notebookId: string) => {
    loading.value = true
    stopAllPolling()
    Object.keys(executions).forEach((key) => delete executions[key])
    Object.keys(outputs).forEach((key) => delete outputs[key])
    try {
      const loaded = await api.getNotebook(notebookId)
      notebook.value = loaded
      cells.value = loaded.cells || []
      await attachRunning(notebookId)
    } catch (error) {
      reportError(error, 'The notebook could not be opened')
      notebook.value = null
      cells.value = []
    } finally {
      loading.value = false
    }
  }

  /**
   * Reattaches the view to work the server is still tracking. Without this a refresh would
   * silently orphan a running statement and the cell would look idle while it kept running.
   */
  const attachRunning = async (notebookId: string) => {
    try {
      const [history, openSessions] = await Promise.all([
        api.listNotebookExecutions(notebookId),
        api.listSessions(notebookId)
      ])
      session.value =
        openSessions.find((candidate) => candidate.state !== 'STOPPED') || null
      // The list is newest first, so the first hit per cell is the current one.
      history.forEach((execution) => {
        if (execution.cellId && !executions[execution.cellId]) {
          executions[execution.cellId] = execution
          if (!isTerminal(execution)) {
            poll(execution.cellId, execution.id)
          } else {
            void collectOutput(execution.cellId, execution)
          }
        }
      })
    } catch (error) {
      // Not being able to reattach is not fatal; the notebook is still editable.
      reportError(error, 'Running executions could not be restored')
    }
  }

  const ensureSession = async (): Promise<NotebookSession> => {
    if (session.value && session.value.state !== 'STOPPED') return session.value
    const created = await api.createSession(notebook.value!.id)
    session.value = created
    return created
  }

  const runCell = async (cell: NotebookCell, source: string) => {
    try {
      const active = await ensureSession()
      const execution = await api.submitExecution(active.id, {
        cellId: cell.id,
        language: cell.language,
        source,
        // Scoped to this attempt, so a retried click after a network timeout does not run twice.
        clientRequestId: `${cell.id}-${Date.now()}`
      })
      executions[cell.id] = execution
      outputs[cell.id] = {
        logs: [],
        logOffset: 0,
        rows: [],
        cursor: null,
        hasMore: false,
        outputs: [],
        outputSequence: 0
      }
      poll(cell.id, execution.id)
    } catch (error) {
      reportError(error, 'The cell could not be started')
    }
  }

  const stopCell = async (cell: NotebookCell) => {
    const execution = executions[cell.id]
    if (!execution) return
    try {
      executions[cell.id] = await api.cancelExecution(execution.id)
    } catch (error) {
      reportError(error, 'The execution could not be cancelled')
    }
  }

  const poll = (cellId: string, executionId: string) => {
    stopPolling(cellId)
    const timer = window.setInterval(async () => {
      try {
        const execution = await api.getExecution(executionId)
        executions[cellId] = execution
        await appendLogs(cellId, execution)
        if (isTerminal(execution)) {
          stopPolling(cellId)
          await collectOutput(cellId, execution)
        }
      } catch (error) {
        stopPolling(cellId)
        reportError(error, 'The execution status could not be read')
      }
    }, POLL_INTERVAL_MS)
    pollers.set(cellId, timer)
  }

  const stopPolling = (cellId: string) => {
    const timer = pollers.get(cellId)
    if (timer) {
      window.clearInterval(timer)
      pollers.delete(cellId)
    }
  }

  const stopAllPolling = () => {
    pollers.forEach((timer) => window.clearInterval(timer))
    pollers.clear()
  }

  const outputFor = (cellId: string): CellOutput => {
    if (!outputs[cellId]) {
      outputs[cellId] = {
        logs: [],
        logOffset: 0,
        rows: [],
        cursor: null,
        hasMore: false,
        outputs: [],
        outputSequence: 0
      }
    }
    return outputs[cellId]
  }

  /**
   * Rich outputs are pulled from the sequence already shown. The server sanitizes them, and the
   * result component still renders markup inside a sandboxed iframe.
   */
  const appendOutputs = async (cellId: string, execution: CellExecution) => {
    const output = outputFor(cellId)
    try {
      const page = await api.getExecutionOutputs(
        execution.id,
        output.outputSequence
      )
      if (page.outputs.length) {
        output.outputs = output.outputs.concat(page.outputs)
        output.outputSequence = page.lastSequence
      }
    } catch (error) {
      // A runtime without rich output simply has none; that is not worth a message.
    }
  }

  /** Logs are pulled from the offset already shown, so nothing is fetched twice. */
  const appendLogs = async (cellId: string, execution: CellExecution) => {
    const output = outputFor(cellId)
    try {
      const page = await api.getExecutionLogs(execution.id, output.logOffset)
      if (page.lines.length) {
        output.logs = output.logs.concat(page.lines)
        output.logOffset = page.nextOffset
      }
    } catch (error) {
      // Logs disappear once the operation is closed; that is not worth interrupting the user.
    }
  }

  const collectOutput = async (cellId: string, execution: CellExecution) => {
    await appendOutputs(cellId, execution)
    if (execution.state !== 'SUCCEEDED') return
    const output = outputFor(cellId)
    try {
      output.schema = await api.getExecutionSchema(execution.id)
    } catch (error) {
      // A statement without a result set has no schema; the empty state covers it.
      return
    }
    await loadMoreRows(cellId, execution)
  }

  const loadMoreRows = async (cellId: string, execution?: CellExecution) => {
    const current = execution || executions[cellId]
    if (!current) return
    const output = outputFor(cellId)
    try {
      const page = await api.getExecutionResults(
        current.id,
        output.cursor,
        PAGE_ROWS
      )
      output.rows = output.rows.concat(page.rows)
      output.cursor = page.nextCursor
      output.hasMore = page.hasMore
    } catch (error) {
      reportError(error, 'The results could not be read')
    }
  }

  // Cell editing ------------------------------------------------------------------------------

  const reloadCells = async () => {
    if (!notebook.value) return
    const loaded = await api.getNotebook(notebook.value.id)
    notebook.value = loaded
    cells.value = loaded.cells || []
  }

  const saveCell = async (
    cell: NotebookCell,
    changes: Record<string, string>
  ) => {
    try {
      await api.updateCell(cell.notebookId, cell.id, changes)
      await reloadCells()
    } catch (error) {
      reportError(error, 'The cell could not be saved')
      await reloadCells()
    }
  }

  const addCell = async (language: string) => {
    if (!notebook.value) return
    try {
      await api.createCell(notebook.value.id, {
        cellType: language === 'MARKDOWN' ? 'MARKDOWN' : 'CODE',
        language,
        source: ''
      })
      await reloadCells()
    } catch (error) {
      reportError(error, 'The cell could not be added')
    }
  }

  const removeCell = async (cell: NotebookCell) => {
    try {
      await api.deleteCell(cell.notebookId, cell.id)
      stopPolling(cell.id)
      delete executions[cell.id]
      delete outputs[cell.id]
      await reloadCells()
    } catch (error) {
      reportError(error, 'The cell could not be removed')
    }
  }

  // Session controls --------------------------------------------------------------------------

  const restartSession = async () => {
    if (!session.value) return
    try {
      session.value = await api.restartSession(session.value.id)
      ElMessage.success('The session was restarted')
    } catch (error) {
      reportError(error, 'The session could not be restarted')
    }
  }

  const stopSession = async () => {
    if (!session.value) return
    try {
      session.value = await api.stopSession(session.value.id)
      stopAllPolling()
      ElMessage.success('The session was stopped')
    } catch (error) {
      reportError(error, 'The session could not be stopped')
    }
  }

  const dispose = () => stopAllPolling()

  return {
    notebook,
    cells,
    session,
    loading,
    pythonEnabled,
    executions,
    outputs,
    readOnly,
    open,
    loadRuntimeSpecs,
    runCell,
    stopCell,
    loadMoreRows,
    saveCell,
    addCell,
    removeCell,
    reloadCells,
    restartSession,
    stopSession,
    dispose,
    reportError
  }
}
