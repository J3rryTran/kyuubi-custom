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

# Notebook REST API

All endpoints live under `/api/v1` on the Kyuubi REST frontend and are authenticated by the
existing mechanism — under `kyuubi.authentication=OIDC` that is `Authorization: Bearer <token>`.
There is no notebook-specific login.

Endpoints marked *(planned)* are specified here but not implemented; see
[ARCHITECTURE.md](ARCHITECTURE.md) for the increment they belong to.

## Configuration

|                     Key                     |  Default   |                      Meaning                      |
|---------------------------------------------|------------|---------------------------------------------------|
| `kyuubi.notebook.enabled`                   | `true`     | Turns the subsystem and its endpoints on          |
| `kyuubi.notebook.store.schema.init`         | `true`     | Creates the notebook tables at startup if missing |
| `kyuubi.notebook.cell.source.max.size`      | `1048576`  | Maximum bytes of one cell source                  |
| `kyuubi.notebook.max.cells`                 | `500`      | Maximum cells per notebook                        |
| `kyuubi.notebook.max.page.size`             | `200`      | Upper bound for `limit`                           |
| `kyuubi.notebook.revision.auto.enabled`     | `true`     | Snapshot on every content change                  |
| `kyuubi.notebook.revision.max.per.notebook` | `100`      | Retained unprotected revisions                    |
| `kyuubi.notebook.import.max.size`           | `16777216` | Maximum bytes of an imported document             |

State is stored in the server metadata database, configured by `kyuubi.metadata.store.jdbc.*`.

## Conventions

**Ownership is never in the request.** `owner`, `createdBy`, `updatedBy` and `submittedBy` come
from the authenticated caller. A body containing them is not rejected; the fields simply do not
exist in the request model and are ignored.

**Optimistic locking.** Mutable resources carry `version`. Send the version you read to make the
write conditional; a mismatch is `409 VERSION_CONFLICT`. Omitting it means last-write-wins.

**Pagination.** List endpoints take `cursor` and `limit` and return
`{"items": [...], "nextCursor": "...", "hasMore": true}`. The cursor is opaque; do not parse it.

**Not found vs forbidden.** A notebook the caller may not read reports `404 NOTEBOOK_NOT_FOUND`,
not 403, so that id probing cannot confirm existence. `403 ACCESS_DENIED` appears only when the
caller can already see the object but lacks the role for that particular operation.

## Error envelope

```json
{
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "notebook 7ab3... was modified concurrently",
    "requestId": "0f1c9f7e-...",
    "retryable": false,
    "details": {}
  }
}
```

`message` is safe for display. Stack traces, secrets, internal handles and filesystem paths are
never included; the underlying cause is logged against `requestId`.

| HTTP |                                                                                             Codes                                                                                              |
|------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 400  | `INVALID_REQUEST`, `UNSUPPORTED_LANGUAGE`, and any code not listed below                                                                                                                       |
| 403  | `ACCESS_DENIED`                                                                                                                                                                                |
| 404  | `NOTEBOOK_NOT_FOUND`, `FOLDER_NOT_FOUND`, `CELL_NOT_FOUND`, `NOTEBOOK_SESSION_NOT_FOUND`, `RUNTIME_SPEC_NOT_FOUND`, `RUNTIME_NOT_FOUND`, `EXECUTION_NOT_FOUND`, `PYTHON_ENVIRONMENT_NOT_FOUND` |
| 409  | `PATH_CONFLICT`, `VERSION_CONFLICT`, `PYTHON_ENVIRONMENT_BUSY`, `RUNTIME_RESTART_REQUIRED`                                                                                                     |
| 429  | `RATE_LIMITED`                                                                                                                                                                                 |
| 500  | `INTERNAL_ERROR`                                                                                                                                                                               |
| 503  | `KYUUBI_UNAVAILABLE`, `KYUUBI_SESSION_LOST`, `KYUUBI_OPERATION_LOST`, `PYTHON_RUNTIME_UNAVAILABLE`, `PACKAGE_INDEX_UNAVAILABLE`, `RUNTIME_LOST`, `NOTEBOOK_DISABLED`                           |
| 504  | `EXECUTION_TIMEOUT`                                                                                                                                                                            |
| 507  | `PYTHON_ENVIRONMENT_QUOTA_EXCEEDED`                                                                                                                                                            |

## Folders

```http
POST   /api/v1/notebook-folders            {"name": "reports", "parentId": null}
GET    /api/v1/notebook-folders?parentId=
GET    /api/v1/notebook-folders/{folderId}
PATCH  /api/v1/notebook-folders/{folderId} {"name": "...", "parentId": "...", "version": 3}
DELETE /api/v1/notebook-folders/{folderId}?version=3
```

