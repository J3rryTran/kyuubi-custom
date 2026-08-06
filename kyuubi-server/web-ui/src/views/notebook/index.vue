<!--
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
-->

<template>
  <div class="notebook-page">
    <NotebookSidebar ref="sidebar" @select="openNotebook" />

    <div v-loading="loading" class="notebook-main">
      <template v-if="notebook">
        <div class="notebook-header">
          <div>
            <h3 class="notebook-title">{{ notebook.name }}</h3>
            <span class="notebook-path">{{ notebook.path }}</span>
          </div>
          <div class="notebook-header-actions">
            <el-tag v-if="notebook.role" size="small" effect="plain">
              {{ notebook.role }}
            </el-tag>
            <el-tag
              v-if="session"
              size="small"
              :type="session.state === 'STOPPED' ? 'info' : 'success'"
              effect="plain">
              Session {{ session.state }}
            </el-tag>
            <el-button
              size="small"
              :disabled="!session"
              icon="RefreshRight"
              @click="restartAndAcknowledge">
              Restart
            </el-button>
            <el-button
              size="small"
              :disabled="!session || session.state === 'STOPPED'"
              icon="SwitchButton"
              @click="stopSession">
              Stop
            </el-button>
            <el-button size="small" icon="Clock" @click="openRevisions">
              Revisions
            </el-button>
            <el-button size="small" icon="Share" @click="openPermissions">
              Share
            </el-button>
            <el-button size="small" icon="Box" @click="openEnvironments">
              Python env
            </el-button>
          </div>
        </div>

        <div class="notebook-cells">
          <NotebookCellItem
            v-for="cell in cells"
            :key="cell.id"
            :cell="cell"
            :execution="executions[cell.id]"
            :output="outputs[cell.id]"
            :read-only="readOnly()"
            :python-enabled="pythonEnabled"
            @run="runCell"
            @stop="stopCell"
            @remove="removeCell"
            @save="saveCell"
            @load-more="(cell) => loadMoreRows(cell.id)" />

          <div class="notebook-add">
            <el-button
              size="small"
              :disabled="readOnly()"
              @click="addCell('SQL')">
              + SQL
            </el-button>
            <el-button
              size="small"
              :disabled="readOnly()"
              @click="addCell('PYTHON')">
              + Python
            </el-button>
            <el-button
              size="small"
              :disabled="readOnly()"
              @click="addCell('MARKDOWN')">
              + Markdown
            </el-button>
          </div>
        </div>
      </template>

      <el-empty v-else description="Select or create a notebook" />
    </div>

    <PythonEnvironmentPanel
      ref="environmentPanel"
      :python-enabled="pythonEnabled" />

    <el-dialog v-model="revisionsDialog" title="Revisions" width="620px">
      <el-button size="small" style="margin-bottom: 8px" @click="checkpoint">
        Create checkpoint
      </el-button>
      <el-table :data="revisions" size="small" max-height="360">
        <el-table-column prop="revisionNumber" label="#" width="60" />
        <el-table-column prop="createdBy" label="By" width="120" />
        <el-table-column label="When" width="180">
          <template #default="scope">
            {{ new Date(scope.row.createdAt).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="Reason" />
        <el-table-column label="" width="90">
          <template #default="scope">
            <el-button
              size="small"
              text
              @click="restore(scope.row.revisionNumber)">
              Restore
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="permissionsDialog" title="Share notebook" width="520px">
      <!-- Group principals are refused by the server, so only users are offered here. -->
      <div v-for="(entry, index) in permissions" :key="index" class="grant-row">
        <el-input v-model="entry.principalId" placeholder="User" size="small" />
        <el-select v-model="entry.role" size="small" style="width: 120px">
          <el-option label="Editor" value="EDITOR" />
          <el-option label="Viewer" value="VIEWER" />
        </el-select>
        <el-button
          size="small"
          icon="Delete"
          @click="permissions.splice(index, 1)" />
      </div>
      <el-button size="small" text @click="addGrant">+ Add user</el-button>
      <template #footer>
        <el-button @click="permissionsDialog = false">Cancel</el-button>
        <el-button type="primary" @click="savePermissions">Save</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
  import { onBeforeUnmount, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import NotebookSidebar from './components/NotebookSidebar.vue'
  import NotebookCellItem from './components/NotebookCellItem.vue'
  import PythonEnvironmentPanel from './components/PythonEnvironmentPanel.vue'
  import { useNotebook } from './use-notebook'
  import * as api from '@/api/notebook'
  import type {
    NotebookPermission,
    NotebookRevision
  } from '@/api/notebook/types'

  const {
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
  } = useNotebook()

  const sidebar = ref<InstanceType<typeof NotebookSidebar> | null>(null)
  const environmentPanel = ref<InstanceType<
    typeof PythonEnvironmentPanel
  > | null>(null)

  const openEnvironments = () => environmentPanel.value?.open()
  const revisionsDialog = ref(false)
  const revisions = ref<NotebookRevision[]>([])
  const permissionsDialog = ref(false)
  const permissions = ref<NotebookPermission[]>([])

  const openNotebook = (notebookId: string) => open(notebookId)

  const openRevisions = async () => {
    if (!notebook.value) return
    try {
      revisions.value = (await api.listRevisions(notebook.value.id)).items
      revisionsDialog.value = true
    } catch (error) {
      reportError(error, 'The revisions could not be listed')
    }
  }

  const checkpoint = async () => {
    try {
      const { value } = await ElMessageBox.prompt('Reason', 'Create checkpoint')
      await api.createRevision(notebook.value!.id, value)
      revisions.value = (await api.listRevisions(notebook.value!.id)).items
    } catch (error) {
      if (error !== 'cancel')
        reportError(error, 'The checkpoint could not be created')
    }
  }

  const restore = async (revisionNumber: number) => {
    try {
      await ElMessageBox.confirm(
        'Restoring replaces the current content. The current state stays in the history.',
        'Confirm',
        { type: 'warning' }
      )
      await api.restoreRevision(notebook.value!.id, revisionNumber)
      await reloadCells()
      revisionsDialog.value = false
      ElMessage.success(`Restored revision ${revisionNumber}`)
    } catch (error) {
      if (error !== 'cancel')
        reportError(error, 'The revision could not be restored')
    }
  }

  const openPermissions = async () => {
    if (!notebook.value) return
    try {
      permissions.value = (await api.listPermissions(notebook.value.id)).filter(
        (grant) => grant.role !== 'OWNER'
      )
      permissionsDialog.value = true
    } catch (error) {
      reportError(error, 'The permissions could not be listed')
    }
  }

  const addGrant = () =>
    permissions.value.push({
      principalType: 'USER',
      principalId: '',
      role: 'VIEWER'
    })

  const savePermissions = async () => {
    try {
      await api.setPermissions(
        notebook.value!.id,
        permissions.value.filter((grant) => grant.principalId.trim().length > 0)
      )
      permissionsDialog.value = false
      ElMessage.success('The permissions were saved')
    } catch (error) {
      reportError(error, 'The permissions could not be saved')
    }
  }

  const restartAndAcknowledge = async () => {
    await restartSession()
    // The new runtime binds to whatever is active now, so the warning has been acted on.
    environmentPanel.value?.acknowledgeRestart()
  }

  onMounted(loadRuntimeSpecs)
  onBeforeUnmount(dispose)
</script>

<style scoped>
  .notebook-page {
    display: flex;
    height: calc(100vh - 120px);
  }

  .notebook-main {
    flex: 1;
    overflow: auto;
    padding: 12px 16px;
  }

  .notebook-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 12px;
  }

  .notebook-title {
    margin: 0;
  }

  .notebook-path {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .notebook-header-actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .notebook-add {
    display: flex;
    gap: 6px;
    margin-top: 8px;
  }

  .grant-row {
    display: flex;
    gap: 6px;
    margin-bottom: 6px;
  }
</style>
