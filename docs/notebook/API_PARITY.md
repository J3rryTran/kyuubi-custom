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

# API Parity: Apache Zeppelin and Jupyter Server

Each capability is classified as:

- `SUPPORTED_DIRECTLY` - equivalent behaviour is reachable today.
- `MAPPED` - the capability exists but under a different shape or name.
- `INTENTIONALLY_DIFFERENT` - a deliberate divergence, with the reason given.
- `OUT_OF_SCOPE` - not planned for version 1.

Rows for capabilities that belong to PR 2/PR 3 are marked *(planned)*; they describe the intended
contract, not shipped behaviour.

## Apache Zeppelin Notebook REST API

|                Zeppelin capability                 |                 Kyuubi equivalent                 |                                                                     Class                                                                      |
|----------------------------------------------------|---------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/notebook` list notebooks                 | `GET /api/v1/notebooks`                           | `MAPPED` - cursor-paginated and authorization-filtered rather than a flat list                                                                 |
| `POST /api/notebook` create                        | `POST /api/v1/notebooks`                          | `SUPPORTED_DIRECTLY`                                                                                                                           |
| `GET /api/notebook/{id}`                           | `GET /api/v1/notebooks/{id}?includeCells=true`    | `SUPPORTED_DIRECTLY`                                                                                                                           |
| `DELETE /api/notebook/{id}`                        | `DELETE /api/v1/notebooks/{id}`                   | `MAPPED` - soft delete; the path is freed but the row is retained                                                                              |
| `POST /api/notebook/{id}` clone                    | `POST /api/v1/notebooks/{id}:clone`               | `SUPPORTED_DIRECTLY`                                                                                                                           |
| `PUT /api/notebook/{id}/rename`                    | `PATCH /api/v1/notebooks/{id}` with `name`        | `MAPPED`                                                                                                                                       |
| `PUT /api/notebook/{id}/move` (trash)              | `POST /api/v1/notebooks/{id}:move`                | `MAPPED` - moves between folders; there is no trash folder, delete is soft                                                                     |
| `PUT /api/notebook/{folder}/rename`                | `PATCH /api/v1/notebook-folders/{id}`             | `SUPPORTED_DIRECTLY` - descendant paths are rewritten atomically                                                                               |
| `POST /api/notebook/import`                        | `POST /api/v1/notebooks:import`                   | `SUPPORTED_DIRECTLY` - accepts native and `.ipynb`                                                                                             |
| `GET /api/notebook/export/{id}`                    | `GET /api/v1/notebooks/{id}:export`               | `SUPPORTED_DIRECTLY`                                                                                                                           |
| `POST /api/notebook/{id}/paragraph`                | `POST /api/v1/notebooks/{id}/cells`               | `MAPPED` - "paragraph" is "cell"                                                                                                               |
| `PUT /api/notebook/{id}/paragraph/{pid}`           | `PATCH /api/v1/notebooks/{id}/cells/{cid}`        | `MAPPED`                                                                                                                                       |
| `POST /api/notebook/{id}/paragraph/{pid}/move/{n}` | `PUT /api/v1/notebooks/{id}/cells:reorder`        | `INTENTIONALLY_DIFFERENT` - a whole-order PUT is idempotent and free of the intermediate states a single-index move produces under concurrency |
| `POST .../paragraph/{pid}/run` (async)             | `POST /api/v1/notebook-sessions/{sid}/executions` | `MAPPED` *(planned)* - execution is a first-class resource so it survives a refresh                                                            |
| `POST .../paragraph/{pid}/run` (sync)              | none                                              | `INTENTIONALLY_DIFFERENT` - a synchronous run ties a query's lifetime to an HTTP connection; poll the execution instead                        |
| `DELETE .../paragraph/{pid}`                       | `DELETE /api/v1/notebooks/{id}/cells/{cid}`       | `SUPPORTED_DIRECTLY`                                                                                                                           |
| `GET .../paragraph/{pid}` status                   | `GET /api/v1/executions/{eid}`                    | `MAPPED` *(planned)* - status belongs to the execution, not the cell, so history is preserved                                                  |
| `POST /api/notebook/job/{id}` run all              | `POST /api/v1/notebooks/{id}:run-all`             | `MAPPED` *(planned)*                                                                                                                           |
| `DELETE /api/notebook/job/{id}` stop all           | `POST /api/v1/notebooks/{id}:stop-all`            | `MAPPED` *(planned)*                                                                                                                           |
| `POST /api/notebook/cron/{id}`                     | `PUT /api/v1/notebooks/{id}/schedule`             | `MAPPED` - timezone is mandatory, and an overlap policy is explicit                                                                            |
| `GET /api/notebook/{id}/permissions`               | `GET /api/v1/notebooks/{id}/permissions`          | `MAPPED` - Zeppelin has readers/writers/runners/owners lists; Kyuubi has OWNER/EDITOR/VIEWER roles                                             |
| `PUT /api/notebook/{id}/permissions`               | `PUT /api/v1/notebooks/{id}/permissions`          | `MAPPED`                                                                                                                                       |
| Revisions (`/api/notebook/{id}/revision`)          | `/api/v1/notebooks/{id}/revisions`                | `SUPPORTED_DIRECTLY` - plus automatic revisions on edit                                                                                        |
| Interpreter management REST                        | none                                              | `OUT_OF_SCOPE` - runtimes are described by runtime specs; interpreter binding is a Kyuubi engine concern                                       |
| Credential API                                     | none                                              | `OUT_OF_SCOPE` - credentials stay with Kyuubi and Keycloak                                                                                     |
| Helium / display-system packages                   | none                                              | `OUT_OF_SCOPE`                                                                                                                                 |
| WebSocket notebook sync                            | long-polling `/executions/{id}/events`            | `INTENTIONALLY_DIFFERENT` *(planned)* - REST-only browser contract; replayable sequences survive a reconnect, which a socket does not          |

## Jupyter Server REST API

|                Jupyter capability                 |                      Kyuubi equivalent                       |                                                              Class                                                              |
|---------------------------------------------------|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/contents/{path}`                        | `GET /api/v1/notebooks/{id}`, `GET /api/v1/notebook-folders` | `INTENTIONALLY_DIFFERENT` - addressing is by opaque id, not by filesystem path, so no request string ever reaches a file lookup |
| `PUT /api/contents/{path}` create/save            | `POST /api/v1/notebooks`, `PATCH .../cells/{id}`             | `MAPPED`                                                                                                                        |
| `PATCH /api/contents/{path}` rename               | `PATCH /api/v1/notebooks/{id}`, `:move`                      | `MAPPED`                                                                                                                        |
| `DELETE /api/contents/{path}`                     | `DELETE /api/v1/notebooks/{id}`                              | `MAPPED`                                                                                                                        |
| `POST /api/contents/{path}/checkpoints`           | `POST /api/v1/notebooks/{id}/revisions`                      | `MAPPED` - checkpoints are revisions                                                                                            |
| `POST .../checkpoints/{cid}` restore              | `POST .../revisions/{n}:restore`                             | `MAPPED` - restore appends rather than rewinds                                                                                  |
| `DELETE .../checkpoints/{cid}`                    | `DELETE .../revisions/{n}`                                   | `MAPPED` - protected revisions cannot be deleted                                                                                |
| `GET /api/sessions`                               | `GET /api/v1/notebooks/{id}/sessions`                        | `MAPPED` *(planned)* - a session belongs to a notebook                                                                          |
| `POST /api/sessions`                              | `POST /api/v1/notebooks/{id}/sessions`                       | `MAPPED` *(planned)*                                                                                                            |
| `DELETE /api/sessions/{id}`                       | `DELETE /api/v1/notebook-sessions/{id}`                      | `MAPPED` *(planned)*                                                                                                            |
| `GET /api/kernels`                                | `GET /api/v1/notebook-sessions/{id}/runtimes`                | `MAPPED` *(planned)* - "kernel" is "runtime"; ids are notebook-scoped, never kernel ids                                         |
| `POST /api/kernels/{id}/interrupt`                | `POST /api/v1/notebook-runtimes/{id}:interrupt`              | `MAPPED` *(planned)*                                                                                                            |
| `POST /api/kernels/{id}/restart`                  | `POST /api/v1/notebook-runtimes/{id}:restart`                | `MAPPED` *(planned)*                                                                                                            |
| `GET /api/kernelspecs`                            | `GET /api/v1/runtime-specs`                                  | `MAPPED` *(planned)* - no startup command or argv is exposed                                                                    |
| Kernel WebSocket channel                          | `/executions/{id}/events`, `/logs`, `/outputs`               | `INTENTIONALLY_DIFFERENT` *(planned)* - see above                                                                               |
| `GET /api/kernels/{id}/connect` connection file   | none                                                         | `INTENTIONALLY_DIFFERENT` - a connection file is a direct, unauthenticated path to the kernel                                   |
| `GET /api/terminals`                              | none                                                         | `OUT_OF_SCOPE` - a terminal would defeat the package-installation policy                                                        |
| `GET /api/config/{section}`                       | `GET /api/v1/me`, `GET /api/v1/notebook-status`              | `MAPPED`                                                                                                                        |
| nbformat `.ipynb` documents                       | import and export                                            | `MAPPED` - source, cell type and language round-trip; stored outputs are dropped in both directions                             |
| Cell attachments and rich outputs in the document | none                                                         | `INTENTIONALLY_DIFFERENT` - an imported file is untrusted input, and its embedded HTML/SVG would be rendered for the importer   |
| `GET /api/contents` recursive listing             | `GET /api/v1/notebooks:search`                               | `MAPPED`                                                                                                                        |
| Extension / nbextension APIs                      | none                                                         | `OUT_OF_SCOPE`                                                                                                                  |

## Divergences worth restating

1. **Ids, not paths, in the API.** Both Zeppelin and Jupyter address documents by path. Kyuubi
   addresses by id and derives the path from the owner and folder chain. A client cannot construct
   a path, so traversal and cross-user collision are structurally impossible rather than filtered.
2. **Execution is a resource.** Neither reference product gives an execution a durable identity.
   Making it one is what allows a browser refresh, a server restart or a second tab to rejoin work
   in progress.
3. **No synchronous run.** Both products offer one. It is omitted because it couples a long query
   to an HTTP connection and gives no way to recover after a disconnect.
4. **Outputs never round-trip through a document.** Zeppelin and Jupyter both store results inside
   the notebook file. Kyuubi keeps outputs out of the document form, so importing a file from an
   untrusted source cannot inject renderable content.