`parentId` on `PATCH` reparents: an explicit empty string moves the folder to the caller's root,
and omitting the field leaves the parent alone. Renaming or reparenting rewrites every descendant
path in one transaction. Deleting cascades to the whole subtree as a soft delete, and the freed
paths become immediately reusable.

`GET` with no `parentId` lists all of the caller's folders; `parentId=` (empty) lists the roots.

## Notebooks

```http
POST   /api/v1/notebooks
GET    /api/v1/notebooks?owner=&folderId=&name=&cursor=&limit=
GET    /api/v1/notebooks/{notebookId}?includeCells=true
PATCH  /api/v1/notebooks/{notebookId}
DELETE /api/v1/notebooks/{notebookId}?version=
POST   /api/v1/notebooks/{notebookId}:clone   {"name": "copy", "folderId": null}
POST   /api/v1/notebooks/{notebookId}:move    {"folderId": "...", "name": "...", "version": 2}
GET    /api/v1/notebooks/{notebookId}:export?format=KYUUBI|IPYNB
POST   /api/v1/notebooks:import
GET    /api/v1/notebooks:search?q=&folderId=&language=&cursor=&limit=
```

Create accepts inline cells:

```json
{
  "name": "daily-revenue",
  "folderId": null,
  "description": "revenue by region",
  "defaultCatalog": "spark_catalog",
  "defaultSchema": "analytics",
  "cells": [
    {"cellType": "MARKDOWN", "language": "MARKDOWN", "source": "# Daily revenue"},
    {"cellType": "CODE", "language": "SQL", "source": "select * from sales"}
  ]
}
```

A response carries `role` — the caller's effective role — alongside the notebook fields.

`:move` cannot relocate a notebook into another user's space; the target folder would not be
visible to the caller in the first place. Deleting a notebook also drops its permission grants and
schedule, so a later notebook that reuses the path cannot inherit them.

Search matches the notebook name, its description and its cell sources, and returns only
notebooks the caller may read. `%` and `_` in `q` are matched literally.

Import accepts `{"format": "KYUUBI"|"IPYNB", "name": "...", "folderId": "...", "content": "<json>"}`;
the format is inferred from the payload when omitted. New ids are generated, the caller becomes the
owner, no permission from the payload is honoured, and outputs are discarded.

## Cells

```http
GET    /api/v1/notebooks/{notebookId}/cells
POST   /api/v1/notebooks/{notebookId}/cells
GET    /api/v1/notebooks/{notebookId}/cells/{cellId}
PATCH  /api/v1/notebooks/{notebookId}/cells/{cellId}
DELETE /api/v1/notebooks/{notebookId}/cells/{cellId}
PUT    /api/v1/notebooks/{notebookId}/cells:reorder   {"cellIds": [...], "version": 5}
GET    /api/v1/notebooks/{notebookId}/cells/{cellId}/config
PATCH  /api/v1/notebooks/{notebookId}/cells/{cellId}/config
```

`cellType` is `CODE` or `MARKDOWN`. `language` is `SQL`, `PYTHON` or `MARKDOWN`; a `CODE` cell must
use an executable language and a `MARKDOWN` cell must use `MARKDOWN`.

`position` is optional on create and defaults to the end. Cells at or after it shift down, so
positions stay contiguous. `cells:reorder` requires the complete list of cell ids exactly once
each; a partial list is `400 INVALID_REQUEST`.

Any cell mutation also bumps the notebook version.

```http
DELETE /api/v1/notebooks/{notebookId}/outputs                (planned)
DELETE /api/v1/notebooks/{notebookId}/cells/{cellId}/outputs (planned)
```

These arrive with the output store; there is no output to clear until then.

## Revisions

```http
GET    /api/v1/notebooks/{notebookId}/revisions?cursor=&limit=
POST   /api/v1/notebooks/{notebookId}/revisions        {"reason": "before refactor"}
GET    /api/v1/notebooks/{notebookId}/revisions/{revisionNumber}
POST   /api/v1/notebooks/{notebookId}/revisions/{revisionNumber}:restore
DELETE /api/v1/notebooks/{notebookId}/revisions/{revisionNumber}
```

Revisions are created automatically on every meaningful change and manually by `POST`. A manual
checkpoint and the revision produced by a restore are **protected**: trimming skips them and
`DELETE` refuses them with `400 INVALID_REQUEST`.

Restore does not rewind. It replaces the current content and appends a new protected revision, so
the pre-restore state stays in the history.

`GET` on a single revision includes the full `document`; the list form omits it.

