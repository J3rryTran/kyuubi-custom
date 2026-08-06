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

# Notebook Architecture

## Delivery status

The subsystem is being delivered in four increments. **This document describes the whole target
design; only PR 1 is implemented today.** Anything marked *planned* does not exist in the code yet
and is not reachable over REST.

| Increment |                                                Scope                                                |     Status      |
|-----------|-----------------------------------------------------------------------------------------------------|-----------------|
| PR 1      | Domain, persistence, folder/notebook/cell/revision/permission/import/export/search/schedule         | **implemented** |
| PR 2      | Runtime adapter registry, Kyuubi SQL adapter, sessions, runtimes, executions, events, logs, results | **implemented** |
| PR 3      | CPython runtime, Python environments, package operations, sanitized rich output                     | **implemented** |
| PR 4      | Notebook UI: browser, cells, run, results, revisions, sharing, Python environments                  | **implemented** |
| PR 4b     | Run-all and stop-all, drag-to-reorder cells                                                         | planned         |

## Layering

```text
Browser
   |  existing Keycloak OIDC session (bearer token on /api/v1/*)
   v
Notebook REST resources        org.apache.kyuubi.server.api.v1
   |  thin: parse, resolve the caller, delegate
   v
Notebook services              org.apache.kyuubi.server.notebook.service
   |  NotebookDocumentService     folders, notebooks, cells
   |  NotebookRevisionService     snapshots, trimming
   |  NotebookPermissionService   role resolution, grants
   |  NotebookContentService      import, export, restore
   |  NotebookScheduleService     schedule contract
   |  NotebookSessionService      session lifecycle
   |  NotebookRuntimeService      runtime lifecycle
   |  NotebookExecutionService    submit, poll, cancel, close, events, logs, results
   |  PythonEnvironmentService    environments, revisions, package operations
   v
NotebookStore                  org.apache.kyuubi.server.notebook.store
   |  JDBCNotebookStore over the server metadata database
   v
RuntimeAdapterRegistry         org.apache.kyuubi.server.notebook.runtime
   |-- KyuubiSqlRuntimeAdapter -> BackendService -> Spark SQL
   +-- CpythonRuntimeAdapter   -> persistent CPython process per runtime
```

`NotebookManager` is the composition root. It is an `AbstractService` added to
`KyuubiRestFrontendService`, so it follows the server's normal initialize/start/stop lifecycle and
is absent entirely when `kyuubi.notebook.enabled` is false.

## Where identity comes from

There is no notebook login. `NotebookApiSupport.principal` builds a `NotebookPrincipal` from
`KyuubiRestFrontendService.getRealUser()`, which reads the name that the existing
`AuthenticationFilter` put in place after validating the OIDC bearer token, and from
`isAdministrator`, which reuses `kyuubi.server.administrators`.

No request body carries `owner`, `createdBy`, `updatedBy` or `submittedBy`. The request classes in
`NotebookRequests.scala` simply do not declare those fields, so there is nothing for Jackson to
bind and no code path where a client-supplied value could win.

## Persistence

The notebook tables live in the **same JDBC database as the server metadata store** and are
configured by the same `kyuubi.metadata.store.jdbc.*` properties. A deployment therefore has one
database to provision, back up and secure. Tables are namespaced with a `notebook_` prefix; the
schema is in `kyuubi-server/src/main/resources/sql/notebook/<dbtype>/`.

Three design points are worth knowing before changing the schema:

- **`path_hash`, not `path`, carries uniqueness.** A utf8mb4 `varchar(1024)` exceeds the InnoDB
  index width limit, so the unique index is on the SHA-256 of the path.
- **Soft delete rewrites the path.** Deleting sets `deleted = 1` and replaces `path`/`path_hash`
  with a tombstone that mixes in the row id. The live path becomes available again while the row
  is retained, and no partial index is required — which matters because MySQL has none.
- **Subtree moves are computed in Scala.** Renaming a folder rewrites every descendant path. The
  new paths are computed by the service and applied by the store in one transaction, because
  string concatenation is spelled differently in MySQL, PostgreSQL and SQLite.

## Paths and namespaces

Every folder and notebook lives at `/<owner>/<ancestor names...>/<own name>`. The path is derived,
never accepted from a client. Rooting it at the owner is what makes cross-user collisions
impossible and what lets "move" reject a relocation into another user's tree by a simple prefix
check (`NotebookPaths.ownerOf`).

