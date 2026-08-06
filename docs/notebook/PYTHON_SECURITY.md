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

# Python Runtime Threat Model

**Status: design.** The CPython runtime and Python environments are delivered in PR 3. This
document is the security contract that implementation must satisfy, written before the code so it
can be reviewed against it. The controls described here are *not* in place today; what *is* in
place is that no Python execution path exists at all.

## The core assumption

**Python cell content is untrusted code that the platform agrees to run.** There is no sandbox
inside CPython worth relying on — `import os` is enough to reach the filesystem, and any
"restricted interpreter" scheme has been broken repeatedly. Therefore the boundary is the
**process and its operating-system limits**, not anything inside the interpreter.

Everything below follows from that: the interpreter is assumed compromised the moment a user runs
a cell, and the question is only what a compromised interpreter can reach.

## Assets

|                    Asset                    |                                   Why it matters                                    |
|---------------------------------------------|-------------------------------------------------------------------------------------|
| Other users' notebooks and outputs          | Confidentiality between tenants                                                     |
| Other users' Python environments            | A writable shared environment is remote code execution against everyone who uses it |
| Kyuubi server process and its configuration | Holds JDBC credentials and the JWT verification setup                               |
| The host and its container runtime          | Escape means whole-cluster compromise                                               |
| Package index credentials                   | Reusable secrets                                                                    |
| Keycloak tokens                             | Impersonation                                                                       |

## Isolation

Each runtime is **one long-lived CPython process per (user, notebook session, generation)**:

- Persistent across cells within a generation, which is what makes `value = 40` visible to the
  next cell.
- One process per cell is explicitly rejected: it breaks that requirement.
- A shared unrestricted process is explicitly rejected: it merges the trust of every user.

Required properties:

- Runs as an **unprivileged** user or container identity; never root.
- Its own **writable working directory**, not readable by another user's runtime.
- **No host container-runtime socket** is mounted. A Docker or containerd socket is equivalent to
  root on the host.
- Child processes are tracked and killed with the runtime, so a background `subprocess` cannot
  outlive it.
- The Kyuubi server's own configuration and credentials are not present in the runtime's
  environment or filesystem view.

## Resource limits

Enforced by the operating system or container runtime, never by the interpreter:

|         Limit          |                           Reason                            |
|------------------------|-------------------------------------------------------------|
| CPU                    | A busy loop must not starve the node                        |
| Memory                 | An unbounded allocation must kill one runtime, not the host |
| Process count          | Blocks fork bombs                                           |
| Execution timeout      | Bounds a single cell                                        |
| Idle timeout           | Reclaims abandoned runtimes                                 |
| Environment disk quota | Bounds package installation                                 |

Hitting a limit terminates the runtime and marks affected executions `FAILED`, never `SUCCEEDED`.

## Package installation

The attack this section exists to stop is **arbitrary command execution through pip arguments**,
and **code execution through package resolution** (a `setup.py` runs on install).

Rules:

1. **No raw shell, no raw pip command.** The API accepts a list of requirement specifications,
   never a command line.
2. **Structural parsing.** Requirements are parsed into name, extras and version specifiers and
   re-emitted; user text is never concatenated into a command string. Accepted forms:

   ```text
   package
   package==1.2.3
   package>=1,<2
   package[extra]==1.2.3
   ```
3. **Rejected by default:** arbitrary pip options, user-supplied index URLs, `--trusted-host`,
   editable installs, local paths, VCS URLs, direct URLs, shell metacharacters, and anything that
   would modify the system Python.
4. **Administrators control the source:** index or internal mirror, allowlist, denylist,
   constraints file, install timeout, build timeout, maximum package count, maximum environment
   size, and network policy. Index credentials are held server-side and never returned by any
   endpoint or included in a log line.
5. Installation runs **unprivileged**, in the environment's own directory. The system Python is
   never modified.

## Environment revisions

An active environment is never mutated in place. Every change produces a candidate revision that
is validated (CPython must start in it) before being activated atomically; the previous revision is
retained for rollback, and a failed update leaves the previous revision active.

**Environments are not shared between users.** A user works with their own environment or with an
explicitly approved administrator-managed shared one, which is read-only to them. Sharing a
notebook never shares the owner's environment — otherwise granting read access to a notebook would
hand over the ability to install code into the grantee's interpreter.

After an activation, existing runtimes keep their old revision and are marked
`RESTART_REQUIRED`. They are never restarted silently: a silent restart destroys in-memory state
the user may not be able to reproduce.

## Path handling

- Filesystem paths are never accepted from a client. Environment and working directories are
  derived from internal ids.
- Derived paths are canonicalized and verified to remain within their intended root, defending
  against `..` traversal and symlink escape.
- No environment or runtime path is ever present in a response.

## Output handling

Rich output is attacker-controlled by definition — a cell can emit any HTML.

- HTML and SVG are sanitized before storage or display.
- Large binary output is stored as an artifact and served with a non-renderable content type and
  `Content-Disposition: attachment`.
- Output is served only to principals authorized for the owning notebook and execution.
- Imported `.ipynb` outputs are discarded rather than sanitized. A file may come from anywhere,
  and dropping is a stronger guarantee than filtering.

## Logging

Access tokens, `Authorization` headers, client secrets, package-index credentials and truststore
passwords are never logged. Where a value must be referenced, only its parameter name or length is
recorded. Log streams returned through the API are redacted with the same rules before they leave
the server.

## Auditing

