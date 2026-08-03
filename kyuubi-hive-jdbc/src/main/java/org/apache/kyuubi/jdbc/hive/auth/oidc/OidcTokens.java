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

/** Immutable holder for the tokens returned from an OAuth2 token endpoint. */
public class OidcTokens {

  private final String accessToken;
  private final String refreshToken;
  private final String idToken;
  private final long expiresAtEpochMs;

  public OidcTokens(
      String accessToken, String refreshToken, String idToken, long expiresAtEpochMs) {
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    this.idToken = idToken;
    this.expiresAtEpochMs = expiresAtEpochMs;
  }

  /**
   * @param expiresInSeconds the {@code expires_in} value from the token response; may be {@code <=
   *     0} if the provider omitted it, in which case the token is treated as already near expiry.
   */
  public static OidcTokens of(
      String accessToken,
      String refreshToken,
      String idToken,
      long expiresInSeconds,
      long nowEpochMs) {
    long expiresAt = expiresInSeconds > 0 ? nowEpochMs + (expiresInSeconds * 1000L) : nowEpochMs;
    return new OidcTokens(accessToken, refreshToken, idToken, expiresAt);
  }

  public String accessToken() {
    return accessToken;
  }

  public String refreshToken() {
    return refreshToken;
  }

  public String idToken() {
    return idToken;
  }

  public boolean hasRefreshToken() {
    return refreshToken != null && !refreshToken.isEmpty();
  }

  /**
   * @return true when the access token is expired or within {@code skewMs} of expiry, and should be
   *     refreshed before use.
   */
  public boolean isExpiredOrNearExpiry(long nowEpochMs, long skewMs) {
    return nowEpochMs >= (expiresAtEpochMs - skewMs);
  }
}
