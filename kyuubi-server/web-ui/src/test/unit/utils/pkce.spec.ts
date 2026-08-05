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

import { describe, expect, test, afterEach } from 'vitest'
import {
  sha256Fallback,
  s256Challenge,
  generateCodeVerifier,
  randomUrlSafe,
  decodeJwtPayload
} from '../../../utils/pkce'

function hex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

function digestHex(input: string): string {
  return hex(sha256Fallback(new TextEncoder().encode(input)))
}

// RFC 7636 appendix B.
const RFC7636_VERIFIER = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'
const RFC7636_CHALLENGE = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM'

describe('pkce', () => {
  const realSubtle = globalThis.crypto.subtle

  afterEach(() => {
    Object.defineProperty(globalThis.crypto, 'subtle', {
      value: realSubtle,
      configurable: true
    })
  })

  test('fallback digest matches known SHA-256 vectors', () => {
    expect(digestHex('')).toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
    )
    expect(digestHex('abc')).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    )
    // Spans a padding block boundary (56 bytes -> two blocks).
    expect(
      digestHex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')
    ).toBe('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1')
  })

  test('s256 challenge matches RFC 7636 with crypto.subtle', async () => {
    expect(await s256Challenge(RFC7636_VERIFIER)).toBe(RFC7636_CHALLENGE)
  })

  test('s256 challenge matches RFC 7636 without crypto.subtle', async () => {
    // Insecure contexts (plain HTTP) do not expose subtle at all.
    Object.defineProperty(globalThis.crypto, 'subtle', {
      value: undefined,
      configurable: true
    })
    expect(await s256Challenge(RFC7636_VERIFIER)).toBe(RFC7636_CHALLENGE)
  })

  test('generated verifier is url-safe and long enough', () => {
    const verifier = generateCodeVerifier()
    expect(verifier.length).toBeGreaterThanOrEqual(43)
    expect(verifier).toMatch(/^[A-Za-z0-9\-_]+$/)
    expect(verifier).not.toBe(generateCodeVerifier())
  })

  test('random values are unique', () => {
    expect(randomUrlSafe(16)).not.toBe(randomUrlSafe(16))
  })

  test('decodes a jwt payload and tolerates garbage', () => {
    const payload = btoa(JSON.stringify({ preferred_username: 'alice' }))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '')
    expect(decodeJwtPayload(`header.${payload}.sig`)?.preferred_username).toBe(
      'alice'
    )
    expect(decodeJwtPayload('not-a-jwt')).toBeNull()
    expect(decodeJwtPayload('a.!!!.c')).toBeNull()
  })
})
