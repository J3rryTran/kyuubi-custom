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
    v-model="dialogVisible"
    :close-on-click-modal="false"
    width="400px">
    <div class="dialog-header">
      <img class="logo" src="@/assets/images/kyuubi-logo.svg" />
    </div>
    <!--
      Under OIDC this dialog is only reached when the redirect to the provider
      itself failed, so it reports the reason instead of asking for a password.
    -->
    <template v-if="oidcEnabled">
      <p class="sso-hint">Could not reach the single sign-on provider.</p>
      <p v-if="loginError" class="login-error">{{ loginError }}</p>
    </template>
    <el-form v-else class="login-form">
      <el-form-item>
        <el-input v-model="username" placeholder="Username" />
      </el-form-item>
      <el-form-item>
        <el-input v-model="password" type="password" placeholder="Password" />
      </el-form-item>
      <el-form-item>
        <p v-if="loginError" class="login-error">{{ loginError }}</p>
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="dialog-footer">
        <el-button
          v-if="oidcEnabled"
          type="primary"
          :loading="redirecting"
          @click="startOidcLogin"
          >Try again</el-button
        >
        <el-button
          v-else
          type="primary"
          :disabled="isLoginDisabled"
          @click="handleLogin"
          >Log in</el-button
        >
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted } from 'vue'
  import { useAuthStore } from '@/pinia/auth/auth'

  const authStore = useAuthStore()
  const dialogVisible = ref(false)
  const username = ref('')
  const password = ref('')
  const loginError = ref('')
  const oidcEnabled = ref(false)
  const redirecting = ref(false)

  const isLoginDisabled = computed(() => {
    return (
      username.value.trim().length === 0 || password.value.trim().length === 0
    )
  })

  const handleLogin = async () => {
    try {
      await authStore.setUser(username.value, password.value)
      dialogVisible.value = false
    } catch (error) {
      loginError.value = (error as Error).message
    }
  }

  /**
   * Leave for the provider straight away — no intermediate prompt. The dialog is
   * only opened if the redirect could not be started, which would otherwise leave
   * the user on a blank page with no way to see why.
   */
  const startOidcLogin = async () => {
    loginError.value = ''
    redirecting.value = true
    try {
      await authStore.oidcLogin()
      dialogVisible.value = false
    } catch (error) {
      loginError.value = (error as Error).message
      dialogVisible.value = true
    } finally {
      redirecting.value = false
    }
  }

  onMounted(async () => {
    const config = await authStore.loadAuthConfig()
    oidcEnabled.value = !!config?.oidcEnabled
    window.addEventListener('auth-required', () => {
      if (oidcEnabled.value) {
        startOidcLogin()
      } else {
        dialogVisible.value = true
      }
    })
  })
</script>

<style scoped>
  .dialog-header {
    display: flex;
    flex-direction: column;
    align-items: center;
    margin-bottom: 20px;
  }

  .logo {
    width: 100px;
    height: auto;
    margin-bottom: 10px;
  }

  .login-form {
    margin-bottom: 20px;
  }

  .sso-hint {
    text-align: center;
    margin-bottom: 10px;
  }

  .login-error {
    color: red;
    margin-top: 10px;
    text-align: left;
  }

  .dialog-footer {
    text-align: center;
    padding: 15px 20px;
  }
</style>
