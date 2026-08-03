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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide in-memory cache of acquired tokens, keyed by issuer+client+scope. Lets many JDBC
 * connections opened in the same JVM (e.g. a DBeaver session) reuse one interactive sign-in and
 * refresh silently, instead of prompting per connection. Tokens are held in memory only.
 */
public final class TokenStore {

  private static final ConcurrentHashMap<String, OidcTokens> CACHE = new ConcurrentHashMap<>();

  private TokenStore() {}

  public static OidcTokens get(String key) {
    return CACHE.get(key);
  }

  public static void put(String key, OidcTokens tokens) {
    if (tokens != null) {
      CACHE.put(key, tokens);
    }
  }

  public static void remove(String key) {
    CACHE.remove(key);
  }
}
