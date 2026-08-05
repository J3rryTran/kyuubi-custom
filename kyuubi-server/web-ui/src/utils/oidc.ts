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

/**
 * Browser-side OAuth2 Authorization Code + PKCE against the OIDC provider.
 *
 * The Web UI is a public client: it never holds a client secret, so PKCE is what
 * binds the redirect back to this browser. Requests go straight to the provider,
 * which therefore must allow this origin in its CORS/Web-origins settings.
 */

export interface ProviderMetadata {
  authorization_endpoint: string
  token_endpoint: string
  end_session_endpoint?: string
}

export interface Tokens {
  accessToken: string
  refreshToken: string | null
  idToken: string | null
  /** Absolute epoch millis at which the access token expires. */
  expiresAt: number
}

function discoveryUrl(issuer: string): string {
  const base = issuer.replace(/\/+$/, '')
  return `${base}/.well-known/openid-configuration`
}

export async function discover(issuer: string): Promise<ProviderMetadata> {
  const res = await fetch(discoveryUrl(issuer), { credentials: 'omit' })
  if (!res.ok) {
    throw new Error(
      `OIDC discovery failed (HTTP ${res.status}) at ${discoveryUrl(issuer)}`
    )
  }
  const meta = await res.json()
  if (!meta.authorization_endpoint || !meta.token_endpoint) {
    throw new Error(
      'OIDC discovery document is missing authorization/token endpoint'
    )
  }
  return meta
}

export function buildAuthorizeUrl(
  meta: ProviderMetadata,
  params: {
    clientId: string
    redirectUri: string
    scope: string
    state: string
    nonce: string
    codeChallenge: string
  }
): string {
  const query = new URLSearchParams({
    response_type: 'code',
    client_id: params.clientId,
    redirect_uri: params.redirectUri,
    scope: params.scope,
    state: params.state,
    nonce: params.nonce,
    code_challenge: params.codeChallenge,
    code_challenge_method: 'S256'
  })
  return `${meta.authorization_endpoint}?${query.toString()}`
}

async function postToken(
  meta: ProviderMetadata,
  form: Record<string, string>,
  what: string
): Promise<Tokens> {
  const res = await fetch(meta.token_endpoint, {
    method: 'POST',
    credentials: 'omit',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(form).toString()
  })
  const body = await res.json().catch(() => ({}))
  if (!res.ok) {
    const desc = body.error_description ? ` ${body.error_description}` : ''
    throw new Error(
      `OIDC ${what} failed (HTTP ${res.status}): ${
        body.error || 'unknown'
      }${desc}`
    )
  }
  if (!body.access_token) {
    throw new Error(`OIDC ${what} returned no access_token`)
  }
  const expiresIn = Number(body.expires_in) || 0
  return {
    accessToken: body.access_token,
    refreshToken: body.refresh_token || null,
    idToken: body.id_token || null,
    expiresAt: Date.now() + expiresIn * 1000
  }
}

export function exchangeCode(
  meta: ProviderMetadata,
  params: {
    clientId: string
    code: string
    codeVerifier: string
    redirectUri: string
  }
): Promise<Tokens> {
  return postToken(
    meta,
    {
      grant_type: 'authorization_code',
      client_id: params.clientId,
      code: params.code,
      code_verifier: params.codeVerifier,
      redirect_uri: params.redirectUri
    },
    'authorization code exchange'
  )
}

export function refreshTokens(
  meta: ProviderMetadata,
  params: { clientId: string; refreshToken: string }
): Promise<Tokens> {
  return postToken(
    meta,
    {
      grant_type: 'refresh_token',
      client_id: params.clientId,
      refresh_token: params.refreshToken
    },
    'token refresh'
  )
}

export function buildLogoutUrl(
  meta: ProviderMetadata,
  params: { clientId: string; idToken: string | null; redirectUri: string }
): string | null {
  if (!meta.end_session_endpoint) return null
  const query = new URLSearchParams({
    client_id: params.clientId,
    post_logout_redirect_uri: params.redirectUri
  })
  if (params.idToken) query.set('id_token_hint', params.idToken)
  return `${meta.end_session_endpoint}?${query.toString()}`
}
