# Task: Implement SQL + CPython Notebook Subsystem

## Objective

Implement a production-ready Notebook subsystem integrated into the existing Kyuubi Web UI.

Version 1 must support:

* SQL cells through Kyuubi REST.
* Python cells through a real CPython runtime.
* Existing Keycloak OIDC authentication.
* Notebook, cell, runtime and execution lifecycle.
* User-managed isolated Python environments.
* REST APIs comparable to relevant Zeppelin and Jupyter Notebook APIs.
* Persistence, recovery, security, OpenAPI and automated tests.

This is not a UI-only task.

---

# 1. Authentication

The existing Keycloak OIDC login already works.

Requirements:

* One Keycloak login must grant access to Notebook features.
* Do not add another Notebook login.
* Do not add manual token entry.
* Do not add password grant.
* Do not redesign the existing OIDC flow.
* Use the authenticated server-side security context.
* Derive `owner`, `createdBy`, `updatedBy` and `submittedBy` from the authenticated identity.
* Do not accept user ownership fields from request bodies.
* Do not expose Kyuubi credentials, Keycloak secrets or package-index credentials to the browser.
* Preserve existing logout and token-expiration behavior.

Notebook authorization must extend the existing authentication system, not replace it.

---

# 2. Architecture

```text
Browser
   |
   | Existing Keycloak session
   | Notebook REST API
   v
Notebook REST Resources
   |
   v
Notebook Services
   |-- NotebookContentService
   |-- NotebookDocumentService
   |-- NotebookRevisionService
   |-- NotebookPermissionService
   |-- NotebookSessionService
   |-- NotebookRuntimeService
   |-- NotebookExecutionService
   |-- NotebookOutputService
   |-- NotebookScheduleService
   |-- PythonEnvironmentService
   |
   v
RuntimeAdapterRegistry
   |-- KyuubiSqlRuntimeAdapter
   |-- CpythonRuntimeAdapter
   |
   +-------------------------+
   |                         |
   v                         v
Kyuubi REST              CPython Runtime Manager
   |                         |
   v                         v
Spark SQL                Isolated ipykernel/CPython
```

Browser-facing communication must use REST.

Do not expose:

* Kyuubi session handles.
* Kyuubi operation handles.
* Kyuubi server addresses.
* Jupyter kernel IDs.
* Kernel connection files.
* Runtime process IDs.
* Python environment paths.
* Internal credentials.

---

# 3. Repository audit

Before implementation, inspect:

* Root `pom.xml`.
* All `AGENTS.md` files.
* Existing REST resources and registration.
* Authentication and authorization filters.
* Session and operation REST APIs.
* Existing persistence mechanisms.
* Existing tests.
* Existing Editor frontend:

  * `kyuubi-server/web-ui/src/api/editor/index.ts`
  * `kyuubi-server/web-ui/src/api/editor/types.ts`
  * `kyuubi-server/web-ui/src/views/editor/index.vue`
  * `kyuubi-server/web-ui/src/views/editor/components/Editor.vue`

Create:

```text
docs/notebook/GAP_ANALYSIS.md
docs/notebook/API_PARITY.md
docs/notebook/ARCHITECTURE.md
docs/notebook/API.md
docs/notebook/LIFECYCLE.md
docs/notebook/PYTHON_SECURITY.md
docs/notebook/openapi.yaml
```

`API_PARITY.md` must compare relevant APIs from:

* Apache Zeppelin Notebook REST API.
* Jupyter Server REST API.

Classify each capability as:

```text
SUPPORTED_DIRECTLY
MAPPED
INTENTIONALLY_DIFFERENT
OUT_OF_SCOPE
```

---

# 4. Domain models

## NotebookFolder

```text
id
parentId
name
path
owner
createdAt
createdBy
updatedAt
updatedBy
version
deleted
```

## Notebook

```text
id
folderId
path
name
description
owner
defaultCatalog
defaultSchema
runtimeProfile
formatVersion
createdAt
createdBy
updatedAt
updatedBy
version
deleted
```

## NotebookCell

```text
id
notebookId
position
type
language
source
metadata
configuration
createdAt
updatedAt
version
```

Cell types:

```text
CODE
MARKDOWN
```

Executable languages:

```text
SQL
PYTHON
```

