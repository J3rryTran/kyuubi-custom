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

import java.net.URL
import java.security.Principal
import java.text.ParseException
import java.util.{Collections, Date}
import javax.security.sasl.AuthenticationException

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{ConfigurableJWTProcessor, DefaultJWTProcessor}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.authentication.{TokenAuthenticationProvider, TokenCredential}

/**
 * A server-side [[TokenAuthenticationProvider]] that validates OAuth2 / OIDC access-token JWTs
 * (e.g. issued by Keycloak) against the provider's published JWKS.
 *
 * It is provider-independent and wired via `kyuubi.authentication.custom.bearer.class`. Validation
 * covers signature (allow-listed algorithms only, `alg=none` rejected), `iss`, `aud`, `exp`,
 * `nbf`, `iat`, and (when configured) the JOSE `typ` header to reject OIDC ID tokens.
 * On success, the configured username claim (default `preferred_username`) is the session user.
 *
 * Configuration keys (read raw from [[KyuubiConf]], so Kyuubi core is untouched):
 *  - `kyuubi.authentication.jwt.issuer` (required) the OIDC issuer
 *  - `kyuubi.authentication.jwt.audience` (required) accepted audience(s), comma-separated
 *  - `kyuubi.authentication.jwt.jwks.url` (optional) JWKS URL; else discovered from the issuer
 *  - `kyuubi.authentication.jwt.username.claim` (default `preferred_username`)
 *  - `kyuubi.authentication.jwt.allowed.algorithms` (default `RS256`, comma-separated)
 *  - `kyuubi.authentication.jwt.expected.typ` (optional) required JOSE `typ`; rejects ID tokens
 *  - `kyuubi.authentication.jwt.clock.skew.seconds` (default `30`)
 *  - `kyuubi.authentication.jwt.connect.timeout.ms` (default `5000`)
 *  - `kyuubi.authentication.jwt.read.timeout.ms` (default `5000`)
 *
 * The audience is always taken from config (typically the client id), never the realm name.
 */
