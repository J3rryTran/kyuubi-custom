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
  <el-dialog
    v-model="visible"
    title="Python environment"
    width="720px"
    @open="reload">
    <div v-if="!pythonEnabled" class="env-unavailable">
      This server has no usable Python runtime, so environments cannot be built.
    </div>

    <template v-else>
      <div class="env-header">
        <el-select
          v-model="selectedId"
          placeholder="Select an environment"
          size="small"
          style="width: 240px"
          @change="reloadSelected">
          <el-option
            v-for="environment in environments"
            :key="environment.id"
            :label="`${environment.name} (${environment.state})`"
            :value="environment.id" />
        </el-select>
        <el-button size="small" icon="Plus" @click="promptCreate"
          >New</el-button
        >
        <el-button
          size="small"
          icon="Delete"
          :disabled="!selectedId"
          @click="removeEnvironment" />
        <span v-if="selected" class="env-meta">
          Python {{ selected.pythonVersion || '?' }} · revision
          {{ selected.activeRevisionNumber ?? '-' }}
        </span>
      </div>

      <!--
        A runtime binds to the revision that was active when it started, so activating a new one
        does not change a running interpreter. Saying so beats letting a user wonder why the
        package they just installed is still missing.
      -->
      <el-alert
        v-if="restartRequired"
        type="warning"
        :closable="false"
        show-icon
        title="Restart the session to pick up the new revision"
        class="env-alert" />

      <div v-if="operation" class="env-operation">
        <el-tag size="small" :type="operationTagType">{{
          operation.state
        }}</el-tag>
        <span
          >{{ operation.action }}
          {{ operation.requestedPackages.join(', ') }}</span
        >
        <span v-if="operation.errorMessage" class="env-error">
          {{ operation.errorMessage }}
        </span>
      </div>

      <el-tabs v-model="tab" type="card">
        <el-tab-pane label="Packages" name="packages">
          <div class="env-install">
            <el-input
              v-model="requirement"
              size="small"
              placeholder="pandas==2.3.1 or numpy>=2,<3"
              @keyup.enter="install" />
            <el-button
              size="small"
              type="primary"
              :disabled="!selectedId || !requirement || busy"
              @click="install">
              Install
            </el-button>
          </div>
          <p class="env-hint">
            Requirement names only. pip options, URLs and paths are rejected by
            the server. Packages marked "image" come with the server and need no
            download.
          </p>

          <el-table :data="packages" size="small" max-height="280">
            <el-table-column prop="name" label="Package" />
            <el-table-column prop="version" label="Version" width="140" />
            <el-table-column label="Source" width="110">
              <template #default="scope">
                <el-tag v-if="scope.row.fromImage" size="small" type="info">
                  image
                </el-tag>
                <el-tag v-else size="small">yours</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="" width="100">
              <template #default="scope">
                <!-- An image package stays importable however the environment is rebuilt. -->
                <el-button
                  v-if="!scope.row.fromImage"
                  size="small"
                  text
                  :disabled="busy"
                  @click="uninstall(scope.row.name)">
                  Remove
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="Revisions" name="revisions">
          <el-table :data="revisions" size="small" max-height="280">
            <el-table-column prop="revisionNumber" label="#" width="60" />
            <el-table-column prop="state" label="State" width="110" />
            <el-table-column label="Active" width="80">
              <template #default="scope">
                <el-tag v-if="scope.row.active" size="small" type="success"
                  >yes</el-tag
                >
              </template>
            </el-table-column>
            <el-table-column label="Requirements">
              <template #default="scope">
                {{ scope.row.requirements.join(', ') || '-' }}
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="Log" name="log">
          <pre v-if="operationLog" class="env-log">{{ operationLog }}</pre>
          <p v-else class="env-hint">No package operation has run yet.</p>
        </el-tab-pane>
      </el-tabs>
    </template>

    <template #footer>
      <el-button @click="visible = false">Close</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import * as api from '@/api/notebook'
  import { reportError } from '../use-notebook'
  import type {
    InstalledPackage,
    PythonEnvironment,
    PythonEnvironmentRevision,
    PythonPackageOperation
  } from '@/api/notebook/types'
  import { TERMINAL_OPERATION_STATES } from '@/api/notebook/types'

  defineProps<{ pythonEnabled: boolean }>()

  const visible = ref(false)
  const environments = ref<PythonEnvironment[]>([])
  const selectedId = ref<string | null>(null)
  const packages = ref<InstalledPackage[]>([])
  const revisions = ref<PythonEnvironmentRevision[]>([])
  const operation = ref<PythonPackageOperation | null>(null)
  const operationLog = ref('')
  const requirement = ref('')
  const tab = ref('packages')
  const busy = ref(false)
  let poller: number | undefined

  const selected = computed(() =>
    environments.value.find(
      (environment) => environment.id === selectedId.value
    )
  )

  /** A finished install means the runtime that is already up is on the previous revision. */
  const restartRequired = ref(false)

  const operationTagType = computed(() => {
    switch (operation.value?.state) {
      case 'SUCCEEDED':
        return 'success'
      case 'FAILED':
        return 'danger'
      case 'CANCELED':
        return 'info'
      default:
        return 'warning'
    }
  })

  const reload = async () => {
    try {
      environments.value = await api.listPythonEnvironments()
      if (!selectedId.value && environments.value.length) {
        selectedId.value = environments.value[0].id
      }
      await reloadSelected()
    } catch (error) {
      reportError(error, 'The environments could not be listed')
    }
  }

  const reloadSelected = async () => {
    if (!selectedId.value) {
      packages.value = []
      revisions.value = []
      return
    }
    try {
      const [packageList, revisionList] = await Promise.all([
        api.listPythonPackages(selectedId.value),
        api.listPythonRevisions(selectedId.value)
      ])
      packages.value = packageList.packages
      revisions.value = revisionList
    } catch (error) {
      reportError(error, 'The environment could not be read')
    }
  }

  const promptCreate = async () => {
    try {
      const { value } = await ElMessageBox.prompt(
        'Environment name',
        'New environment',
        {
          inputPattern: /^[^/\\]+$/,
          inputErrorMessage: 'The name must not be empty or contain / or \\'
        }
      )
      const created = await api.createPythonEnvironment(value.trim())
      selectedId.value = created.id
      await reload()
      ElMessage.success('The environment is being built')
    } catch (error) {
      if (error !== 'cancel')
        reportError(error, 'The environment could not be created')
    }
  }

  const removeEnvironment = async () => {
    if (!selectedId.value) return
    try {
      await ElMessageBox.confirm(
        'Deleting an environment removes every revision on disk.',
        'Confirm',
        { type: 'warning' }
      )
      await api.deletePythonEnvironment(selectedId.value)
      selectedId.value = null
      await reload()
    } catch (error) {
      if (error !== 'cancel')
        reportError(error, 'The environment could not be deleted')
    }
  }

  const install = async () => {
    if (!selectedId.value || !requirement.value) return
    try {
      busy.value = true
      operation.value = await api.installPythonPackages(selectedId.value, [
        requirement.value.trim()
      ])
      requirement.value = ''
      pollOperation()
    } catch (error) {
      busy.value = false
      reportError(error, 'The package could not be installed')
    }
  }

  const uninstall = async (name: string) => {
    if (!selectedId.value) return
    try {
      busy.value = true
      operation.value = await api.uninstallPythonPackages(selectedId.value, [
        name
      ])
      pollOperation()
    } catch (error) {
      busy.value = false
      reportError(error, 'The package could not be removed')
    }
  }

  /** Builds run in the background, so progress is polled rather than awaited. */
  const pollOperation = () => {
    stopPolling()
    poller = window.setInterval(async () => {
      if (!operation.value) return
      try {
        operation.value = await api.getPackageOperation(operation.value.id)
        const logs = await api.getPackageOperationLogs(operation.value.id)
        operationLog.value = logs.log
        if (TERMINAL_OPERATION_STATES.includes(operation.value.state)) {
          stopPolling()
          busy.value = false
          if (operation.value.state === 'SUCCEEDED')
            restartRequired.value = true
          await reloadSelected()
        }
      } catch (error) {
        stopPolling()
        busy.value = false
        reportError(error, 'The operation status could not be read')
      }
    }, 1500)
  }

  const stopPolling = () => {
    if (poller) {
      window.clearInterval(poller)
      poller = undefined
    }
  }

  const open = () => {
    visible.value = true
  }

  const acknowledgeRestart = () => {
    restartRequired.value = false
  }

  onBeforeUnmount(stopPolling)

  defineExpose({ open, acknowledgeRestart })
</script>

<style scoped>
  .env-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
  }

  .env-meta {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .env-alert {
    margin-bottom: 10px;
  }

  .env-unavailable {
    color: var(--el-color-warning);
  }

  .env-install {
    display: flex;
    gap: 8px;
  }

  .env-hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    margin: 6px 0;
  }

  .env-operation {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    margin-bottom: 8px;
  }

  .env-error {
    color: var(--el-color-danger);
  }

  .env-log {
    max-height: 280px;
    overflow: auto;
    font-family: monospace;
    font-size: 12px;
    white-space: pre-wrap;
    margin: 0;
  }
</style>
