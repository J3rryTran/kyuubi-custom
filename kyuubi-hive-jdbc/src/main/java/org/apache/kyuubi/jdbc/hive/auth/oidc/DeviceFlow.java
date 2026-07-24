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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth2 Device Authorization Grant (RFC 8628) for headless / no-browser clients. The user is shown
 * a URL and a short code to enter on any device; the driver polls the token endpoint until
 * authorized.
 */
public class DeviceFlow {

  private static final Logger LOG = LoggerFactory.getLogger(DeviceFlow.class);
  private static final long DEFAULT_INTERVAL_SECONDS = 5;

  private final OidcProviderMetadata metadata;
  private final OidcConfig config;
  private final TokenEndpointClient tokenClient;
  private final HttpJsonClient http;

  public DeviceFlow(
      OidcProviderMetadata metadata,
      OidcConfig config,
      TokenEndpointClient tokenClient,
      HttpJsonClient http) {
    this.metadata = metadata;
    this.config = config;
    this.tokenClient = tokenClient;
    this.http = http;
  }

  public OidcTokens authenticate() {
    JsonNode auth = requestDeviceCode();
    String deviceCode = text(auth, "device_code");
    String userCode = text(auth, "user_code");
    String verificationUri = text(auth, "verification_uri");
    String verificationUriComplete = text(auth, "verification_uri_complete");
    long interval =
        auth.has("interval")
            ? auth.get("interval").asLong(DEFAULT_INTERVAL_SECONDS)
            : DEFAULT_INTERVAL_SECONDS;
    long expiresIn = auth.has("expires_in") ? auth.get("expires_in").asLong(600) : 600;

    if (deviceCode == null || userCode == null || verificationUri == null) {
      throw new OidcAuthException("Device authorization response missing required fields");
    }

    promptUser(userCode, verificationUri, verificationUriComplete);

    long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
    long intervalMs = interval * 1000L;
    while (System.currentTimeMillis() < deadline) {
      sleep(intervalMs);
      HttpJsonClient.Response r = tokenClient.pollDeviceToken(deviceCode);
      if (r.isSuccess()) {
        return TokenEndpointClient.parseTokens(r.body);
      }
      String error = text(r.body, "error");
      if ("authorization_pending".equals(error)) {
        continue;
      } else if ("slow_down".equals(error)) {
        intervalMs += 5000L;
      } else if (error == null) {
        throw new OidcAuthException("Device token poll failed: HTTP " + r.status);
      } else {
        // access_denied, expired_token, etc.
        throw new OidcAuthException("Device authorization failed: " + error);
      }
    }
    throw new OidcAuthException("Device authorization timed out; the user code expired");
  }

  private JsonNode requestDeviceCode() {
    Map<String, String> form = new HashMap<>();
    form.put("client_id", config.clientId());
    if (config.scope() != null) {
      form.put("scope", config.scope());
    }
    HttpJsonClient.Response r;
    if (config.isConfidentialClient()) {
      r =
          http.postForm(
              metadata.deviceAuthorizationEndpoint(),
              form,
              config.clientId(),
              config.clientSecret());
    } else {
      r = http.postForm(metadata.deviceAuthorizationEndpoint(), form, null, null);
    }
    if (!r.isSuccess()) {
      throw new OidcAuthException(
          "Device authorization request failed (HTTP " + r.status + "): " + text(r.body, "error"));
    }
    return r.body;
  }

  private void promptUser(String userCode, String verificationUri, String verificationUriComplete) {
    StringBuilder sb = new StringBuilder();
    sb.append("To sign in, open ")
        .append(verificationUri)
        .append(" and enter code: ")
        .append(userCode);
    if (verificationUriComplete != null) {
      sb.append("\n  Or open directly: ").append(verificationUriComplete);
      if (config.browserEnabled() && BrowserLauncher.isBrowsingSupported()) {
        BrowserLauncher.open(verificationUriComplete);
      }
    }
    LOG.info(sb.toString());
    System.err.println("[Kyuubi OIDC] " + sb);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OidcAuthException("Interrupted during device authorization polling", e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }
}
