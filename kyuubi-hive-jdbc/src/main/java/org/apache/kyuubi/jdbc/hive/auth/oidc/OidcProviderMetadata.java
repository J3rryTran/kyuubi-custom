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

/**
 * The subset of the OIDC provider metadata (from {@code /.well-known/openid-configuration}) that
 * the driver needs. Preferring discovery keeps the JDBC URL small — only the issuer and client id
 * are required.
 */
public class OidcProviderMetadata {

  private final String authorizationEndpoint;
  private final String tokenEndpoint;
  private final String deviceAuthorizationEndpoint;
  private final String endSessionEndpoint;

  public OidcProviderMetadata(
      String authorizationEndpoint,
      String tokenEndpoint,
      String deviceAuthorizationEndpoint,
      String endSessionEndpoint) {
    this.authorizationEndpoint = authorizationEndpoint;
    this.tokenEndpoint = tokenEndpoint;
    this.deviceAuthorizationEndpoint = deviceAuthorizationEndpoint;
    this.endSessionEndpoint = endSessionEndpoint;
  }

  /** Fetch and parse the discovery document. */
  public static OidcProviderMetadata discover(String discoveryUrl, HttpJsonClient http) {
    JsonNode doc = http.getJson(discoveryUrl);
    String tokenEndpoint = text(doc, "token_endpoint");
    if (tokenEndpoint == null) {
      throw new OidcAuthException(
          "OIDC discovery document at " + discoveryUrl + " has no token_endpoint");
    }
    return new OidcProviderMetadata(
        text(doc, "authorization_endpoint"),
        tokenEndpoint,
        text(doc, "device_authorization_endpoint"),
        text(doc, "end_session_endpoint"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  public String authorizationEndpoint() {
    return require(authorizationEndpoint, "authorization_endpoint");
  }

  public String tokenEndpoint() {
    return tokenEndpoint;
  }

  public boolean hasDeviceAuthorizationEndpoint() {
    return deviceAuthorizationEndpoint != null;
  }

  public String deviceAuthorizationEndpoint() {
    return require(deviceAuthorizationEndpoint, "device_authorization_endpoint");
  }

  public String endSessionEndpointOrNull() {
    return endSessionEndpoint;
  }

  private static String require(String value, String name) {
    if (value == null) {
      throw new OidcAuthException("OIDC provider does not advertise " + name);
    }
    return value;
  }
}
