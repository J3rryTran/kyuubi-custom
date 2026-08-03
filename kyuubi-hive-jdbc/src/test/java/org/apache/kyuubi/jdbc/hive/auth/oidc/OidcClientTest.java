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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class OidcClientTest {

  @Test
  public void pkceChallengeMatchesS256OfVerifier() throws Exception {
    String verifier = PkceUtil.generateCodeVerifier();
    // Verifier is URL-safe base64 without padding.
    assertFalse(verifier.contains("+"));
    assertFalse(verifier.contains("/"));
    assertFalse(verifier.contains("="));
    assertTrue(verifier.length() >= 43);

    String challenge = PkceUtil.s256Challenge(verifier);
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] hash = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
    String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    assertEquals(expected, challenge);
  }

  @Test
  public void randomStateIsUrlSafeAndUnique() {
    String a = PkceUtil.generateRandomUrlSafe();
    String b = PkceUtil.generateRandomUrlSafe();
    assertFalse(a.equals(b));
    assertFalse(a.contains("="));
  }

  @Test
  public void configParsesAndDerivesDiscoveryUrl() {
    Map<String, String> conf = new HashMap<>();
    conf.put(OidcParams.OIDC_ISSUER, "https://kc.example.com/realms/prod/");
    conf.put(OidcParams.OIDC_CLIENT_ID, "kyuubi-jdbc");
    OidcConfig cfg = OidcConfig.fromSessionConf(conf);
    assertEquals(
        "https://kc.example.com/realms/prod/.well-known/openid-configuration", cfg.discoveryUrl());
    assertEquals("openid profile email", cfg.scope());
    assertEquals(OidcConfig.Flow.AUTH_CODE, cfg.flow());
    assertTrue(cfg.tokenCacheEnabled());
    assertTrue(cfg.browserEnabled());
    assertFalse(cfg.isConfidentialClient());
  }

  @Test
  public void configRequiresClientId() {
    Map<String, String> conf = new HashMap<>();
    conf.put(OidcParams.OIDC_ISSUER, "https://kc.example.com/realms/prod");
    assertThrows(IllegalArgumentException.class, () -> OidcConfig.fromSessionConf(conf));
  }

  @Test
  public void configRequiresIssuerOrDiscovery() {
    Map<String, String> conf = new HashMap<>();
    conf.put(OidcParams.OIDC_CLIENT_ID, "kyuubi-jdbc");
    assertThrows(IllegalArgumentException.class, () -> OidcConfig.fromSessionConf(conf));
  }

  @Test
  public void deviceFlowSelectionAndConfidentialClient() {
    Map<String, String> conf = new HashMap<>();
    conf.put(OidcParams.OIDC_DISCOVERY_URI, "https://kc.example.com/disco");
    conf.put(OidcParams.OIDC_CLIENT_ID, "kyuubi-jdbc");
    conf.put(OidcParams.OIDC_CLIENT_SECRET, "s3cret");
    conf.put(OidcParams.OIDC_FLOW, "device");
    conf.put(OidcParams.OIDC_TOKEN_CACHE, "false");
    conf.put(OidcParams.OIDC_BROWSER, "none");
    OidcConfig cfg = OidcConfig.fromSessionConf(conf);
    assertEquals(OidcConfig.Flow.DEVICE, cfg.flow());
    assertTrue(cfg.isConfidentialClient());
    assertFalse(cfg.tokenCacheEnabled());
    assertFalse(cfg.browserEnabled());
    assertEquals("https://kc.example.com/disco", cfg.discoveryUrl());
  }

  @Test
  public void invalidFlowRejected() {
    Map<String, String> conf = new HashMap<>();
    conf.put(OidcParams.OIDC_ISSUER, "https://kc.example.com/realms/prod");
    conf.put(OidcParams.OIDC_CLIENT_ID, "kyuubi-jdbc");
    conf.put(OidcParams.OIDC_FLOW, "banana");
    assertThrows(IllegalArgumentException.class, () -> OidcConfig.fromSessionConf(conf));
  }

  @Test
  public void parseTokensReadsAccessTokenAndExpiry() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String json =
        "{\"access_token\":\"AT\",\"refresh_token\":\"RT\",\"id_token\":\"IT\",\"expires_in\":300}";
    OidcTokens tokens = TokenEndpointClient.parseTokens(mapper.readTree(json));
    assertEquals("AT", tokens.accessToken());
    assertEquals("RT", tokens.refreshToken());
    assertEquals("IT", tokens.idToken());
    assertTrue(tokens.hasRefreshToken());
    long now = System.currentTimeMillis();
    assertFalse(tokens.isExpiredOrNearExpiry(now, 30_000L));
    // Beyond the 300s lifetime it must be considered expired.
    assertTrue(tokens.isExpiredOrNearExpiry(now + 301_000L, 30_000L));
  }

  @Test
  public void parseTokensRejectsMissingAccessToken() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertThrows(
        OidcAuthException.class,
        () -> TokenEndpointClient.parseTokens(mapper.readTree("{\"token_type\":\"Bearer\"}")));
  }

  @Test
  public void formEncodingIsUrlEncoded() {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("redirect_uri", "http://127.0.0.1:8080/callback");
    String encoded = HttpJsonClient.encodeForm(form);
    assertEquals(
        "grant_type=authorization_code&redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fcallback",
        encoded);
  }

  @Test
  public void loopbackServerServesRedirectUriAndTimesOut() throws Exception {
    try (LoopbackCallbackServer server = new LoopbackCallbackServer(0, "state-xyz")) {
      String redirect = server.redirectUri();
      assertNotNull(redirect);
      assertTrue(redirect.startsWith("http://127.0.0.1:"));
      assertTrue(redirect.endsWith("/callback"));
      // No callback arrives -> awaitCode must time out with an OidcAuthException.
      assertThrows(
          OidcAuthException.class,
          () -> server.awaitCode(200, java.util.concurrent.TimeUnit.MILLISECONDS));
    }
  }
}
