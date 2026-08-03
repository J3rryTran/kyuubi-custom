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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * A one-shot loopback HTTP listener that captures the OAuth2 Authorization Code redirect, following
 * RFC 8252 (OAuth for Native Apps). It binds to {@code 127.0.0.1} on an ephemeral (or fixed) port,
 * serves exactly one {@code /callback} request, and hands back the {@code code} after verifying the
 * {@code state}.
 */
public class LoopbackCallbackServer implements AutoCloseable {

  private static final String CALLBACK_PATH = "/callback";

  private final HttpServer server;
  private final int port;
  private final String expectedState;
  private final SynchronousQueue<Object> result = new SynchronousQueue<>();

  /**
   * @param requestedPort the loopback port to bind, or {@code 0} for an ephemeral port
   * @param expectedState the {@code state} value that the redirect must echo back (CSRF protection)
   */
  public LoopbackCallbackServer(int requestedPort, String expectedState) {
    this.expectedState = expectedState;
    try {
      InetAddress loopback = InetAddress.getByName("127.0.0.1");
      this.server = HttpServer.create(new InetSocketAddress(loopback, requestedPort), 0);
    } catch (IOException e) {
      throw new OidcAuthException(
          "Failed to start loopback callback listener on 127.0.0.1: " + e.getMessage(), e);
    }
    this.port = server.getAddress().getPort();
    server.createContext(CALLBACK_PATH, this::handle);
    server.setExecutor(null);
    server.start();
  }

  public String redirectUri() {
    return "http://127.0.0.1:" + port + CALLBACK_PATH;
  }

  /**
   * Block until the redirect arrives (or the timeout elapses) and return the authorization code.
   *
   * @throws OidcAuthException on timeout, a mismatched {@code state}, or an OAuth error redirect
   */
  public String awaitCode(long timeout, TimeUnit unit) {
    Object taken;
    try {
      taken = result.poll(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OidcAuthException("Interrupted while waiting for the OIDC browser callback", e);
    }
    if (taken == null) {
      throw new OidcAuthException(
          "Timed out after "
              + unit.toSeconds(timeout)
              + "s waiting for the OIDC browser callback on "
              + redirectUri());
    }
    if (taken instanceof OidcAuthException) {
      throw (OidcAuthException) taken;
    }
    return (String) taken;
  }

  private void handle(HttpExchange exchange) throws IOException {
    Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
    String body;
    Object outcome;
    String error = params.get("error");
    if (error != null) {
      String desc = params.getOrDefault("error_description", "");
      outcome = new OidcAuthException("Authorization failed: " + error + " " + desc);
      body = page("Sign-in failed", "You can close this tab and return to your SQL client.");
    } else if (!expectedState.equals(params.get("state"))) {
      outcome = new OidcAuthException("Authorization callback state mismatch (possible CSRF)");
      body = page("Sign-in failed", "State mismatch. You can close this tab.");
    } else if (params.get("code") == null) {
      outcome = new OidcAuthException("Authorization callback did not include a code");
      body = page("Sign-in failed", "Missing authorization code. You can close this tab.");
    } else {
      outcome = params.get("code");
      body = page("Signed in", "Authentication complete. You can close this tab and return.");
    }

    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    // Hand the outcome to awaitCode(); ignore if nobody is waiting (duplicate hit).
    result.offer(outcome);
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> map = new HashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return map;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        map.put(urlDecode(pair), "");
      } else {
        map.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
      }
    }
    return map;
  }

  private static String urlDecode(String s) {
    try {
      return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name());
    } catch (Exception e) {
      return s;
    }
  }

  private static String page(String title, String message) {
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>"
        + title
        + "</title></head><body style=\"font-family:sans-serif;text-align:center;margin-top:4rem\"><h2>"
        + title
        + "</h2><p>"
        + message
        + "</p></body></html>";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
