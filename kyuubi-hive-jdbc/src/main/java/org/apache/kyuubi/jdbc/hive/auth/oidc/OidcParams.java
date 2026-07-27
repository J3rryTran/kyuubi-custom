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

/** JDBC session-variable names that configure the client-side OIDC token acquisition. */
public final class OidcParams {

  private OidcParams() {}

  /** {@code auth=oidc} — explicit OIDC auth type. */
  public static final String AUTH_TYPE_OIDC = "oidc";

  /**
   * {@code oidc=true} — enables OIDC on top of the existing {@code auth=jwt} mode, so that an OIDC
   * access token is acquired interactively instead of being pre-supplied. Also serves as the
   * presence-trigger key.
   */
  public static final String OIDC_ENABLED = "oidc";

  public static final String OIDC_ISSUER = "oidcIssuer";
  public static final String OIDC_DISCOVERY_URI = "oidcDiscoveryUri";
  public static final String OIDC_CLIENT_ID = "oidcClientId";
  public static final String OIDC_CLIENT_SECRET = "oidcClientSecret";
  public static final String OIDC_SCOPE = "oidcScope";
  public static final String OIDC_FLOW = "oidcFlow";
  public static final String OIDC_REDIRECT_PORT = "oidcRedirectPort";
  public static final String OIDC_TOKEN_CACHE = "oidcTokenCache";
  public static final String OIDC_BROWSER = "oidcBrowser";
  public static final String OIDC_LOGOUT = "oidcLogout";

  /**
   * {@code oidcInsecureTls=true} — skip TLS certificate and hostname verification for the calls to
   * the OIDC provider (discovery, JWKS, token, device endpoints). INSECURE: only for internal/dev
   * providers using self-signed certificates. Never enable against a production issuer.
   */
  public static final String OIDC_INSECURE_TLS = "oidcInsecureTls";
}
