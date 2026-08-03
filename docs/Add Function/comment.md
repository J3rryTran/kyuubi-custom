# Review Comments

The current design is generally solid and technically feasible. However, several assumptions should be clarified or relaxed to avoid constraining the implementation unnecessarily.

---

## 1. Clarify Desktop vs Headless Clients

The current document assumes every JDBC client can launch a browser.

Please clarify that browser-based SSO is only applicable to interactive desktop clients such as:

- DBeaver
- DataGrip
- IntelliJ Database Tools

Headless clients (e.g. Beeline, Sqlline, Spark, Airflow, server-side applications) cannot rely on browser-based authentication.

For headless environments, recommend:

- OAuth2 Device Authorization Flow (RFC 8628), or
- Pre-issued Access Tokens (existing JWT mode).

---

## 2. Clarify THRIFT_HTTP Limitation

The document currently assumes OIDC requires THRIFT_HTTP.

Please clarify that this is an implementation choice because the existing Bearer authentication mechanism already exists on the HTTP transport.

Suggested wording:

> The initial implementation targets THRIFT_HTTP because it already supports HTTP Bearer authentication. This is an implementation decision rather than an inherent limitation of OIDC.

---

## 3. Relax the Driver API Design

The document currently introduces:

```text
auth=oidc
```

This should be presented as one possible API instead of the only acceptable solution.

Allow the implementation to propose a more natural extension of the existing JDBC authentication model if appropriate.

For example:

- auth=oidc
- auth=jwt + oidc=true
- another backward-compatible design

The final API should minimize changes to the existing JDBC driver.

---

## 4. Relax the "No Core Changes" Statement

Several sections state that no Kyuubi core modifications are required.

Replace this with:

> Minimize modifications to existing Kyuubi components whenever possible.

Small changes to existing modules are acceptable if they significantly improve maintainability or integration.

---

## 5. JWT Library

Nimbus JOSE JWT should be recommended rather than mandated.

Suggested wording:

> Nimbus JOSE JWT is the recommended implementation. Equivalent mature JWT libraries may also be used if technically justified.

---

## 6. JWT Validation

Extend the validation checklist.

Current:

- signature
- issuer
- audience
- exp
- nbf

Also consider validating:

- iat (Issued At)
- allowed signing algorithms

Reject:

- alg=none
- unsupported algorithms

---

## 7. Accept Only Access Tokens

Explicitly state:

Only OAuth2 Access Tokens should be accepted for authentication.

OIDC ID Tokens must not be accepted as authentication credentials.

---

## 8. Audience Clarification

Avoid implying that the expected audience equals the Keycloak Realm name.

Instead:

- Audience must always be configurable.
- In most deployments it will correspond to the Client ID.
- Never hardcode or infer it from the Realm name.

---

## 9. Prefer OIDC Discovery

Whenever possible, prefer OpenID Connect Discovery instead of requiring every endpoint to be configured manually.

From the configured issuer:

```
issuer
    ↓
.well-known/openid-configuration
    ↓
authorization_endpoint
token_endpoint
jwks_uri
userinfo_endpoint
end_session_endpoint
```

Explicit endpoint configuration should remain optional.

---

## 10. Refresh Token Clarification

The current wording may suggest that every request requires Bearer token validation.

Clarify that:

- Access Token validation primarily occurs during session establishment.
- Once authenticated, Kyuubi typically continues using its existing session cookie.
- Refresh Tokens are mainly required for:
    - opening new sessions,
    - re-authentication after cookie/session expiration,
    - long-running desktop sessions.

Refresh Tokens are not expected to participate in every RPC request.

---

## 11. Preserve Existing JWT Authentication

The existing JWT authentication mode should continue to work unchanged.

The new OIDC capability should be treated as an additional mechanism for acquiring and managing JWT Access Tokens, rather than replacing the existing JWT authentication path.

---

## 12. Overall Design Goal

Please keep the implementation focused on the following architectural principle:

- reuse existing Kyuubi Authentication SPI whenever possible;
- reuse existing HTTP Bearer authentication path;
- minimize invasive changes to Kyuubi core;
- keep the implementation provider-independent;
- maintain full backward compatibility.