class JwtTokenAuthenticationProvider(conf: KyuubiConf)
  extends TokenAuthenticationProvider with Logging {

  import JwtTokenAuthenticationProvider._

  private val issuer: String = required(conf, ISSUER)
  private val audiences: Set[String] = {
    val aud = required(conf, AUDIENCE)
    aud.split(",").map(_.trim).filter(_.nonEmpty).toSet
  }
  private val usernameClaim: String =
    conf.getOption(USERNAME_CLAIM).map(_.trim).filter(_.nonEmpty).getOrElse("preferred_username")
  private val allowedAlgorithms: Set[JWSAlgorithm] = {
    val raw = conf.getOption(ALLOWED_ALGORITHMS).map(_.trim).filter(_.nonEmpty).getOrElse("RS256")
    raw.split(",").map(_.trim).filter(_.nonEmpty).map(JWSAlgorithm.parse).toSet
  }
  private val expectedTyp: Option[String] =
    conf.getOption(EXPECTED_TYP).map(_.trim).filter(_.nonEmpty)
  private val clockSkewSeconds: Int =
    conf.getOption(CLOCK_SKEW_SECONDS).map(_.trim.toInt).getOrElse(30)
  private val connectTimeoutMs: Int =
    conf.getOption(CONNECT_TIMEOUT_MS).map(_.trim.toInt).getOrElse(5000)
  private val readTimeoutMs: Int =
    conf.getOption(READ_TIMEOUT_MS).map(_.trim.toInt).getOrElse(5000)

  private val resourceRetriever = new DefaultResourceRetriever(connectTimeoutMs, readTimeoutMs)

  private val jwksUrl: String =
    conf.getOption(JWKS_URL).map(_.trim).filter(_.nonEmpty).getOrElse(discoverJwksUri())

  private val jwtProcessor: ConfigurableJWTProcessor[SecurityContext] = buildProcessor()

  info(s"Initialized JWT authentication: issuer=$issuer, jwksUrl=$jwksUrl, " +
    s"audience=${audiences.mkString(",")}, usernameClaim=$usernameClaim, " +
    s"allowedAlgorithms=${allowedAlgorithms.map(_.getName).mkString(",")}")

  override def authenticate(credential: TokenCredential): Principal = {
    val token = credential.token
    if (token == null || token.isEmpty) {
      throw new AuthenticationException("Bearer token is empty")
    }
    val claims =
      try {
        jwtProcessor.process(token, null.asInstanceOf[SecurityContext])
      } catch {
        case e: ParseException =>
          throw new AuthenticationException("Malformed JWT: " + e.getMessage)
        case e: Exception =>
          // BadJOSEException (bad signature / claims), JOSEException (crypto), etc.
          throw new AuthenticationException("JWT validation failed: " + e.getMessage)
      }

    // Manual issued-at sanity: reject tokens issued in the future (beyond the allowed skew).
    val iat: Date = claims.getIssueTime
    if (iat != null && iat.getTime > System.currentTimeMillis() + clockSkewSeconds * 1000L) {
      throw new AuthenticationException("JWT 'iat' is in the future")
    }

    val username = claims.getStringClaim(usernameClaim)
    if (username == null || username.isEmpty) {
      throw new AuthenticationException(s"JWT is missing the username claim '$usernameClaim'")
    }
    debug(s"Authenticated bearer token for user '$username' (subject=${claims.getSubject})")
    new org.apache.kyuubi.service.authentication.BasicPrincipal(username)
  }

  private def discoverJwksUri(): String = {
    val base = if (issuer.endsWith("/")) issuer.dropRight(1) else issuer
    val discoveryUrl = base + "/.well-known/openid-configuration"
    try {
      val content = resourceRetriever.retrieveResource(new URL(discoveryUrl)).getContent
      val node = MAPPER.readTree(content)
      val jwks = node.get("jwks_uri")
      if (jwks == null || jwks.isNull) {
        throw new IllegalStateException(s"discovery document at $discoveryUrl has no jwks_uri")
      }
      jwks.asText
    } catch {
      case e: Exception =>
        throw new IllegalStateException(
          s"Failed OIDC discovery at $discoveryUrl: ${e.getMessage}",
          e)
    }
  }

  private def buildProcessor(): ConfigurableJWTProcessor[SecurityContext] = {
    val jwkSource: JWKSource[SecurityContext] =
      JWKSourceBuilder.create[SecurityContext](new URL(jwksUrl), resourceRetriever).build()

    val processor = new DefaultJWTProcessor[SecurityContext]()
    processor.setJWSKeySelector(
      new JWSVerificationKeySelector[SecurityContext](allowedAlgorithms.asJava, jwkSource))

    expectedTyp.foreach { typ =>
      processor.setJWSTypeVerifier(
        new com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier[SecurityContext](
          new JOSEObjectType(typ)))
    }

    val exactMatch = new JWTClaimsSet.Builder().issuer(issuer).build()
    val required = Set("exp", "iat").asJava
    val verifier = new com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier[SecurityContext](
      audiences.asJava,
      exactMatch,
      required,
      Collections.emptySet[String]())
    verifier.setMaxClockSkew(clockSkewSeconds)
    processor.setJWTClaimsSetVerifier(verifier)
    processor
  }
}

object JwtTokenAuthenticationProvider {
  private val PREFIX = "kyuubi.authentication.jwt."
  val ISSUER: String = PREFIX + "issuer"
  val AUDIENCE: String = PREFIX + "audience"
  val JWKS_URL: String = PREFIX + "jwks.url"
  val USERNAME_CLAIM: String = PREFIX + "username.claim"
  val ALLOWED_ALGORITHMS: String = PREFIX + "allowed.algorithms"
  val EXPECTED_TYP: String = PREFIX + "expected.typ"
  val CLOCK_SKEW_SECONDS: String = PREFIX + "clock.skew.seconds"
  val CONNECT_TIMEOUT_MS: String = PREFIX + "connect.timeout.ms"
  val READ_TIMEOUT_MS: String = PREFIX + "read.timeout.ms"

  private val MAPPER = new ObjectMapper()

  private def required(conf: KyuubiConf, key: String): String = {
    conf.getOption(key).map(_.trim).filter(_.nonEmpty).getOrElse {
      throw new IllegalArgumentException(s"$key must be set for JWT bearer authentication")
    }
  }
}