## Permissions

```http
GET /api/v1/notebooks/{notebookId}/permissions
PUT /api/v1/notebooks/{notebookId}/permissions
```

```json
{"permissions": [{"principalType": "USER", "principalId": "bob", "role": "EDITOR"}]}
```

`PUT` replaces the whole grant list. Only the owner or an administrator may call it.

|   Role   |                      May                       |
|----------|------------------------------------------------|
| `OWNER`  | read, update, execute, share, delete           |
| `EDITOR` | read, update, execute                          |
| `VIEWER` | read saved content and permitted saved outputs |

`OWNER` cannot be granted — it follows the notebook's owner. `GROUP` principals are **rejected**
rather than stored, because there is no group source to evaluate them against and a grant that
silently never applies is worse than an error.

Sharing a notebook does not share the owner's runtimes or Python environment; those are checked
separately by their own services.

## Schedule

```http
GET    /api/v1/notebooks/{notebookId}/schedule
PUT    /api/v1/notebooks/{notebookId}/schedule
DELETE /api/v1/notebooks/{notebookId}/schedule
```

```json
{
  "cronExpression": "0 3 * * *",
  "timezone": "Asia/Ho_Chi_Minh",
  "enabled": true,
  "failurePolicy": "STOP_ON_ERROR",
  "overlapPolicy": "SKIP_IF_RUNNING",
  "version": 1
}
```

`timezone` is mandatory and must be a known zone id — a cron without one silently follows the
server's zone and changes meaning when the server moves. The 5-field cron is validated at write
time.

**Storing a schedule does not yet start runs.** This is the contract only; triggering arrives with
the notebook-run services.

## Sessions, runtimes, executions, outputs *(planned)*

```http
POST   /api/v1/notebooks/{notebookId}/sessions
GET    /api/v1/notebooks/{notebookId}/sessions
GET    /api/v1/notebook-sessions/{sessionId}
DELETE /api/v1/notebook-sessions/{sessionId}
POST   /api/v1/notebook-sessions/{sessionId}:restart|:reset|:stop

GET    /api/v1/runtime-specs
GET    /api/v1/runtime-specs/{runtimeSpecId}
GET    /api/v1/notebook-sessions/{sessionId}/runtimes
POST   /api/v1/notebook-sessions/{sessionId}/runtimes
GET    /api/v1/notebook-runtimes/{runtimeId}
POST   /api/v1/notebook-runtimes/{runtimeId}:interrupt|:restart|:stop
DELETE /api/v1/notebook-runtimes/{runtimeId}

POST   /api/v1/notebook-sessions/{sessionId}/executions
GET    /api/v1/executions/{executionId}
POST   /api/v1/executions/{executionId}:cancel|:close
GET    /api/v1/executions/{executionId}/events?afterSequence=&waitMillis=&limit=
GET    /api/v1/executions/{executionId}/logs?offset=&maxBytes=
GET    /api/v1/executions/{executionId}/outputs?afterSequence=&limit=
GET    /api/v1/executions/{executionId}/schema
GET    /api/v1/executions/{executionId}/results?cursor=&maxRows=

POST   /api/v1/notebooks/{notebookId}:run-all|:stop-all
GET    /api/v1/notebooks/{notebookId}/runs
GET    /api/v1/notebook-runs/{runId}

POST   /api/v1/python-environments
GET    /api/v1/python-environments
...
```

See [LIFECYCLE.md](LIFECYCLE.md) for the state machines these will follow.

## Service endpoints

```http
GET /api/v1/me
GET /api/v1/notebook-status
```

`/me` returns the authenticated identity, admin flag and capability booleans. It never returns a
token.

```json
{
  "user": "alice",
  "admin": false,
  "permissions": {
    "manageNotebooks": true,
    "manageRuntimes": false,
    "managePythonEnvironments": false
  }
}
```

`/notebook-status` reports sanitized health. Subsystems that do not exist yet report
`NOT_IMPLEMENTED` rather than a zero count, so a monitor cannot read "no runtimes" as "healthy and
idle".

```json
{
  "notebookService": "HEALTHY",
  "persistence": "HEALTHY",
  "kyuubiSql": "NOT_IMPLEMENTED",
  "pythonRuntimeManager": "NOT_IMPLEMENTED",
  "activeSessions": 0,
  "activeRuntimes": 0,
  "queuedExecutions": 0
}
```

The OpenAPI document for the whole REST surface is served at `/api/v1/openapi.json` by the
existing Kyuubi OpenAPI resource; a hand-maintained notebook-only specification is in
[openapi.yaml](openapi.yaml).
