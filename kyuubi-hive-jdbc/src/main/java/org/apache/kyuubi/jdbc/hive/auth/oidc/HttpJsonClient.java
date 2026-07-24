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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Minimal JSON-over-HTTP helper built on the JDK {@link HttpURLConnection} (no extra dependency).
 * Used for OIDC discovery and OAuth2 token/device endpoint calls.
 *
 * <p>TLS to the identity provider relies on the default JVM trust store, which is appropriate for
 * providers fronted by a publicly trusted CA (the common Keycloak deployment).
 */
public class HttpJsonClient {

  /**
   * HTTP status + parsed JSON body (parsed even for 4xx, so OAuth error bodies are inspectable).
   */
  public static final class Response {
    public final int status;
    public final JsonNode body;

    Response(int status, JsonNode body) {
      this.status = status;
      this.body = body;
    }

    public boolean isSuccess() {
      return status >= 200 && status < 300;
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  public HttpJsonClient(int connectTimeoutMs, int readTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  /** GET a JSON document; throws on non-2xx. */
  public JsonNode getJson(String url) {
    try {
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("GET");
      conn.setRequestProperty("Accept", "application/json");
      conn.setConnectTimeout(connectTimeoutMs);
      conn.setReadTimeout(readTimeoutMs);
      Response r = readResponse(conn);
      if (!r.isSuccess()) {
        throw new OidcAuthException("GET " + url + " failed: HTTP " + r.status + " " + r.body);
      }
      return r.body;
    } catch (IOException e) {
      throw new OidcAuthException("GET " + url + " failed: " + e.getMessage(), e);
    }
  }

  /**
   * POST an {@code application/x-www-form-urlencoded} body and parse the JSON response. The
   * response is returned for BOTH success and error statuses so that callers (e.g. device-flow
   * polling) can inspect the OAuth2 {@code error} field without exception handling.
   *
   * @param basicUser optional HTTP Basic username (confidential clients); may be {@code null}
   * @param basicPass optional HTTP Basic password (client secret); may be {@code null}
   */
  public Response postForm(
      String url, Map<String, String> form, String basicUser, String basicPass) {
    try {
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
      conn.setRequestProperty("Accept", "application/json");
      if (basicUser != null && basicPass != null) {
        String basic =
            Base64.getEncoder()
                .encodeToString((basicUser + ":" + basicPass).getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + basic);
      }
      conn.setConnectTimeout(connectTimeoutMs);
      conn.setReadTimeout(readTimeoutMs);

      byte[] payload = encodeForm(form).getBytes(StandardCharsets.UTF_8);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(payload);
      }
      return readResponse(conn);
    } catch (IOException e) {
      throw new OidcAuthException("POST " + url + " failed: " + e.getMessage(), e);
    }
  }

  private Response readResponse(HttpURLConnection conn) throws IOException {
    int status = conn.getResponseCode();
    InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
    byte[] bytes = is == null ? new byte[0] : readAll(is);
    JsonNode body;
    if (bytes.length == 0) {
      body = MAPPER.createObjectNode();
    } else {
      try {
        body = MAPPER.readTree(bytes);
      } catch (IOException parseError) {
        // Non-JSON error page; wrap the raw text so the caller still sees something useful.
        throw new OidcAuthException(
            "Non-JSON response (HTTP "
                + status
                + "): "
                + new String(bytes, StandardCharsets.UTF_8));
      }
    }
    return new Response(status, body);
  }

  private static byte[] readAll(InputStream is) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = is.read(buf)) != -1) {
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  static String encodeForm(Map<String, String> form) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : form.entrySet()) {
      if (e.getValue() == null) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
    }
    return sb.toString();
  }

  static String urlEncode(String s) {
    try {
      return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
    } catch (Exception e) {
      throw new OidcAuthException("URL encoding failed", e);
    }
  }
}