## NotebookRevision

```text
id
notebookId
revisionNumber
documentSnapshot
createdAt
createdBy
reason
```

## NotebookPermission

```text
notebookId
principalType
principalId
role
createdAt
createdBy
```

Roles:

```text
OWNER
EDITOR
VIEWER
```

## NotebookSession

```text
id
notebookId
owner
state
runtimeProfile
createdAt
lastActivityAt
stoppedAt
failureMessage
version
```

States:

```text
CREATING
IDLE
BUSY
RESETTING
STOPPING
STOPPED
LOST
FAILED
```

## NotebookRuntime

```text
id
notebookSessionId
runtimeSpecId
runtimeType
language
owner
state
generation
environmentRevisionId
createdAt
lastActivityAt
stoppedAt
failureMessage
internalRuntimeHandle
internalRuntimeLocation
version
```

States:

```text
CREATING
IDLE
BUSY
INTERRUPTING
RESTARTING
STOPPING
STOPPED
LOST
FAILED
```

Internal runtime fields must never appear in public responses.

## CellExecution

```text
id
notebookId
notebookSessionId
runtimeId
cellId
cellVersion
language
sourceSnapshot
state
submittedAt
startedAt
finishedAt
submittedBy
errorCode
errorMessage
version
```

States:

```text
QUEUED
STARTING
RUNNING
CANCELING
CANCELED
SUCCEEDED
FAILED
CLOSED
LOST
```

## ExecutionOutput

```text
sequence
executionId
outputType
stream
mimeType
data
artifactId
metadata
createdAt
```

Output types:

```text
STREAM
TEXT
TABLE
DISPLAY_DATA
EXECUTE_RESULT
ERROR
IMAGE
JSON
HTML
```

## NotebookRun

```text
id
notebookId
notebookSessionId
state
submittedAt
startedAt
finishedAt
submittedBy
requestedCellIds
currentCellId
failurePolicy
version
```

## NotebookSchedule

```text
id
notebookId
cronExpression
timezone
enabled
runtimeProfile
failurePolicy
overlapPolicy
lastRunAt
nextRunAt
createdAt
createdBy
updatedAt
updatedBy
version
```

---

# 5. Notebook and folder APIs

## Folder APIs

```http
POST   /api/v1/notebook-folders
GET    /api/v1/notebook-folders
GET    /api/v1/notebook-folders/{folderId}
PATCH  /api/v1/notebook-folders/{folderId}
DELETE /api/v1/notebook-folders/{folderId}
```

## Notebook APIs

```http
POST   /api/v1/notebooks
GET    /api/v1/notebooks
GET    /api/v1/notebooks/{notebookId}
PATCH  /api/v1/notebooks/{notebookId}
DELETE /api/v1/notebooks/{notebookId}

POST /api/v1/notebooks/{notebookId}:clone
POST /api/v1/notebooks/{notebookId}:move
```

Requirements:

* Pagination.
* Name, owner and folder filtering.
* Optimistic locking.
* HTTP `409` for version or path conflict.
* Soft delete unless an existing project convention requires otherwise.
* Atomic move, rename and reorder.
* Deterministic paths.
* No cross-user namespace movement.

---

# 6. Cell APIs

```http
POST   /api/v1/notebooks/{notebookId}/cells
GET    /api/v1/notebooks/{notebookId}/cells/{cellId}
PATCH  /api/v1/notebooks/{notebookId}/cells/{cellId}
DELETE /api/v1/notebooks/{notebookId}/cells/{cellId}
PUT    /api/v1/notebooks/{notebookId}/cells:reorder

GET    /api/v1/notebooks/{notebookId}/cells/{cellId}/config
PATCH  /api/v1/notebooks/{notebookId}/cells/{cellId}/config

DELETE /api/v1/notebooks/{notebookId}/outputs
DELETE /api/v1/notebooks/{notebookId}/cells/{cellId}/outputs
```

Requirements:

* Cell source size limit.
* Atomic reorder.
* Immutable execution source snapshots.
* Updating a cell must not alter a running or completed execution.

---

# 7. Import, export and search

## Import/export

```http
POST /api/v1/notebooks:import
GET  /api/v1/notebooks/{notebookId}:export
```

Supported formats:

```text
Native Kyuubi Notebook JSON
Jupyter .ipynb
```

