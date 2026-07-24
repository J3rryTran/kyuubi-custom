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

package org.apache.kyuubi.jdbc.hive.auth;

import java.util.Map;
import java.util.function.Supplier;
import org.apache.http.HttpRequest;
import org.apache.http.client.CookieStore;
import org.apache.http.protocol.HttpContext;

/**
 * This implements the logic to intercept the HTTP requests from the Hive Jdbc connection and adds
 * JWT auth header.
 *
 * <p>The token is provided by a {@link Supplier} evaluated per request, so a dynamic source (e.g.
 * an OIDC authenticator that transparently refreshes the access token) stays current across a
 * long-running connection. A fixed pre-supplied JWT is wrapped as a constant supplier.
 */
public class HttpJwtAuthRequestInterceptor extends HttpRequestInterceptorBase {
  private final Supplier<String> jwtSupplier;

  public HttpJwtAuthRequestInterceptor(
      String signedJwt,
      CookieStore cookieStore,
      String cn,
      boolean isSSL,
      Map<String, String> additionalHeaders,
      Map<String, String> customCookies) {
    this(() -> signedJwt, cookieStore, cn, isSSL, additionalHeaders, customCookies);
  }

  public HttpJwtAuthRequestInterceptor(
      Supplier<String> jwtSupplier,
      CookieStore cookieStore,
      String cn,
      boolean isSSL,
      Map<String, String> additionalHeaders,
      Map<String, String> customCookies) {
    super(cookieStore, cn, isSSL, additionalHeaders, customCookies);
    this.jwtSupplier = jwtSupplier;
  }

  @Override
  protected void addHttpAuthHeader(HttpRequest httpRequest, HttpContext httpContext) {
    httpRequest.addHeader(
        HttpAuthUtils.AUTHORIZATION, HttpAuthUtils.BEARER + " " + jwtSupplier.get());
  }
}
