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

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OAuth2 Authorization Code + PKCE (S256) flow with an auto-launched browser (RFC 8252). */
public class AuthCodeFlow {

  private static final Logger LOG = LoggerFactory.getLogger(AuthCodeFlow.class);
  private static final long CALLBACK_TIMEOUT_SECONDS = 300;

  private final OidcProviderMetadata metadata;
  private final OidcConfig config;
  private final TokenEndpointClient tokenClient;

  public AuthCodeFlow(
      OidcProviderMetadata metadata, OidcConfig config, TokenEndpointClient tokenClient) {
    this.metadata = metadata;
    this.config = config;
    this.tokenClient = tokenClient;
  }

  public OidcTokens authenticate() {
    String codeVerifier = PkceUtil.generateCodeVerifier();
    String codeChallenge = PkceUtil.s256Challenge(codeVerifier);
    String state = PkceUtil.generateRandomUrlSafe();
    String nonce = PkceUtil.generateRandomUrlSafe();

    try (LoopbackCallbackServer callback =
        new LoopbackCallbackServer(config.redirectPort(), state)) {
      String redirectUri = callback.redirectUri();
      String authUrl = buildAuthorizationUrl(redirectUri, codeChallenge, state, nonce);

      LOG.info("Opening browser for OIDC sign-in at {}", metadata.authorizationEndpoint());
      boolean launched = config.browserEnabled() && BrowserLauncher.open(authUrl);
      if (!launched) {
        // Surface the URL so the user can open it manually (also covers a failed auto-launch).
        String msg = "Open the following URL in a browser to complete sign-in:\n  " + authUrl;
        LOG.info(msg);
        System.err.println("[Kyuubi OIDC] " + msg);
      }

      String code = callback.awaitCode(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return tokenClient.exchangeAuthorizationCode(code, codeVerifier, redirectUri);
    }
  }

  private String buildAuthorizationUrl(
      String redirectUri, String codeChallenge, String state, String nonce) {
    StringBuilder sb = new StringBuilder(metadata.authorizationEndpoint());
    sb.append(metadata.authorizationEndpoint().contains("?") ? '&' : '?');
    sb.append("response_type=code");
    append(sb, "client_id", config.clientId());
    append(sb, "redirect_uri", redirectUri);
    append(sb, "scope", config.scope());
    append(sb, "state", state);
    append(sb, "nonce", nonce);
    append(sb, "code_challenge", codeChallenge);
    append(sb, "code_challenge_method", "S256");
    return sb.toString();
  }

  private static void append(StringBuilder sb, String key, String value) {
    sb.append('&')
        .append(HttpJsonClient.urlEncode(key))
        .append('=')
        .append(HttpJsonClient.urlEncode(value));
  }
}