Requirements:

* Generate new internal IDs on import.
* Validate notebook and cell sizes.
* Sanitize metadata and rich outputs.
* Never trust imported ownership or permissions.
* Never export internal handles, credentials or filesystem paths.

## Search

```http
GET /api/v1/notebooks:search
    ?q=
    &folderId=
    &language=
    &cursor=
    &limit=
```

Search:

* Notebook names.
* Descriptions.
* Cell source.
* Safe metadata fields.

Only return authorized notebooks.

---

# 8. Revisions and checkpoints

```http
GET    /api/v1/notebooks/{notebookId}/revisions
POST   /api/v1/notebooks/{notebookId}/revisions
GET    /api/v1/notebooks/{notebookId}/revisions/{revisionNumber}
POST   /api/v1/notebooks/{notebookId}/revisions/{revisionNumber}:restore
DELETE /api/v1/notebooks/{notebookId}/revisions/{revisionNumber}
```

Requirements:

* Automatic revisions for meaningful changes.
* Manual checkpoint creation.
* Restore creates a new current revision.
* Restore does not delete history.
* Optimistic concurrency.
* Audit metadata.
* Protected audit revisions cannot be deleted.

---

# 9. Permissions

```http
GET /api/v1/notebooks/{notebookId}/permissions
PUT /api/v1/notebooks/{notebookId}/permissions
```

Rules:

* Owner: read, update, execute, share and delete.
* Editor: read, update and execute.
* Viewer: read saved content and permitted saved outputs.
* Only owner or admin changes permissions.
* Runtime, logs and execution access require separate ownership checks.
* Sharing a notebook does not share the owner's private Python environment.

---

# 10. Notebook sessions

```http
POST   /api/v1/notebooks/{notebookId}/sessions
GET    /api/v1/notebooks/{notebookId}/sessions
GET    /api/v1/notebook-sessions/{sessionId}
DELETE /api/v1/notebook-sessions/{sessionId}

POST /api/v1/notebook-sessions/{sessionId}:restart
POST /api/v1/notebook-sessions/{sessionId}:reset
POST /api/v1/notebook-sessions/{sessionId}:stop
```

## Restart

* Cancel active executions.
* Restart SQL and Python runtimes.
* Preserve notebook content, history and saved outputs.
* Increment runtime generation.
* Clear in-memory runtime state.

## Reset

* Perform restart.
* Clear temporary session state.
* Optionally clear unsaved outputs.
* Never delete notebook source or persisted artifacts silently.

## Stop

* Cancel active executions.
* Close Kyuubi operations and sessions.
* Stop CPython runtimes.
* Release runtime resources.
* Preserve notebook and execution history.
* Be idempotent.

---

# 11. Runtime specifications

```http
GET /api/v1/runtime-specs
GET /api/v1/runtime-specs/{runtimeSpecId}
```

Initial runtime specs:

```text
Kyuubi SQL
CPython 3
```

Runtime specs may expose:

* Display name.
* Language.
* Version.
* Enabled status.
* User-selectable configuration.
* Resource limits.

Do not expose internal startup commands or secrets.

---

# 12. Runtime APIs

```http
GET  /api/v1/notebook-sessions/{sessionId}/runtimes
POST /api/v1/notebook-sessions/{sessionId}/runtimes

GET    /api/v1/notebook-runtimes/{runtimeId}
POST   /api/v1/notebook-runtimes/{runtimeId}:interrupt
POST   /api/v1/notebook-runtimes/{runtimeId}:restart
POST   /api/v1/notebook-runtimes/{runtimeId}:stop
DELETE /api/v1/notebook-runtimes/{runtimeId}
```

Requirements:

* Idempotent lifecycle operations.
* Interrupt preserves runtime state where supported.
* Restart clears variables and increments generation.
* Stop releases resources.
* Runtime ownership validation.
* SQL and Python runtimes may coexist in one notebook session.

---

# 13. Runtime adapters

Implement:

```scala
trait NotebookRuntimeAdapter {
  def runtimeType: String
  def startRuntime(...): AdapterRuntime
  def getRuntimeStatus(...): AdapterRuntimeStatus
  def execute(...): AdapterExecution
  def interruptExecution(...): Unit
  def restartRuntime(...): AdapterRuntime
  def stopRuntime(...): Unit
  def fetchEvents(...): Seq[AdapterEvent]
  def fetchLogs(...): AdapterLogPage
  def fetchOutputs(...): AdapterOutputPage
}
```

