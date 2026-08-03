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

package org.apache.kyuubi.jdbc.hive.auth.oidc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE (RFC 7636) helpers plus random {@code state}/{@code nonce} generation. */
public final class PkceUtil {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private PkceUtil() {}

  /**
   * A high-entropy {@code code_verifier} (RFC 7636 §4.1): 32 random bytes, base64url, no padding.
   */
  public static String generateCodeVerifier() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return URL_ENCODER.encodeToString(bytes);
  }

  /** The S256 {@code code_challenge} = base64url(SHA-256(code_verifier)) (RFC 7636 §4.2). */
  public static String s256Challenge(String codeVerifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      return URL_ENCODER.encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed present on every JVM.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** A random URL-safe opaque value, used for both {@code state} (CSRF) and {@code nonce}. */
  public static String generateRandomUrlSafe() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return URL_ENCODER.encodeToString(bytes);
  }
}
