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

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side orchestrator that acquires and maintains an OAuth2 access token for a JDBC
 * connection.
 *
 * <p>It is provider-independent: given an issuer (or explicit discovery URL) and a client id, it
 * runs the configured flow (Authorization Code + PKCE, Device, or auto), caches the tokens
 * process-wide, and refreshes silently. {@link #get()} returns a currently-valid access token and
 * is used as the token supplier for the HTTP Bearer interceptor, so long-running connections keep
 * working across access-token expiry.
 */
public class OidcAuthenticator implements Supplier<String> {

  private static final Logger LOG = LoggerFactory.getLogger(OidcAuthenticator.class);
  private static final long REFRESH_SKEW_MS = 30_000L;
  private static final int CONNECT_TIMEOUT_MS = 10_000;
  private static final int READ_TIMEOUT_MS = 30_000;

  private final OidcConfig config;
  private final String cacheKey;
  private final HttpJsonClient http;

  private OidcProviderMetadata metadata;
  private TokenEndpointClient tokenClient;
  private OidcTokens tokens;

  public OidcAuthenticator(Map<String, String> sessionConf) {
    this.config = OidcConfig.fromSessionConf(sessionConf);
    this.cacheKey = config.cacheKey();
    this.http = new HttpJsonClient(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
  }

  /** Eagerly acquire a token so connection open fails fast on auth errors. */
  public String acquireInitialToken() {
    return currentAccessToken();
  }

  /** Supplier contract: returns a currently-valid access token (used per HTTP request). */
  @Override
  public String get() {
    return currentAccessToken();
  }

  public synchronized String currentAccessToken() {
    long now = System.currentTimeMillis();
    if (tokens == null && config.tokenCacheEnabled()) {
      tokens = TokenStore.get(cacheKey);
    }
    if (tokens != null && !tokens.isExpiredOrNearExpiry(now, REFRESH_SKEW_MS)) {
      return tokens.accessToken();
    }
    if (tokens != null && tokens.hasRefreshToken()) {
      try {
        tokens = tokenClient().refresh(tokens.refreshToken());
        store();
        LOG.debug("Refreshed OIDC access token for {}", config.clientId());
        return tokens.accessToken();
      } catch (OidcAuthException e) {
        LOG.info("OIDC token refresh failed ({}); re-authenticating interactively", e.getMessage());
        tokens = null;
      }
    }
    tokens = runInteractiveFlow();
    store();
    return tokens.accessToken();
  }

  private OidcTokens runInteractiveFlow() {
    switch (config.flow()) {
      case AUTH_CODE:
        return new AuthCodeFlow(metadata(), config, tokenClient()).authenticate();
      case DEVICE:
        return new DeviceFlow(metadata(), config, tokenClient(), http).authenticate();
      case AUTO:
      default:
        if (BrowserLauncher.isBrowsingSupported()) {
          return new AuthCodeFlow(metadata(), config, tokenClient()).authenticate();
        }
        LOG.info("No browser available; using OAuth2 Device Authorization flow");
        return new DeviceFlow(metadata(), config, tokenClient(), http).authenticate();
    }
  }

  /** Best-effort OIDC RP-initiated logout; clears the cached tokens regardless. */
  public synchronized void logout() {
    try {
      if (config.logoutEnabled() && tokens != null && tokens.idToken() != null) {
        String endSession = metadata().endSessionEndpointOrNull();
        if (endSession != null) {
          String url =
              endSession
                  + (endSession.contains("?") ? '&' : '?')
                  + "id_token_hint="
                  + HttpJsonClient.urlEncode(tokens.idToken())
                  + "&client_id="
                  + HttpJsonClient.urlEncode(config.clientId());
          bestEffortGet(url);
        }
      }
    } catch (RuntimeException e) {
      LOG.debug("OIDC logout call failed (ignored): {}", e.getMessage());
    } finally {
      TokenStore.remove(cacheKey);
      tokens = null;
    }
  }

  private synchronized OidcProviderMetadata metadata() {
    if (metadata == null) {
      metadata = OidcProviderMetadata.discover(config.discoveryUrl(), http);
    }
    return metadata;
  }

  private synchronized TokenEndpointClient tokenClient() {
    if (tokenClient == null) {
      tokenClient = new TokenEndpointClient(metadata(), config, http);
    }
    return tokenClient;
  }

  private void store() {
    if (config.tokenCacheEnabled()) {
      TokenStore.put(cacheKey, tokens);
    }
  }

  private static void bestEffortGet(String url) {
    try {
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setInstanceFollowRedirects(false);
      conn.getResponseCode();
      conn.disconnect();
    } catch (Exception ignored) {
      // best-effort
    }
  }
}