For tabular runtimes:

```scala
trait TabularNotebookRuntimeAdapter extends NotebookRuntimeAdapter {
  def fetchSchema(...): AdapterSchema
  def fetchResults(...): AdapterResultPage
}
```

Implement:

```text
RuntimeAdapterRegistry
KyuubiSqlRuntimeAdapter
CpythonRuntimeAdapter
```

REST resources must remain thin.

---

# 14. SQL runtime

SQL execution must use existing Kyuubi REST primitives:

* Create/close session.
* Submit async statement.
* Read operation state.
* Fetch logs.
* Fetch schema.
* Fetch result pages.
* Cancel operation.
* Close operation.

Requirements:

* Use asynchronous execution.
* Do not use `runAsync: false`.
* Do not use admin APIs for normal operation cleanup.
* Keep Kyuubi handles private.
* Store and honor `kyuubiInstance`.
* Normalize Kyuubi states.
* Handle session affinity and recovery.
* Do not load entire results into server or browser memory.

---

# 15. Real CPython runtime

Implement a real persistent CPython interpreter.

Recommended implementation:

```text
Isolated ipykernel managed by an internal runtime manager
```

Requirements:

* Supported CPython 3 version.
* Persistent variables between cells in one runtime generation.
* Async execution.
* Interrupt.
* Restart.
* Stop.
* stdout.
* stderr.
* Expression results.
* Exceptions and traceback.
* Rich MIME outputs.
* CPU limit.
* Memory limit.
* Process-count limit.
* Execution timeout.
* Idle timeout.
* Unprivileged user/container identity.
* Isolated writable working directory.
* Cross-user filesystem isolation.
* Child-process cleanup.
* No host container-runtime socket.
* No root package installation.

Do not implement:

* Fake Python evaluation.
* Jython.
* Python inside the Kyuubi JVM.
* One process per cell.
* A shared unrestricted Python process.
* A stateless interpreter.

On service restart:

* Reconnect to a valid runtime when safe.
* Otherwise mark runtime and unfinished executions `LOST`.
* Never mark unknown work successful.

Python variables do not need to survive runtime restart.

---

# 16. Execution APIs

```http
POST /api/v1/notebook-sessions/{sessionId}/executions

GET /api/v1/executions/{executionId}
GET /api/v1/notebook-sessions/{sessionId}/executions
GET /api/v1/notebooks/{notebookId}/executions

POST /api/v1/executions/{executionId}:cancel
POST /api/v1/executions/{executionId}:close
```

Request:

```json
{
  "cellId": "cell-id",
  "runtimeId": "optional-runtime-id",
  "language": "PYTHON",
  "source": "print('hello')",
  "clientRequestId": "stable-id",
  "executionTimeoutSeconds": 300,
  "configuration": {}
}
```

Requirements:

* Return immediately after accepting execution.
* Idempotency through `clientRequestId`.
* Same ID with different payload returns HTTP `409`.
* Immutable source snapshot.
* Validate runtime language.
* Validate ownership.
* Reject unsupported configuration.
* Pollable after browser refresh.

---

# 17. Events, logs and outputs

## Events

```http
GET /api/v1/executions/{executionId}/events
    ?afterSequence=
    &waitMillis=
    &limit=
```

Requirements:

* Long polling.
* Stable increasing sequence.
* Empty response after timeout.
* Replay after reconnect.
* Duplicate delivery allowed.
* Ordered delivery required.

## Logs

```http
GET /api/v1/executions/{executionId}/logs
    ?offset=
    &maxBytes=
```

Requirements:

* Incremental logs.
* Next offset.
* Response size limit.
* Secret redaction.
* Retention after completion.

## Generic outputs

```http
GET /api/v1/executions/{executionId}/outputs
    ?afterSequence=
    &limit=
```

Requirements:

* stdout and stderr distinction.
* Rich MIME output.
* Sanitized HTML and SVG.
* Large binary output stored as artifacts.
* Stable sequence.
* Authorized access.

## SQL results

