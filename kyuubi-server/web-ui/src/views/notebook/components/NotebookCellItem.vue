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
  <div class="cell" :class="{ 'cell-running': isRunning }">
    <div class="cell-toolbar">
      <el-select
        v-model="localLanguage"
        size="small"
        style="width: 120px"
        :disabled="readOnly"
        @change="onLanguageChange">
        <el-option label="SQL" value="SQL" />
        <el-option label="Python" value="PYTHON" />
        <el-option label="Markdown" value="MARKDOWN" />
      </el-select>

      <span v-if="pythonUnavailable" class="cell-note">
        Python execution is not available yet
      </span>

      <div class="cell-toolbar-right">
        <el-button
          v-if="isRunning"
          size="small"
          type="warning"
          icon="VideoPause"
          @click="$emit('stop', cell)">
          Stop
        </el-button>
        <el-button
          v-else-if="isExecutable"
          size="small"
          type="success"
          icon="VideoPlay"
          :disabled="readOnly || pythonUnavailable"
          @click="$emit('run', cell, localSource)">
          Run
        </el-button>
        <el-button
          size="small"
          icon="Delete"
          :disabled="readOnly"
          @click="$emit('remove', cell)" />
      </div>
    </div>

    <!--
      A textarea rather than one Monaco instance per cell: a notebook can hold dozens of cells,
      and the shared editor component reformats its content with a SQL formatter, which would
      mangle Python and Markdown.
    -->
    <el-input
      v-model="localSource"
      type="textarea"
      class="cell-source"
      :autosize="{ minRows: 3, maxRows: 24 }"
      :readonly="readOnly"
      :placeholder="placeholder"
      @change="onSourceChange" />

    <div
      v-if="cell.cellType === 'MARKDOWN' && localSource"
      class="cell-markdown-preview">
      {{ localSource }}
    </div>

    <CellResult
      v-if="execution"
      :execution="execution"
      :schema="output?.schema"
      :rows="output?.rows"
      :has-more="output?.hasMore"
      :logs="output?.logs"
      :outputs="output?.outputs"
      @load-more="$emit('load-more', cell)" />
  </div>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import CellResult from './CellResult.vue'
  import type { CellExecution, NotebookCell } from '@/api/notebook/types'
  import { TERMINAL_EXECUTION_STATES } from '@/api/notebook/types'
  import type { CellOutput } from '../use-notebook'

  const props = defineProps<{
    cell: NotebookCell
    execution?: CellExecution
    output?: CellOutput
    readOnly: boolean
    pythonEnabled: boolean
  }>()

  const emit = defineEmits<{
    (e: 'run', cell: NotebookCell, source: string): void
    (e: 'stop', cell: NotebookCell): void
    (e: 'remove', cell: NotebookCell): void
    (e: 'load-more', cell: NotebookCell): void
    (e: 'save', cell: NotebookCell, changes: Record<string, string>): void
  }>()

  const localSource = ref(props.cell.source)
  const localLanguage = ref<string>(props.cell.language)

  // The list is re-fetched after a save or a restore, so the local copy follows the server's.
  watch(
    () => props.cell.source,
    (value) => {
      if (value !== localSource.value) localSource.value = value
    }
  )
  watch(
    () => props.cell.language,
    (value) => {
      localLanguage.value = value
    }
  )

  const isRunning = computed(
    () =>
      !!props.execution &&
      !TERMINAL_EXECUTION_STATES.includes(props.execution.state)
  )

  const isExecutable = computed(() => props.cell.cellType === 'CODE')

  const pythonUnavailable = computed(
    () => localLanguage.value === 'PYTHON' && !props.pythonEnabled
  )

  const placeholder = computed(() =>
    localLanguage.value === 'MARKDOWN' ? '# Markdown' : 'select 1'
  )

  const onSourceChange = () => {
    if (localSource.value !== props.cell.source) {
      emit('save', props.cell, { source: localSource.value })
    }
  }

  const onLanguageChange = (value: string) => {
    // A markdown cell must carry the markdown language, and vice versa; the server enforces it,
    // so the pair is sent together rather than letting the server reject a half-change.
    const cellType = value === 'MARKDOWN' ? 'MARKDOWN' : 'CODE'
    emit('save', props.cell, { language: value, cellType })
  }
</script>

<style scoped>
  .cell {
    border: 1px solid var(--el-border-color);
    border-radius: 4px;
    margin-bottom: 12px;
    background: var(--el-bg-color);
  }

  .cell-running {
    border-color: var(--el-color-success);
  }

  .cell-toolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 8px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .cell-toolbar-right {
    margin-left: auto;
    display: flex;
    gap: 6px;
  }

  .cell-note {
    color: var(--el-color-warning);
    font-size: 12px;
  }

  .cell-source :deep(textarea) {
    font-family: monospace;
    border: none;
    box-shadow: none;
    resize: none;
  }

  .cell-markdown-preview {
    padding: 8px 12px;
    border-top: 1px dashed var(--el-border-color-lighter);
    white-space: pre-wrap;
    color: var(--el-text-color-regular);
  }
</style>
