# Task: Investigate and Implement OIDC SSO Authentication for Apache Kyuubi

## Background

Apache Kyuubi 1.10.3 currently supports several authentication modes but does not provide native OpenID Connect (OIDC) Single Sign-On (SSO).

The desired user experience is similar to modern cloud databases.

---

# Target User Experience

A user connects from a desktop SQL client such as:

- DBeaver
- IntelliJ Database Tools
- DataGrip
- Other JDBC clients

When creating a connection, the user enters the server information and initiates the connection.

Instead of authenticating with a locally verified username/password, the authentication flow should redirect the user to the organization's Keycloak login page.

Example flow:

```
DBeaver
    │
    │ Connect
    ▼
Kyuubi
    │
    │ Trigger OIDC authentication
    ▼
Default Web Browser
    │
    ▼
Keycloak Login Page
    │
    │ Login / MFA / SSO
    ▼
Keycloak
    │
    │ Authorization Code
    ▼
Desktop Client
    │
    │ Exchange Authorization Code for Access Token
    ▼
Kyuubi
    │
    │ Validate JWT
    ▼
Authenticated Session
```

The authentication should feel similar to logging into Azure, Google Cloud, Snowflake, or Databricks using SSO.

---

# Primary Goal

Determine whether this authentication flow can be implemented using the existing Kyuubi architecture.

If not, identify all required modifications.

Do **not** assume that implementing an AuthenticationProvider alone is sufficient.

Research the complete authentication path before proposing an implementation.

---

# Research

Investigate:

- Kyuubi Authentication SPI
- Thrift authentication protocol
- Kyuubi JDBC Driver
- Hive JDBC authentication flow
- DBeaver authentication capabilities
- JDBC driver extension points
- OAuth2 Authorization Code Flow with PKCE
- OAuth2 Device Authorization Flow (RFC 8628) as an alternative

Answer the following questions:

1. Can Kyuubi Authentication SPI alone support browser-based SSO?

2. Does the JDBC driver need to participate in the OAuth flow?

3. Can DBeaver automatically launch a browser?

4. Where should the Authorization Code callback be handled?

5. How should the Access Token be transmitted to Kyuubi?

6. Is PKCE required?

7. Would Device Authorization Flow provide a better user experience?

---

# Design

If feasible, design a complete end-to-end authentication architecture.

The design should include:

- Browser launch
- Keycloak login
- Authorization Code exchange
- Access Token acquisition
- JWT validation
- Session establishment
- Token refresh strategy
- Logout behavior

Produce sequence diagrams describing the complete flow.

---

# Implementation

If the proposed design is feasible, implement the required changes.

Reuse existing Kyuubi components whenever possible.

Minimize modifications to existing authentication logic.

If changes are required outside Kyuubi Server (e.g., JDBC Driver), clearly separate those responsibilities.

---

# Requirements

The solution should:

- Support Keycloak initially.
- Be compatible with standard OIDC providers.
- Use OAuth2 Authorization Code Flow with PKCE when appropriate.
- Follow OIDC best practices.
- Be production-ready.
- Preserve backward compatibility.
- Require minimal changes to the existing architecture.

---

# Notes

Target version:

Apache Kyuubi 1.10.3

Primary OIDC Provider:

Keycloak

Do not begin implementation until the feasibility analysis and architecture review are complete.