Privileged actions and authorization failures are audited: permission changes, environment
creation and activation, package operations, runtime stop and restart, and every denied request
with the principal, object and reason.

## Residual risks

- A CPython process can consume its full CPU and memory allotment for the duration of its timeout.
  This is accepted; the limits bound the damage rather than preventing it.
- A package from a permitted index can still be malicious. The allowlist and mirror are the
  control; the platform does not audit package contents.
- A user can exfiltrate data they are already authorized to read. Notebook isolation is about
  cross-user access, not about preventing an authorized user from copying their own results.

---

## What is implemented

The design above is now in place. Where the implementation makes a choice the design left open,
it is recorded here.

### Not ipykernel

§15 recommends an isolated ipykernel. The runtime is instead a small driver script
(`kyuubi-server/src/main/resources/python/kyuubi_notebook_kernel.py`) that speaks one JSON object
per line over stdin/stdout. The reasons:

- ipykernel requires the Jupyter wire protocol over ZeroMQ - five sockets and HMAC signing - which
  would add a ZeroMQ client to the server and roughly 50 MB of Python dependencies to the image.
- The driver needs only the standard library, so it starts inside a bare environment. That matters
  because the interpreter has to run before a user can install anything.
- Less code in the process that runs untrusted input.

What is kept: one persistent process per runtime generation, names surviving between cells,
stdout and stderr, expression results, tracebacks, interrupt, restart and stop. What is reduced:
rich output covers the `_repr_html_`, `_repr_png_`, `_repr_svg_` and `_repr_json_` protocol rather
than the full Jupyter display system, and only the `%pip` and `!` magics are recognised.

### `%pip` in a cell is allowed, and lands somewhere harmless

Blocking `%pip` while allowing arbitrary Python would be theatre: `import subprocess` grants the
same capability. What matters is where the install goes. `PYTHONUSERBASE` points at the runtime's
own scratch directory, so an in-cell install writes there and disappears with the runtime, and the
managed environment revision stays immutable. `%pip` is rewritten to use `sys.executable`, because
`!pip` would use whatever is first on `PATH` and can silently install into another interpreter.

### Where enforcement actually lives

|              Control              |                        Enforced by                         |
|-----------------------------------|------------------------------------------------------------|
| CPU, address space, process count | `setrlimit` in the driver, before user code runs           |
| Execution timeout                 | The server, which interrupts the cell                      |
| Environment size                  | Measured after a build; an oversized revision is discarded |
| Package identity                  | `RequirementSpec`, before anything is scheduled            |
| Index and constraints             | Server configuration, passed as pip arguments              |
| Reaching the index at all         | Network policy, not the server                             |

The last row is the honest limitation of the in-cell path: a user who types
`%pip install --index-url https://elsewhere ...` bypasses the configured mirror, because that pip
runs as them, in their own process. Only network policy stops it. The managed API has no such
hole - it accepts requirement names and builds the command line itself.

### No shell, anywhere in the managed path

`PythonEnvironmentBuilder` runs `python -m venv` and `python -m pip` through `ProcessBuilder` with
an argument vector, never a shell string, and clears the inherited environment first so a
credential in the server process cannot reach a package's `setup.py`.

### Rich output: two layers, neither trusted alone

Rich output is whatever a cell chose to emit, so it is treated as hostile.

1. **Server** - `RichOutputSanitizer` removes `script`, `style`, `iframe`, `object`, `embed`,
   `applet`, `link`, `meta`, `base` and `form` elements with their content, strips `on*` event
   attributes, removes `javascript:`, `vbscript:` and `data:text/html` URLs, and drops comments.
   Base64 image payloads are re-decoded and dropped if they do not decode.
2. **Browser** - the UI renders `text/html` and `image/svg+xml` inside
   `<iframe sandbox="" referrerpolicy="no-referrer" srcdoc="...">`. An empty `sandbox` grants
   nothing: no scripts, no same-origin access, no form submission, no top-level navigation.

The second layer is the one relied upon. A hand-written filter is the wrong thing to stake
safety on, and the first layer exists so that the common attacks never reach a browser at all,
and so a future consumer that forgets the iframe is not instantly exploitable. `image/png` and
friends render as a `data:` URI in an `img`, which cannot execute.

### Packages baked into the image

`docker/requirements.txt` is installed into the image's system interpreter, and environments are
created with `--system-site-packages`, so every user sees those packages without a download. The
flag is the whole point: a plain `python3 -m venv` is fully isolated and would make the baked
packages invisible. It is controlled by `kyuubi.notebook.python.venv.system.site.packages`.

Three consequences:

- A user may still install a different version of a baked distribution. Their copy lands in their
  own environment and takes precedence, which is ordinary virtualenv behaviour.
- A user cannot remove a baked package. The server refuses the request rather than rebuilding a
  revision without it and leaving it importable anyway.
- `pip freeze --local` is what records a revision's contents, so baked packages do not end up in
  `resolvedPackages` and a rebuild does not try to reinstall them into the revision.

Baked packages are read-only to the runtime user, so this widens what a cell can import but not
what it can write.

### Still open

- The runtime is confined by `setrlimit` and an unprivileged uid, not by a container or namespace
  per runtime. A user who can run Python can read anything that uid can read.
- Child processes are killed with the kernel through `destroyForcibly`, which does not follow a
  process that deliberately detaches itself.
- Rich outputs live in the server's memory until the execution is closed, so they do not survive
  a restart. That is consistent with the execution itself becoming `LOST`, but it means output is
  not replayable the way events are.