```http
GET /api/v1/executions/{executionId}/schema
GET /api/v1/executions/{executionId}/results
    ?cursor=
    &maxRows=
```

Requirements:

* Opaque cursor.
* Configurable page-size maximum.
* Correct null and datatype handling.
* `hasMore`.
* Stable next cursor.
* Empty and non-result statements supported.
* Python execution without a tabular result returns `NO_TABULAR_RESULT`.

---

# 18. Run-all and stop-all

```http
POST /api/v1/notebooks/{notebookId}:run-all
POST /api/v1/notebooks/{notebookId}:stop-all

GET /api/v1/notebooks/{notebookId}/runs
GET /api/v1/notebook-runs/{runId}
```

Run-all:

* Execute CODE cells by position.
* Skip MARKDOWN.
* Support mixed SQL and Python.
* Create one execution per cell.
* Preserve language runtime state.
* Return immediately.
* Support:

  * `STOP_ON_ERROR`
  * `CONTINUE_ON_ERROR`
* Use idempotent `clientRequestId`.

Stop-all:

* Cancel active and queued executions in the notebook run.
* Preserve execution history.
* Not affect unrelated sessions.
* Be idempotent.

---

# 19. Scheduling

```http
GET    /api/v1/notebooks/{notebookId}/schedule
PUT    /api/v1/notebooks/{notebookId}/schedule
DELETE /api/v1/notebooks/{notebookId}/schedule
```

Requirements:

* Validate cron.
* Explicit timezone.
* Persistent schedule.
* Auditable scheduled runs.
* Use normal NotebookRun and Execution services.
* Default overlap policy:

```text
SKIP_IF_RUNNING
```

Also support:

```text
QUEUE
```

No unlimited overlapping runs.

---

# 20. User Python environments

## PythonEnvironment

```text
id
owner
name
runtimeSpecId
pythonVersion
activeRevisionId
state
createdAt
createdBy
updatedAt
updatedBy
version
```

## PythonEnvironmentRevision

```text
id
environmentId
revisionNumber
state
requirements
resolvedPackages
createdAt
createdBy
activatedAt
failureMessage
internalEnvironmentLocation
```

## PythonPackageOperation

```text
id
environmentId
baseRevisionId
targetRevisionId
action
requestedPackages
state
submittedAt
startedAt
finishedAt
submittedBy
errorCode
errorMessage
version
```

Environment states:

```text
CREATING
READY
UPDATING
FAILED
DELETING
DELETED
```

Package-operation states:

```text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELED
```

---

# 21. Python environment APIs

```http
POST   /api/v1/python-environments
GET    /api/v1/python-environments
GET    /api/v1/python-environments/{environmentId}
PATCH  /api/v1/python-environments/{environmentId}
DELETE /api/v1/python-environments/{environmentId}

GET /api/v1/python-environments/{environmentId}/revisions
GET /api/v1/python-environments/{environmentId}/revisions/{revisionNumber}

GET  /api/v1/python-environments/{environmentId}/packages
POST /api/v1/python-environments/{environmentId}/packages:install
POST /api/v1/python-environments/{environmentId}/packages:uninstall
POST /api/v1/python-environments/{environmentId}:rebuild
POST /api/v1/python-environments/{environmentId}/revisions/{revisionNumber}:activate

GET  /api/v1/python-package-operations/{operationId}
GET  /api/v1/python-package-operations/{operationId}/logs
POST /api/v1/python-package-operations/{operationId}:cancel
```

Install request:

```json
{
  "packages": [
    "pandas==2.3.1",
    "numpy>=2,<3"
  ],
  "clientRequestId": "stable-id"
}
```

Requirements:

* Async operations.
* Idempotency.
* Owner/admin modification only.
* Unprivileged installation.
* Incremental logs.
* Timeout.
* Disk quota.
* Serialized environment updates.
* Failed update preserves previous active revision.
* Persist resolved package versions.
* Never return index credentials.

---

# 22. Environment revision policy

Do not modify an active environment in place.

For install, uninstall or rebuild:

1. Copy from the active revision.
2. Create a candidate revision.
3. Apply package changes.
4. Validate CPython startup.
5. Record resolved packages.
6. Activate atomically after success.
7. Retain previous revision for rollback.

After activation:

