<!--
- Licensed to the Apache Software Foundation (ASF) under one or more
- contributor license agreements.  See the NOTICE file distributed with
- this work for additional information regarding copyright ownership.
- The ASF licenses this file to You under the Apache License, Version 2.0
- (the "License"); you may not use this file except in compliance with
- the License.  You may obtain a copy of the License at
-
-   http://www.apache.org/licenses/LICENSE-2.0
-
- Unless required by applicable law or agreed to in writing, software
- distributed under the License is distributed on an "AS IS" BASIS,
- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
- See the License for the specific language governing permissions and
- limitations under the License.
-->

# Notebook Gap Analysis

What the repository already provided before this work, and what had to be built.

## Authentication - reusable as is

`AuthenticationFilter` validates the request and publishes the authenticated name in a
`ThreadLocal`; `KyuubiRestFrontendService.getRealUser()` reads it, and `isAdministrator` answers
the admin question from `kyuubi.server.administrators`.

This fork already routes `kyuubi.authentication=OIDC` to a JWT bearer provider, and the Web UI
performs its own Authorization Code + PKCE login. **Nothing about authentication needed to
change** — the notebook layer consumes the existing security context and adds authorization on
top of it.

## REST plumbing - reusable, one extension needed

Jersey scans `org.apache.kyuubi.server.api.v1`, and `ApiRootResource` dispatches to sub-resources
by `@Path` locator. New resources drop straight in.

One gap: the plan's collection actions `notebooks:import` and `notebooks:search` are *siblings* of
`notebooks`, not children, so they cannot live inside a resource rooted at `notebooks`. They are
registered as their own locators on `ApiRootResource`, and the per-notebook action paths use the
`{notebookId: [^:/]+}` template so `{id}` cannot swallow `{id}:clone`.

`RestExceptionMapper` returns `{"message": ...}` for everything, which does not satisfy the error
contract, so `NotebookExceptionMapper` was added for `NotebookException` and registered ahead of
it.

## Persistence - pattern reusable, no schema reusable

`JDBCMetadataStore` establishes the house style: HikariCP, `JdbcUtils`, versioned DDL per dialect
under `src/main/resources/sql/`, idempotent schema init. `MetadataManager` shows the
`AbstractService` lifecycle.

The `metadata` table itself models batch jobs and is unrelated to notebooks, so six new tables
were added. They reuse the *same connection settings* rather than introducing a second database.

Two portability gaps had to be handled that the existing store does not hit:

- MySQL has no `CREATE INDEX IF NOT EXISTS`, so the MySQL DDL declares secondary indexes inline
  while PostgreSQL and SQLite use `CREATE INDEX IF NOT EXISTS`.
- SQLite has no default `LIKE` escape character and the three dialects disagree about backslash
  handling in string literals, so every `LIKE` pairs with an explicit `ESCAPE '~'`.

## Existing Editor frontend - to be replaced in PR 4

`web-ui/src/api/editor/index.ts` calls `/api/v1/sessions`, `/api/v1/sessions/{id}/operations/statement`,
`/api/v1/operations/{id}/rowset`, `/api/v1/operations/{id}/resultsetmetadata`,
`/api/v1/operations/{id}/log` and — for cleanup — `/api/v1/admin/operations/{id}`.

Three problems make it unusable as the notebook client:

1. It uses an **admin** endpoint for ordinary operation cleanup, which every notebook user would
   need admin rights to reach.
2. Kyuubi session and operation handles are held in the browser, which the notebook contract
   forbids.
3. There is no persistence: a refresh loses the session, and nothing survives a server restart.

`Editor.vue` (421 lines) is a single-statement editor with no notion of cells, ordering, or
per-cell language, so PR 4 replaces rather than extends it.

## Summary of what PR 1 added

|      Area      |                  Gap                  |                                  Resolution                                  |
|----------------|---------------------------------------|------------------------------------------------------------------------------|
| Domain model   | none existed                          | `notebook/api` - entities, enums, document form, error codes                 |
| Persistence    | no notebook tables                    | `notebook/store` - trait plus JDBC implementation, DDL for 3 dialects        |
| Authorization  | server-wide only, no per-object roles | `NotebookPermissionService` - owner/editor/viewer over the existing identity |
| Path model     | none                                  | `NotebookPaths` - owner-rooted derived paths, hashed uniqueness key          |
| Error contract | generic `{"message"}`                 | `NotebookErrorCode` plus `NotebookExceptionMapper`                           |
| Config         | none                                  | `kyuubi.notebook.*`, documented in `docs/configuration/settings.md`          |

## Still open

Everything runtime-related: sessions, runtimes, executions, events, logs, outputs, SQL results,
run-all, the CPython manager, Python environments, and the UI. See [ARCHITECTURE.md](ARCHITECTURE.md)
for the increment each belongs to.
