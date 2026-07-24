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

import java.util.Map;

/**
 * Client-side OIDC configuration parsed from the JDBC session variables.
 *
 * <p>This is a client concern only: it drives how the driver <em>acquires</em> an OAuth2 access
 * token (via the Authorization Code + PKCE flow or the Device Authorization flow). The acquired
 * access token is then transmitted to Kyuubi as an {@code Authorization: Bearer} header, reusing
 * the existing JWT/bearer transport path. The server validates the token independently.
 */
public class OidcConfig {

  /** OIDC flow selection. */
  public enum Flow {
    /**
     * OAuth2 Authorization Code + PKCE with an automatically launched browser (desktop clients).
     */
    AUTH_CODE,
    /** OAuth2 Device Authorization Grant, RFC 8628 (headless / no-browser clients). */
    DEVICE,
    /** Try {@link #AUTH_CODE} first, fall back to {@link #DEVICE} when no browser is available. */
    AUTO
  }

  private final String issuer;
  private final String discoveryUri;
  private final String clientId;
  private final String clientSecret;
  private final String scope;
  private final Flow flow;
  private final int redirectPort;
  private final boolean tokenCache;
  private final boolean browserEnabled;
  private final boolean logout;

  private OidcConfig(
      String issuer,
      String discoveryUri,
      String clientId,
      String clientSecret,
      String scope,
      Flow flow,
      int redirectPort,
      boolean tokenCache,
      boolean browserEnabled,
      boolean logout) {
    this.issuer = issuer;
    this.discoveryUri = discoveryUri;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.scope = scope;
    this.flow = flow;
    this.redirectPort = redirectPort;
    this.tokenCache = tokenCache;
    this.browserEnabled = browserEnabled;
    this.logout = logout;
  }

  /**
   * Build a config from the JDBC session variable map.
   *
   * @throws IllegalArgumentException when a required parameter is missing or malformed.
   */
  public static OidcConfig fromSessionConf(Map<String, String> conf) {
    String issuer = trimToNull(conf.get(OidcParams.OIDC_ISSUER));
    String discoveryUri = trimToNull(conf.get(OidcParams.OIDC_DISCOVERY_URI));
    if (issuer == null && discoveryUri == null) {
      throw new IllegalArgumentException(
          "OIDC auth requires either '"
              + OidcParams.OIDC_ISSUER
              + "' or '"
              + OidcParams.OIDC_DISCOVERY_URI
              + "' to be set");
    }
    String clientId = trimToNull(conf.get(OidcParams.OIDC_CLIENT_ID));
    if (clientId == null) {
      throw new IllegalArgumentException(
          "OIDC auth requires '" + OidcParams.OIDC_CLIENT_ID + "' to be set");
    }
    String clientSecret = trimToNull(conf.get(OidcParams.OIDC_CLIENT_SECRET));
    String scope = conf.getOrDefault(OidcParams.OIDC_SCOPE, "openid profile email");

    Flow flow;
    String flowStr = conf.getOrDefault(OidcParams.OIDC_FLOW, "authcode").trim().toLowerCase();
    switch (flowStr) {
      case "authcode":
      case "auth_code":
      case "code":
        flow = Flow.AUTH_CODE;
        break;
      case "device":
        flow = Flow.DEVICE;
        break;
      case "auto":
        flow = Flow.AUTO;
        break;
      default:
        throw new IllegalArgumentException(
            "Unknown '"
                + OidcParams.OIDC_FLOW
                + "' value: "
                + flowStr
                + " (expected: authcode | device | auto)");
    }

    int redirectPort = 0;
    String portStr = trimToNull(conf.get(OidcParams.OIDC_REDIRECT_PORT));
    if (portStr != null) {
      try {
        redirectPort = Integer.parseInt(portStr);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Invalid '" + OidcParams.OIDC_REDIRECT_PORT + "': " + portStr);
      }
      if (redirectPort < 0 || redirectPort > 65535) {
        throw new IllegalArgumentException(
            "'" + OidcParams.OIDC_REDIRECT_PORT + "' out of range: " + redirectPort);
      }
    }

    boolean tokenCache = !"false".equalsIgnoreCase(conf.get(OidcParams.OIDC_TOKEN_CACHE));
    boolean browserEnabled = !"none".equalsIgnoreCase(conf.get(OidcParams.OIDC_BROWSER));
    boolean logout = "true".equalsIgnoreCase(conf.get(OidcParams.OIDC_LOGOUT));

    return new OidcConfig(
        issuer,
        discoveryUri,
        clientId,
        clientSecret,
        scope,
        flow,
        redirectPort,
        tokenCache,
        browserEnabled,
        logout);
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  /** @return the effective discovery document URL ({issuer}/.well-known/openid-configuration). */
  public String discoveryUrl() {
    if (discoveryUri != null) {
      return discoveryUri;
    }
    String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    return base + "/.well-known/openid-configuration";
  }

  public String issuer() {
    return issuer;
  }

  public String clientId() {
    return clientId;
  }

  public String clientSecret() {
    return clientSecret;
  }

  public boolean isConfidentialClient() {
    return clientSecret != null;
  }

  public String scope() {
    return scope;
  }

  public Flow flow() {
    return flow;
  }

  public int redirectPort() {
    return redirectPort;
  }

  public boolean tokenCacheEnabled() {
    return tokenCache;
  }

  public boolean browserEnabled() {
    return browserEnabled;
  }

  public boolean logoutEnabled() {
    return logout;
  }

  /** Stable cache key for the token store, scoped to issuer + client + scope. */
  public String cacheKey() {
    return (issuer != null ? issuer : discoveryUri) + "|" + clientId + "|" + scope;
  }
}