* Existing runtimes may continue on their old revision.
* Mark them `RESTART_REQUIRED`.
* Do not restart an active runtime silently.
* A restarted runtime must bind to the new active revision.

A shared notebook does not automatically share the owner's Python environment.

Each user uses:

* Their own environment, or
* An explicitly approved administrator-managed shared environment.

---

# 23. Package installation security

Do not expose a raw shell or raw pip command.

Accept validated requirement specifications only:

```text
package
package==1.2.3
package>=1,<2
package[extra]==1.2.3
```

Reject by default:

* Arbitrary pip options.
* User-provided index URLs.
* `--trusted-host`.
* Editable installs.
* Local paths.
* VCS URLs.
* Direct URLs.
* Shell metacharacters.
* System package modification.

Administrators configure:

* Package index.
* Internal mirror.
* Allowlist.
* Denylist.
* Constraints file.
* Installation timeout.
* Build timeout.
* Maximum packages.
* Maximum environment size.
* Network policy.

Parse requirements structurally. Never concatenate user input into a shell command.

---

# 24. Current user and service APIs

```http
GET /api/v1/me
GET /api/v1/notebook-status
GET /api/v1/notebook-openapi.yaml
```

`/me` returns:

* Current authenticated identity.
* Notebook permissions.
* Runtime permissions.
* Python-environment management permission.
* Admin status.

Do not return tokens.

`/notebook-status` returns sanitized health data:

* Notebook service.
* Persistence.
* Kyuubi SQL availability.
* CPython runtime-manager availability.
* Active sessions.
* Active runtimes.
* Queued executions.

Do not expose private notebook or runtime data.

---

# 25. Persistence and recovery

Persist:

* Folders.
* Notebooks.
* Cells.
* Revisions.
* Permissions.
* Schedules.
* Notebook runs.
* Notebook sessions.
* Runtime metadata.
* Runtime generations.
* Executions.
* Events.
* Output metadata.
* Python environments.
* Environment revisions.
* Package operations.
* Audit records.

Persistence must survive:

* Browser refresh.
* Notebook service restart.
* Kyuubi Web server restart.
* Runtime-manager restart.
* Normal deployment restart.

On restart:

* Reconcile Kyuubi sessions and operations.
* Reconcile CPython runtimes.
* Reconcile package operations.
* Mark unrecoverable objects `LOST` or `FAILED`.
* Never silently mark unknown work successful.

Do not use in-memory-only production storage.

---

# 26. Security requirements

Implement:

* Existing OIDC security-context integration.
* CSRF protection.
* CORS allowlist.
* Secure cookies.
* Authorization on every notebook/runtime/execution endpoint.
* Cross-user notebook isolation.
* Cross-user runtime isolation.
* Cross-user Python-environment isolation.
* CPU and memory limits.
* Process-count limits.
* Runtime idle timeout.
* Execution timeout.
* Package-install timeout.
* Environment disk quotas.
* Path traversal protection.
* Symlink escape protection.
* Shell-injection protection.
* HTML/SVG output sanitization.
* Secret and token redaction.
* No host container socket.
* No root runtime.
* No system-Python mutation.
* Child-process cleanup.
* Auditing of privileged actions and authorization failures.

Document the threat model in:

```text
docs/notebook/PYTHON_SECURITY.md
```

---

# 27. Frontend migration

The frontend must no longer call raw endpoints directly:

```text
/api/v1/sessions
/api/v1/operations/*
/api/v1/admin/operation/*
```

Use Notebook APIs only.

Required UI:

* Notebook and folder browser.
* Create, rename, move and clone.
* Import/export.
* Search.
* Revisions/checkpoints.
* Permissions.
* SQL cells.
* Python cells.
* Markdown cells.
* Run cell.
* Run all.
* Stop cell.
* Stop all.
* Execution history.
* Incremental logs.
* SQL pagination.
* Python stdout/stderr.
* Python traceback.
* Rich output.
* SQL runtime controls.
* Python runtime controls.
* Schedule configuration.
* Python environment selection.
* Installed-package list.
* Install and uninstall package.
* Package-operation progress.
* Restart-required notification.
* Browser-refresh recovery.

Opening Notebook must not trigger a second login.

Do not expose internal handles, kernel IDs, environment paths or credentials in browser storage.

---