## Optimistic concurrency

Every mutable row carries a `version`. A request may include the version it read; when it does,
the store's `UPDATE ... WHERE version = ?` decides, and a zero-row update becomes
`VERSION_CONFLICT` (HTTP 409). When the version is omitted, the current value is used, which is
last-write-wins — convenient for simple clients, and the reason the UI always sends it.

Editing a cell also bumps the owning notebook's version in the same transaction, so a client
watching the notebook sees that its content changed without polling every cell.

## What is deliberately not exposed

The REST layer never returns a Kyuubi session or operation handle, a Kyuubi server address, a
Jupyter kernel id, a kernel connection file, a runtime process id, a Python environment path or
any credential. Responses are built from the `*View` types in `NotebookViews.scala`, which is a
separate set of classes from the domain model precisely so that adding an internal field to the
domain cannot leak it by accident.

## Errors

All notebook failures are `NotebookException`, carrying a stable code from `NotebookErrorCode`.
`NotebookExceptionMapper` renders the documented envelope and assigns a request id; the cause is
logged, never serialized. See [API.md](API.md) for the envelope and the code-to-status mapping.

## How SQL runs

A notebook session owns one Kyuubi session per language runtime, and each execution is one
asynchronous Kyuubi operation. The adapter calls the same `BackendService` the REST session and
operation resources use, so no HTTP hop and no second credential are involved, and the handles
stay inside the adapter.

Three consequences are worth knowing:

- **Nothing is synchronous.** `executeStatement` is always called with `runAsync = true`; the
  submission returns as soon as the operation is accepted. Everything after that is polled.
- **Cleanup uses ordinary operations.** `cancelOperation`, `closeOperation` and `closeSession`,
  never the admin endpoints, so a notebook user needs no administrator rights.
- **The operation stays open until the client closes the execution.** That is what keeps logs and
  results readable after the statement finished; `POST /executions/{id}:close` is what releases
  them.

### Result paging

Kyuubi's result cursor moves forwards only. The execution service therefore owns the cursor
policy for every runtime: it remembers the page it last handed out, so re-requesting that cursor
returns the same rows, and any other jump is refused with `RESULT_EXPIRED` rather than silently
returning the wrong window. Adapters just read forward.

### Session affinity

A session records the Kyuubi instance that created it. Serving it from a different instance would
silently do nothing, because the runtime lives in the first one's memory, so it is refused with
`KYUUBI_SESSION_LOST`. Cross-instance proxying is not implemented; in a multi-instance
deployment either pin notebook traffic to one instance or expect users to open a new session
after a failover.

### After a restart

`NotebookManager.start()` reconciles: every non-terminal execution becomes `LOST` and every live
session becomes `LOST`, because nothing in the previous process survived. Unknown work is never
reported as successful.

## Web UI

The notebook view lives at `/ui/notebook` (`web-ui/src/views/notebook`) and reaches the server
only through `src/api/notebook`. It never calls `/api/v1/sessions`, `/api/v1/operations/*` or the
admin endpoint the old SQL editor uses for cleanup, so a notebook user needs no administrator
rights.

- `use-notebook.ts` owns the state: it opens a notebook, ensures a session on the first run,
  submits an execution and then polls. Polling rather than pushing is what makes a refresh
  survivable - on open, `attachRunning` reloads the notebook's executions, reattaches the latest
  one per cell and resumes polling anything still unfinished.
- Cells use a plain textarea, not one Monaco instance each: a notebook can hold dozens of cells,
  and the shared editor component reformats its content with a SQL formatter, which would mangle
  Python and Markdown.
- Logs are fetched from the offset already displayed, and results page forward through the opaque
  cursor, so neither is re-read from the start.
- A `PYTHON` cell is accepted and saved but its Run button stays disabled until a Python runtime
  spec reports itself enabled, which is what `GET /api/v1/runtime-specs` is consulted for.

Rich output and Python environments are wired in: a cell's `text/html` and `image/svg+xml`
render inside a sandboxed iframe, images as a `data:` URI, and the "Python env" dialog creates
environments, installs and removes packages, follows the build log and lists revisions. Activating
a revision raises a restart notice rather than restarting a live interpreter.

Not built yet: run-all/stop-all controls, and drag-and-drop cell reordering - the reorder endpoint
exists and is wired in `api/notebook`, but no gesture calls it.
