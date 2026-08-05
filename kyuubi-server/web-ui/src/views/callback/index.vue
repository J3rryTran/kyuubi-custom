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
  <div class="callback-container">
    <template v-if="errorMessage">
      <el-result icon="error" title="Sign-in failed" :sub-title="errorMessage">
        <template #extra>
          <el-button type="primary" @click="retry">Try again</el-button>
        </template>
      </el-result>
    </template>
    <template v-else>
      <p v-loading="true" class="callback-loading">Completing sign-in…</p>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { ref, onMounted } from 'vue'
  import { useRouter } from 'vue-router'
  import { useAuthStore } from '@/pinia/auth/auth'

  const router = useRouter()
  const authStore = useAuthStore()
  const errorMessage = ref('')

  const retry = async () => {
    errorMessage.value = ''
    try {
      await authStore.oidcLogin()
    } catch (error) {
      errorMessage.value = (error as Error).message
    }
  }

  onMounted(async () => {
    const params = new URLSearchParams(window.location.search)
    const error = params.get('error')
    if (error) {
      errorMessage.value = `${error} ${
        params.get('error_description') || ''
      }`.trim()
      return
    }
    const code = params.get('code')
    const state = params.get('state')
    if (!code || !state) {
      errorMessage.value = 'The provider did not return an authorization code'
      return
    }
    try {
      const returnTo = await authStore.completeOidcLogin(code, state)
      // Replace so the code-bearing URL does not stay in the history.
      await router.replace(returnTo)
    } catch (e) {
      errorMessage.value = (e as Error).message
    }
  })
</script>

<style scoped>
  .callback-container {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100vh;
  }

  .callback-loading {
    padding: 40px;
  }
</style>
