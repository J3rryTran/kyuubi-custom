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

# Notebook Lifecycles

State machines for every notebook object. Document lifecycles are implemented; runtime lifecycles
are the contract that PR 2 and PR 3 implement, and the enums for them already exist in
`NotebookEnums.scala` so that persisted values do not change later.

## Document

A notebook has no state column. Its lifecycle is the version counter plus the soft-delete flag.

```text
created (version 1)
   |  PATCH / cell mutation / reorder   -> version + 1, revision recorded
   v
live -----------------------------------> soft-deleted
   ^                                        |  path rewritten to a tombstone
   |  revision restore                      |  permissions and schedule dropped
   +----------------------------------------+  (no path back; restore a revision of a live
                                               notebook instead)
```

Deleting is soft: `deleted = 1`, and `path`/`path_hash` are rewritten to
`<path>#deleted:<id>`. The live path becomes free immediately, so creating a replacement at the
same path succeeds, and the two rows remain distinguishable for auditing.

Deleting a folder applies the same rewrite to the folder and every descendant in one transaction.

## Revision

```text
edit -> automatic revision (unprotected, trimmable)
manual checkpoint -> protected revision
restore of revision N:
    current content <- snapshot of N
    append protected revision N+k  ("restored from revision N")
```

Trimming keeps the newest `kyuubi.notebook.revision.max.per.notebook` **unprotected** revisions.
Protected ones are never trimmed and `DELETE` refuses them, so the record of a restore cannot be
erased.

## Notebook session *(planned)*

```text
CREATING --> IDLE <----> BUSY
   |          |  \         |
   |          |   \        v
   |          |    +--> RESETTING --> IDLE
   |          v
   |       STOPPING --> STOPPED
   v
 FAILED                    LOST
```

- **restart** cancels active executions, restarts the SQL and Python runtimes, increments the
  runtime generation, clears in-memory runtime state, and keeps notebook content, history and
  saved outputs.
- **reset** performs a restart and additionally clears temporary session state; it never deletes
  notebook source or persisted artifacts.
- **stop** cancels active executions, closes Kyuubi operations and sessions, stops CPython
  runtimes, releases resources, keeps history, and is idempotent.
- `LOST` is entered only by reconciliation, when a session's backing resources cannot be found
  after a restart.

## Runtime *(planned)*

```text
CREATING --> IDLE <----> BUSY
              |  ^         |
              |  |         v
              |  +---- INTERRUPTING
              |  |
              |  +---- RESTARTING   (generation + 1, variables cleared)
              v
           STOPPING --> STOPPED

any state --> FAILED | LOST
```

Interrupt preserves runtime state where the backend supports it; restart does not. A restart
increments `generation`, and every execution records the generation it ran under, so an output can
never be attributed to the wrong incarnation.

SQL and Python runtimes coexist in one notebook session. Restarting one does not disturb the
other.

## Cell execution *(planned)*

```text
QUEUED --> STARTING --> RUNNING --> SUCCEEDED
   |           |           |    \-> FAILED
   |           |           |     \-> CANCELING --> CANCELED
   |           |           |
   +-----------+-----------+------> LOST        (reconciliation only)
                                        |
                                        v
                                     CLOSED     (client released the result)
```

- The submission returns as soon as the execution is accepted; the client polls.
- `clientRequestId` makes submission idempotent. The same id with a different payload is
  `409` — a retry must not silently run different code.
- The source is snapshotted at submission. Editing the cell afterwards changes neither a running
  nor a finished execution.
- `LOST` is never inferred from silence alone; it is set by reconciliation when the backing
  operation or runtime is confirmed gone. Unknown work is never marked `SUCCEEDED`.

## Notebook run (run-all) *(planned)*

```text
QUEUED --> RUNNING --> SUCCEEDED
              |    \-> FAILED        (STOP_ON_ERROR, or every-cell failure)
              +-----> CANCELING --> CANCELED   (stop-all)
```

CODE cells run in position order and MARKDOWN cells are skipped. Each cell gets its own execution,
so per-cell history is preserved. `STOP_ON_ERROR` halts at the first failure; `CONTINUE_ON_ERROR`
runs to the end and reports the run as `FAILED` if any cell failed. Language runtime state is
preserved across cells, which is what makes a Python variable set in one cell visible in the next.

## Python environment *(planned)*

```text
CREATING --> READY <----> UPDATING
   |           |             |
   |           |             +--> FAILED  (previous active revision stays active)
   |           v
   |        DELETING --> DELETED
   v
 FAILED
```

An active revision is never modified in place. Install, uninstall and rebuild all follow:

```text
copy active revision -> candidate revision -> apply package changes ->
validate CPython startup -> record resolved packages -> activate atomically ->
retain previous revision for rollback
```

After activation, runtimes still bound to the old revision keep running and are marked
`RESTART_REQUIRED`. They are never restarted silently; the user decides when to lose their
variables.

## Package operation *(planned)*

```text
QUEUED --> RUNNING --> SUCCEEDED
              |    \-> FAILED     (candidate discarded, previous revision still active)
              +-----> CANCELED
```

Operations against one environment are serialized, so two concurrent installs cannot interleave
into an inconsistent revision.

## What survives what

The two ways a package reaches a cell have deliberately different lifetimes.

|                          | Baked into the image |     Managed environment      |   `%pip` inside a cell    |
|--------------------------|----------------------|------------------------------|---------------------------|
| Where it lives           | System interpreter   | Environment revision on disk | Runtime scratch directory |
| Who installs it          | The image build      | The user, through the API    | The user, in a cell       |
| Reset or restart session | survives             | survives                     | **gone**                  |
| Stop session             | survives             | survives                     | **gone**                  |
| Idle timeout             | survives             | survives                     | **gone**                  |
| Server restart           | survives             | survives                     | **gone**                  |
| Deleting the environment | survives             | gone                         | n/a                       |

A user therefore reinstalls only what they installed from inside a cell. Anything they asked for
through the environment API is on disk and comes back with the next runtime. That split is the
whole reason the managed environment exists: without it, every restart would mean downloading
pandas again.

If ephemeral-only behaviour is wanted, set `kyuubi.notebook.python.environment.max.per.user` to
`0`, which leaves the baked packages plus whatever a cell installs for itself.

## Idle reclamation

A runtime that has not run a cell for `kyuubi.notebook.runtime.idle.timeout` is stopped, and a
session left with no runtimes is stopped with it. Activity is recorded when an execution is
submitted, so a long-running cell is never reclaimed underneath itself, and a runtime in state
`BUSY` is skipped. Setting the timeout to `0` disables reclamation.

Reclaiming has the same effect on state as an explicit stop: variables and anything a cell
installed are gone, notebook content and execution history are not.

## Recovery on restart

On startup the subsystem reconciles what it persisted against what actually exists:

|                         Object                         |                                             Reconciliation                                              |
|--------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Notebook, folder, cell, revision, permission, schedule | durable; nothing to reconcile                                                                           |
| Notebook session                                       | reconnect if its runtimes are alive, otherwise `LOST`                                                   |
| Runtime                                                | reconnect when the backing process or Kyuubi session is verifiably the same, otherwise `LOST`           |
| Execution                                              | terminal states stay; a non-terminal execution whose runtime is gone becomes `LOST`                     |
| Package operation                                      | a non-terminal operation whose worker is gone becomes `FAILED`, and its candidate revision is discarded |

The rule that overrides all of the above: **unknown work is never marked successful.** If the
outcome cannot be established, it is `LOST` or `FAILED`.

Python variables are not expected to survive a runtime restart, and no attempt is made to persist
them.
