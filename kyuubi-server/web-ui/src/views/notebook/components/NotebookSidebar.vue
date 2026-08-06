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
  <div class="sidebar">
    <div class="sidebar-actions">
      <el-button size="small" icon="Document" @click="promptNotebook">
        Notebook
      </el-button>
      <el-button size="small" icon="FolderAdd" @click="promptFolder">
        Folder
      </el-button>
      <el-button size="small" icon="Upload" @click="importDialog = true" />
    </div>

    <el-input
      v-model="query"
      size="small"
      clearable
      placeholder="Search name, description, cell source"
      @keyup.enter="runSearch"
      @clear="reload" />

    <el-tree
      class="sidebar-tree"
      :data="tree"
      node-key="key"
      :expand-on-click-node="false"
      :highlight-current="true"
      default-expand-all
      @node-click="onNodeClick">
      <template #default="{ data }">
        <span class="sidebar-node">
          <el-icon>
            <component :is="data.isFolder ? 'Folder' : 'Document'" />
          </el-icon>
          <span class="sidebar-label">{{ data.label }}</span>
          <el-dropdown
            trigger="click"
            @command="(c: string) => onCommand(c, data)">
            <el-icon class="sidebar-more"><MoreFilled /></el-icon>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename">Rename</el-dropdown-item>
                <el-dropdown-item v-if="!data.isFolder" command="clone">
                  Clone
                </el-dropdown-item>
                <el-dropdown-item v-if="!data.isFolder" command="export">
                  Export
                </el-dropdown-item>
                <el-dropdown-item command="delete" divided
                  >Delete</el-dropdown-item
                >
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </span>
      </template>
    </el-tree>

    <el-dialog v-model="importDialog" title="Import notebook" width="520px">
      <el-input v-model="importName" placeholder="Name (optional)" />
      <el-input
        v-model="importContent"
        type="textarea"
        :rows="10"
        class="import-body"
        placeholder="Paste a Kyuubi notebook or a Jupyter .ipynb document" />
      <template #footer>
        <el-button @click="importDialog = false">Cancel</el-button>
        <el-button type="primary" :disabled="!importContent" @click="runImport">
          Import
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import * as api from '@/api/notebook'
  import { reportError } from '../use-notebook'
  import type { Notebook, NotebookFolder } from '@/api/notebook/types'

  /**
   * Mirrors the server's rule: a name may not be blank and may not contain a path separator,
   * because the path is derived from it. Checking here turns a 400 round-trip into a message
   * next to the input.
   */
  const NAME_RULES = {
    inputPattern: /^[^/\\]+$/,
    inputErrorMessage: 'The name must not be empty or contain / or \\'
  }

  const emit = defineEmits<{ (e: 'select', notebookId: string): void }>()

  const folders = ref<NotebookFolder[]>([])
  const notebooks = ref<Notebook[]>([])
  const query = ref('')
  const importDialog = ref(false)
  const importName = ref('')
  const importContent = ref('')

  interface TreeNode {
    key: string
    label: string
    isFolder: boolean
    id: string
    version: number
    children?: TreeNode[]
  }

  /** Folders nest by parentId; notebooks hang off their folder, or off the root when loose. */
  const tree = computed<TreeNode[]>(() => {
    const byId = new Map<string, TreeNode>()
    folders.value.forEach((folder) =>
      byId.set(folder.id, {
        key: `f-${folder.id}`,
        label: folder.name,
        isFolder: true,
        id: folder.id,
        version: folder.version,
        children: []
      })
    )
    const roots: TreeNode[] = []
    folders.value.forEach((folder) => {
      const node = byId.get(folder.id)!
      const parent = folder.parentId ? byId.get(folder.parentId) : undefined
      if (parent) parent.children!.push(node)
      else roots.push(node)
    })
    notebooks.value.forEach((item) => {
      const node: TreeNode = {
        key: `n-${item.id}`,
        label: item.name,
        isFolder: false,
        id: item.id,
        version: item.version
      }
      const parent = item.folderId ? byId.get(item.folderId) : undefined
      if (parent) parent.children!.push(node)
      else roots.push(node)
    })
    return roots
  })

  const reload = async () => {
    const [loadedFolders, page] = await Promise.all([
      api.listFolders(),
      api.listNotebooks({ limit: 200 })
    ])
    folders.value = loadedFolders
    notebooks.value = page.items
  }

  const runSearch = async () => {
    if (!query.value) {
      await reload()
      return
    }
    const page = await api.searchNotebooks(query.value)
    // A search result is a flat list, so the folders are dropped to avoid implying a hierarchy.
    folders.value = []
    notebooks.value = page.items
  }

  const onNodeClick = (data: TreeNode) => {
    if (!data.isFolder) emit('select', data.id)
  }

  const promptNotebook = async () => {
    try {
      const { value } = await ElMessageBox.prompt(
        'Notebook name',
        'New notebook',
        NAME_RULES
      )
      await api.createNotebook(value.trim(), null)
      await reload()
    } catch (error) {
      if (error !== 'cancel')
        reportError(error, 'The notebook could not be created')
    }
  }

  const promptFolder = async () => {
    try {
      const { value } = await ElMessageBox.prompt(
        'Folder name',
        'New folder',
        NAME_RULES
      )
      await api.createFolder(value.trim(), null)
      await reload()
    } catch (error) {
      if (error !== 'cancel')
        reportError(error, 'The folder could not be created')
    }
  }

  const onCommand = async (command: string, data: TreeNode) => {
    try {
      switch (command) {
        case 'rename': {
          const { value } = await ElMessageBox.prompt('New name', 'Rename', {
            inputValue: data.label,
            ...NAME_RULES
          })
          if (data.isFolder)
            await api.renameFolder(data.id, value, data.version)
          else
            await api.updateNotebook(data.id, {
              name: value,
              version: data.version
            })
          break
        }
        case 'clone': {
          const { value } = await ElMessageBox.prompt(
            'Name of the copy',
            'Clone',
            {
              inputValue: `${data.label} copy`,
              ...NAME_RULES
            }
          )
          await api.cloneNotebook(data.id, value)
          break
        }
        case 'export': {
          const document = await api.exportNotebook(data.id, 'KYUUBI')
          download(`${data.label}.json`, JSON.stringify(document, null, 2))
          return
        }
        case 'delete': {
          await ElMessageBox.confirm(
            data.isFolder
              ? 'Deleting a folder also deletes everything inside it.'
              : 'Delete this notebook?',
            'Confirm',
            { type: 'warning' }
          )
          if (data.isFolder) await api.deleteFolder(data.id)
          else await api.deleteNotebook(data.id)
          break
        }
      }
      await reload()
    } catch (error) {
      if (error !== 'cancel') reportError(error, 'The action failed')
    }
  }

  const runImport = async () => {
    try {
      await api.importNotebook(importName.value, importContent.value)
      importDialog.value = false
      importContent.value = ''
      importName.value = ''
      await reload()
    } catch (error) {
      reportError(error, 'The document could not be imported')
    }
  }

  const download = (filename: string, content: string) => {
    const blob = new Blob([content], { type: 'application/json' })
    const anchor = document.createElement('a')
    anchor.href = URL.createObjectURL(blob)
    anchor.download = filename
    anchor.click()
    URL.revokeObjectURL(anchor.href)
  }

  onMounted(reload)

  defineExpose({ reload })
</script>

<style scoped>
  .sidebar {
    width: 280px;
    border-right: 1px solid var(--el-border-color-lighter);
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    overflow: auto;
  }

  .sidebar-actions {
    display: flex;
    gap: 6px;
  }

  .sidebar-tree {
    flex: 1;
  }

  .sidebar-node {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
  }

  .sidebar-label {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .sidebar-more {
    visibility: hidden;
  }

  .sidebar-node:hover .sidebar-more {
    visibility: visible;
  }

  .import-body {
    margin-top: 8px;
  }
</style>
