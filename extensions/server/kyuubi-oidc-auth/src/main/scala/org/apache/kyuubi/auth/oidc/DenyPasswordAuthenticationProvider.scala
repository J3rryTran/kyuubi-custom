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

package org.apache.kyuubi.auth.oidc

import javax.security.sasl.AuthenticationException

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.authentication.PasswdAuthenticationProvider

/**
 * A password provider that rejects every username/password login. Useful in an OIDC-only deployment
 * that keeps THRIFT_BINARY / HTTP Basic paths enabled: under `kyuubi.authentication=CUSTOM` those
 * paths still require a `PasswdAuthenticationProvider`, so wiring this to
 * `kyuubi.authentication.custom.class` forces clients onto the OIDC bearer flow.
 */
class DenyPasswordAuthenticationProvider(conf: KyuubiConf) extends PasswdAuthenticationProvider {

  def this() = this(null)

  override def authenticate(user: String, password: String): Unit = {
    throw new AuthenticationException(
      "Password authentication is disabled; use OIDC/JWT bearer authentication over HTTP transport")
  }
}
