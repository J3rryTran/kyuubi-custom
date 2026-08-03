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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;

/** Wraps the OAuth2 token endpoint: code exchange, refresh, and device-code polling. */
public class TokenEndpointClient {

  private final OidcProviderMetadata metadata;
  private final OidcConfig config;
  private final HttpJsonClient http;

  public TokenEndpointClient(
      OidcProviderMetadata metadata, OidcConfig config, HttpJsonClient http) {
    this.metadata = metadata;
    this.config = config;
    this.http = http;
  }

  /** Exchange an authorization code (with PKCE verifier) for tokens. */
  public OidcTokens exchangeAuthorizationCode(
      String code, String codeVerifier, String redirectUri) {
    Map<String, String> form = baseForm();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("code_verifier", codeVerifier);
    form.put("redirect_uri", redirectUri);
    HttpJsonClient.Response r = post(form);
    requireSuccess(r, "authorization code exchange");
    return parseTokens(r.body);
  }

  /** Redeem a refresh token for a fresh access token. */
  public OidcTokens refresh(String refreshToken) {
    Map<String, String> form = baseForm();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    if (config.scope() != null) {
      form.put("scope", config.scope());
    }
    HttpJsonClient.Response r = post(form);
    requireSuccess(r, "token refresh");
    return parseTokens(r.body);
  }

  /** Raw device-code token poll; returns the response so the caller can inspect {@code error}. */
  public HttpJsonClient.Response pollDeviceToken(String deviceCode) {
    Map<String, String> form = baseForm();
    form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
    form.put("device_code", deviceCode);
    return post(form);
  }

  private Map<String, String> baseForm() {
    Map<String, String> form = new HashMap<>();
    form.put("client_id", config.clientId());
    // Public clients send client_id in the body; confidential clients use HTTP Basic (see post()).
    return form;
  }

  private HttpJsonClient.Response post(Map<String, String> form) {
    if (config.isConfidentialClient()) {
      return http.postForm(
          metadata.tokenEndpoint(), form, config.clientId(), config.clientSecret());
    }
    return http.postForm(metadata.tokenEndpoint(), form, null, null);
  }

  static OidcTokens parseTokens(JsonNode body) {
    String accessToken = text(body, "access_token");
    if (accessToken == null) {
      throw new OidcAuthException("Token response did not contain an access_token");
    }
    long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong(0L) : 0L;
    return OidcTokens.of(
        accessToken,
        text(body, "refresh_token"),
        text(body, "id_token"),
        expiresIn,
        System.currentTimeMillis());
  }

  private static void requireSuccess(HttpJsonClient.Response r, String what) {
    if (!r.isSuccess()) {
      String error = text(r.body, "error");
      String desc = text(r.body, "error_description");
      throw new OidcAuthException(
          "OIDC "
              + what
              + " failed (HTTP "
              + r.status
              + "): "
              + error
              + " "
              + (desc == null ? "" : desc));
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }
}