# 28. Error contract

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Safe public message",
    "requestId": "request-id",
    "retryable": false,
    "details": {}
  }
}
```

Required error codes include:

```text
NOTEBOOK_NOT_FOUND
FOLDER_NOT_FOUND
CELL_NOT_FOUND
PATH_CONFLICT
VERSION_CONFLICT
ACCESS_DENIED
UNSUPPORTED_LANGUAGE
NOTEBOOK_SESSION_NOT_FOUND
RUNTIME_SPEC_NOT_FOUND
RUNTIME_NOT_FOUND
RUNTIME_LOST
RUNTIME_RESTART_REQUIRED
EXECUTION_NOT_FOUND
EXECUTION_TIMEOUT
EXECUTION_CANCELED
KYUUBI_SESSION_LOST
KYUUBI_OPERATION_LOST
KYUUBI_UNAVAILABLE
PYTHON_RUNTIME_UNAVAILABLE
PYTHON_EXECUTION_FAILED
PYTHON_INTERRUPT_FAILED
PYTHON_ENVIRONMENT_NOT_FOUND
PYTHON_ENVIRONMENT_BUSY
PYTHON_PACKAGE_INVALID
PYTHON_PACKAGE_DENIED
PYTHON_PACKAGE_INSTALL_FAILED
PYTHON_PACKAGE_UNINSTALL_FAILED
PYTHON_ENVIRONMENT_QUOTA_EXCEEDED
PACKAGE_INDEX_UNAVAILABLE
NO_TABULAR_RESULT
OUTPUT_EXPIRED
RESULT_EXPIRED
RATE_LIMITED
```

Do not return stack traces, secrets, internal handles or filesystem paths.

---

# 29. Testing

## Authentication

Test:

* Existing Keycloak login works with Notebook.
* No second login appears.
* Unauthenticated requests are rejected.
* Client-supplied owner fields are rejected or ignored.

## Notebook API

Test:

* Folder CRUD.
* Notebook CRUD.
* Copy, clone, move and rename.
* Cell CRUD and reorder.
* Import/export.
* Search.
* Permissions.
* Revisions and restore.
* Run all and stop all.
* Scheduling.
* Current-user endpoint.
* Status endpoint.
* OpenAPI endpoint.

## SQL

Test:

* Async execution.
* Status polling.
* Incremental logs.
* Paginated results.
* Empty result.
* DDL/DML without result.
* Syntax failure.
* Timeout.
* Cancel.
* Close.
* Browser refresh recovery.
* Kyuubi unavailable.
* Lost Kyuubi session.
* Session affinity.

## CPython

Test:

```python
value = 40
```

Then:

```python
print(value + 2)
```

Expected output:

```text
42
```

Also test:

* stdout.
* stderr.
* Expression output.
* Traceback.
* Rich display.
* Long execution interrupt.
* Restart clears variables.
* Stop terminates runtime.
* Runtime resource limits.
* Cross-user isolation.
* Runtime recovery or accurate `LOST`.

## Python environments

Test:

* Create environment.
* Install pinned package.
* List packages.
* Restart runtime.
* Import installed package.
* Uninstall package.
* Failed installation preserves old revision.
* Duplicate requests are idempotent.
* Concurrent updates are serialized.
* Invalid pip options are rejected.
* Direct URLs and local paths are rejected.
* Cross-user access is rejected.
* Quota enforcement.
* Incremental package logs.
* No credential leakage.

## Race conditions

Test:

* Cancel while execution completes.
* Stop while execution is submitted.
* Reset while fetching results.
* Duplicate POST after browser timeout.
* Duplicate `clientRequestId` with different payload.
* Token expiry during execution.
* Browser refresh during execution.
* Server restart during execution.
* Runtime restart during package activation.

---

# 30. Acceptance criteria

The task is complete only when:

## Authentication

* One existing Keycloak login is sufficient.
* No Notebook-specific login exists.
* Existing OIDC remains operational.

## Notebook API

* Folder, notebook, cell, revision and permission APIs work.
* Import/export work.
* Search works.
* Run one, run all, stop one and stop all work.
* Scheduling works.
* Zeppelin/Jupyter parity document is complete.

## SQL

* SQL executes asynchronously through Kyuubi.
* Logs are incremental.
* Results are paginated.
* Cancel and close work without admin endpoints.
* Internal Kyuubi handles remain private.

## CPython

* Real CPython executes Python code.
* Variables persist across cells.
* stdout, stderr, results and tracebacks work.
* Interrupt, restart and stop work.
* Restart clears variables.
* Cross-user runtime access is prevented.
* Resource limits are enforced.

## Python environments

* User can create an isolated environment.
* User can install and uninstall allowed packages.
* Package changes create immutable revisions.
* Failed updates preserve the previous revision.
* Newly installed packages work after runtime restart.
* Users cannot access another user's environment.
* System Python is never modified.
* Arbitrary pip flags and shell commands are rejected.
* Index credentials remain private.

## Recovery

* Notebook data survives restart.
* Environment definitions survive restart.
* Execution history survives refresh.
* Runtime and package operations recover or transition accurately.
* Unknown work is never marked successful.

## Compatibility

* Existing Kyuubi APIs remain backward compatible.
* Frontend uses Notebook APIs.
* OpenAPI is complete.
* Backend tests pass.
* Frontend tests pass.
* E2E tests pass.

---

# 31. Delivery plan

## PR 1 — Domain and API contract

* Gap analysis.
* Zeppelin/Jupyter parity.
* Domain models.
* Persistence.
* Folder/notebook/cell APIs.
* Revisions.
* Permissions.
* Import/export.
* Search.
* Scheduling contract.
* Runtime-neutral API.
* OpenAPI.
* Existing OIDC authorization.
* Unit tests.

## PR 2 — SQL runtime

* Runtime adapter registry.
* Kyuubi SQL adapter.
* Async execution.
* Logs.
* Events.
* Schema.
* Result pagination.
* Cancel/close.
* Session affinity.
* Restart/reset/stop.
* Run-all/stop-all.
* Recovery.
* Integration tests.

## PR 3 — CPython and Python environments

* CPython runtime manager.
* Persistent interpreter state.
* Generic outputs.
* Interrupt/restart/stop.
* Python environments.
* Immutable environment revisions.
* Package installation/uninstall.
* Quotas and security controls.
* Package logs.
* Recovery.
* Integration tests.
* `PYTHON_SECURITY.md`.

## PR 4 — Frontend and E2E

* Notebook UI.
* SQL and Python cells.
* Folder navigation.
* Import/export.
* Search.
* Revisions.
* Permissions.
* Scheduling.
* Runtime controls.
* Rich outputs.
* Python environment UI.
* Package installation UI.
* Browser-refresh recovery.
* Frontend tests.
* SQL E2E.
* CPython E2E.
* Package E2E.

---

# 32. Agent rules

* Remove redundant and unnecessary comments from modified code.
* Do not add comments that only restate the code.
* Keep comments only when they explain non-obvious behavior, invariants, security constraints, compatibility requirements or architectural decisions.
* Preserve license headers, public API documentation and actionable TODOs linked to an issue.
* Do not redesign the existing OIDC flow.
* Do not add a second login.
* Do not begin with UI changes.
* Do not postpone CPython.
* Do not fake Python execution.
* Do not run Python inside the Kyuubi JVM.
* Do not use one process per cell.
* Do not expose raw Kyuubi or kernel handles.
* Do not use admin endpoints for normal cleanup.
* Do not use in-memory-only production persistence.
* Do not share writable Python environments between users.
* Do not mutate active environment revisions.
* Do not modify system Python.
* Do not accept arbitrary pip commands or flags.
* Do not concatenate user input into shell commands.
* Do not expose package-index credentials.
* Do not silently restart active runtimes.
* Do not mark unknown work successful.
* Keep browser-facing APIs REST-based.
* Keep REST resources thin.
* Put SQL logic behind `KyuubiSqlRuntimeAdapter`.
* Put Python logic behind `CpythonRuntimeAdapter`.
* Put package logic behind `PythonEnvironmentService`.
* Preserve upstream Kyuubi compatibility.
* Avoid unrelated refactoring.

At completion, report:

* Architecture implemented.
* Endpoint inventory.
* Zeppelin/Jupyter parity status.
* Persistence changes.
* CPython version and isolation method.
* Python environment implementation.
* Package-source policy.
* Files changed.
* Test commands.
* Test results.
* Known limitations.
