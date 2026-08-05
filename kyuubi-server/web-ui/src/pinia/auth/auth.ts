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

import { defineStore } from 'pinia'
import request from '@/utils/request'
import { getAuthConfig, AuthConfig } from '@/api/auth'
import {
  discover,
  buildAuthorizeUrl,
  buildLogoutUrl,
  exchangeCode,
  refreshTokens,
  ProviderMetadata,
  Tokens
} from '@/utils/oidc'
import {
  generateCodeVerifier,
  randomUrlSafe,
  s256Challenge,
  decodeJwtPayload
} from '@/utils/pkce'

/** Refresh this long before the access token actually expires. */
const REFRESH_SKEW_MS = 30_000

const VERIFIER_KEY = 'kyuubi.oidc.verifier'
const STATE_KEY = 'kyuubi.oidc.state'
const RETURN_TO_KEY = 'kyuubi.oidc.returnTo'

/**
 * Several requests can fail authentication at once; the first one wins the redirect
 * so the others do not overwrite its `state`/verifier mid-flight. Deliberately not
 * part of the persisted store — it only matters until the page navigates away.
 */
let redirectInFlight = false

/** The redirect URI must match exactly what is registered on the provider. */
export function oidcRedirectUri(): string {
  return `${window.location.origin}/ui/callback`
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as string | null,
    authToken: null as string | null,
    isAuthenticated: false,
    refreshToken: null as string | null,
    idToken: null as string | null,
    expiresAt: 0,
    authConfig: null as AuthConfig | null
  }),
  actions: {
    /** Load (once) how this server wants the UI to authenticate. */
    async loadAuthConfig(): Promise<AuthConfig | null> {
      if (this.authConfig) return this.authConfig
      try {
        this.authConfig = await getAuthConfig()
      } catch (e) {
        // An older server without the endpoint simply keeps the basic-auth form.
        this.authConfig = { authType: 'UNKNOWN', oidcEnabled: false }
      }
      return this.authConfig
    },

    async providerMetadata(): Promise<ProviderMetadata> {
      const config = this.authConfig
      if (!config?.issuer) {
        throw new Error(
          'OIDC issuer is not configured on the server (kyuubi.authentication.jwt.issuer)'
        )
      }
      if (!config.clientId) {
        throw new Error(
          'OIDC client id for the Web UI is not configured on the server ' +
            '(kyuubi.authentication.oidc.ui.client.id)'
        )
      }
      return discover(config.issuer)
    },

    /**
     * Start the Authorization Code + PKCE flow by redirecting to the provider.
     * Throws — rather than redirecting — when the provider cannot be reached, so
     * the caller can show the reason instead of bouncing the user into a loop.
     */
    async oidcLogin(): Promise<void> {
      if (redirectInFlight) return
      await this.loadAuthConfig()
      const meta = await this.providerMetadata()
      redirectInFlight = true
      const verifier = generateCodeVerifier()
      const state = randomUrlSafe(16)
      const nonce = randomUrlSafe(16)
      sessionStorage.setItem(VERIFIER_KEY, verifier)
      sessionStorage.setItem(STATE_KEY, state)
      sessionStorage.setItem(
        RETURN_TO_KEY,
        window.location.pathname + window.location.search
      )
      const url = buildAuthorizeUrl(meta, {
        clientId: this.authConfig!.clientId!,
        redirectUri: oidcRedirectUri(),
        scope: this.authConfig!.scope || 'openid profile email',
        state,
        nonce,
        codeChallenge: await s256Challenge(verifier)
      })
      window.location.assign(url)
    },

    /** Complete the flow: validate state, swap the code for tokens. */
    async completeOidcLogin(code: string, state: string): Promise<string> {
      const expectedState = sessionStorage.getItem(STATE_KEY)
      const verifier = sessionStorage.getItem(VERIFIER_KEY)
      sessionStorage.removeItem(STATE_KEY)
      sessionStorage.removeItem(VERIFIER_KEY)
      if (!expectedState || state !== expectedState) {
        throw new Error(
          'OIDC state mismatch — the login attempt was not started by this browser'
        )
      }
      if (!verifier) {
        throw new Error(
          'Missing PKCE code verifier — please start the sign-in again'
        )
      }
      await this.loadAuthConfig()
      const meta = await this.providerMetadata()
      const tokens = await exchangeCode(meta, {
        clientId: this.authConfig!.clientId!,
        code,
        codeVerifier: verifier,
        redirectUri: oidcRedirectUri()
      })
      this.applyTokens(tokens)
      const returnTo = sessionStorage.getItem(RETURN_TO_KEY)
      sessionStorage.removeItem(RETURN_TO_KEY)
      return returnTo && returnTo.startsWith('/') ? returnTo : '/overview'
    },

    applyTokens(tokens: Tokens) {
      this.authToken = `Bearer ${tokens.accessToken}`
      this.refreshToken = tokens.refreshToken
      this.idToken = tokens.idToken
      this.expiresAt = tokens.expiresAt
      this.isAuthenticated = true
      const claims = decodeJwtPayload(tokens.accessToken)
      this.user = claims?.preferred_username || claims?.sub || 'unknown'
    },

    /**
     * Renew the access token when it is expired or about to be. Returns false when
     * no silent renewal is possible, in which case the caller must sign in again.
     */
    async refreshIfNeeded(force = false): Promise<boolean> {
      if (!this.authConfig?.oidcEnabled || !this.refreshToken) return false
      if (!force && this.expiresAt - Date.now() > REFRESH_SKEW_MS) return true
      try {
        const meta = await this.providerMetadata()
        const tokens = await refreshTokens(meta, {
          clientId: this.authConfig!.clientId!,
          refreshToken: this.refreshToken
        })
        this.applyTokens(tokens)
        return true
      } catch (e) {
        this.clearUser()
        return false
      }
    },

    /** Basic-auth sign-in, kept for non-OIDC deployments. */
    async setUser(user: string, password: string) {
      const response = await request({
        url: 'api/v1/ping',
        method: 'get',
        auth: {
          username: user,
          password: password
        }
      })

      if (response) {
        this.user = user
        this.authToken = `Basic ${btoa(user + ':' + password)}`
        this.isAuthenticated = true
      } else {
        throw new Error('Authentication failed')
      }
    },

    clearUser() {
      this.user = null
      this.authToken = null
      this.isAuthenticated = false
      this.refreshToken = null
      this.idToken = null
      this.expiresAt = 0
    },

    /** Sign out locally, and end the provider session too when it supports it. */
    async logout() {
      const oidc = this.authConfig?.oidcEnabled
      const idToken = this.idToken
      const clientId = this.authConfig?.clientId
      this.clearUser()
      if (!oidc || !clientId) return
      try {
        const meta = await this.providerMetadata()
        const url = buildLogoutUrl(meta, {
          clientId,
          idToken,
          redirectUri: `${window.location.origin}/ui`
        })
        if (url) window.location.assign(url)
      } catch (e) {
        // Local sign-out already happened; leaving the provider session is best effort.
      }
    }
  },
  persist: {
    key: 'auth'
  }
})
