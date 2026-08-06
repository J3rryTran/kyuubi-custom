#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""Persistent CPython interpreter for Kyuubi notebooks.

One process serves one notebook runtime for its whole generation, so names bound in one cell are
visible in the next. The server speaks to it over stdin/stdout with one JSON object per line;
stdout is reserved for that protocol, which is why user output is captured and shipped inside
the response rather than being allowed to reach the real stdout.

Only the standard library is used: the interpreter has to start inside a bare environment, and a
dependency here would have to be installed before a user could install anything.
"""

import ast
import io
import json
import os
import resource
import signal
import subprocess
import sys
import traceback


class Capture(io.TextIOBase):
    """Collects what a cell writes, tagged with the stream it came from."""

    def __init__(self, sink, stream):
        self.sink = sink
        self.stream = stream

    def write(self, text):
        if text:
            self.sink.append({"stream": self.stream, "text": text})
        return len(text)

    def writable(self):
        return True


def apply_limits():
    """Applies the caps the server passed in the environment.

    Enforcement is by the operating system, not by anything inside the interpreter: a cell can
    call into C, spawn threads or rebind builtins, so a Python-level guard would be advisory.
    """
    def limit(name, resource_id, scale=1):
        raw = os.environ.get(name)
        if not raw:
            return
        try:
            value = int(raw) * scale
        except ValueError:
            return
        try:
            resource.setrlimit(resource_id, (value, value))
        except (ValueError, OSError):
            # A limit lower than the current hard limit cannot be raised back; failing to set
            # one must not stop the runtime from starting, so it is reported and skipped.
            print(
                json.dumps({"type": "warning", "message": "could not apply " + name}),
                flush=True,
            )

    limit("KYUUBI_PY_LIMIT_CPU_SECONDS", resource.RLIMIT_CPU)
    limit("KYUUBI_PY_LIMIT_MEMORY_MB", resource.RLIMIT_AS, 1024 * 1024)
    limit("KYUUBI_PY_LIMIT_PROCESSES", resource.RLIMIT_NPROC)
    limit("KYUUBI_PY_LIMIT_FILE_SIZE_MB", resource.RLIMIT_FSIZE, 1024 * 1024)


def rich_output(value):
    """Extracts the richest representation an object offers.

    The `_repr_*_` protocol is what pandas, matplotlib and friends already implement, so honouring
    it costs nothing and covers the common cases without pulling in IPython.
    """
    outputs = []
    for method, mime in (
        ("_repr_html_", "text/html"),
        ("_repr_png_", "image/png"),
        ("_repr_svg_", "image/svg+xml"),
        ("_repr_json_", "application/json"),
    ):
        renderer = getattr(value, method, None)
        if callable(renderer):
            try:
                data = renderer()
            except Exception:
                continue
            if data is None:
                continue
            if isinstance(data, bytes):
                import base64

                data = base64.b64encode(data).decode("ascii")
            outputs.append({"mimeType": mime, "data": data})
    return outputs


def run_shell(command, captured):
    """Backs `!cmd` and `%pip`.

    The command runs as this process's own user with this process's own limits, so it grants no
    authority the cell did not already have through `subprocess`. Blocking it would only hide
    the capability, not remove it.
    """
    completed = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if completed.stdout:
        captured.append({"stream": "stdout", "text": completed.stdout})
    if completed.returncode != 0:
        captured.append(
            {"stream": "stderr", "text": "command exited with %d\n" % completed.returncode}
        )


def expand_magics(source):
    """Rewrites the two magics people actually use into ordinary calls.

    `%pip` targets this interpreter through `sys.executable`, which is what makes an install
    land in the environment the kernel is running from; `!pip` would use whatever is first on
    PATH and can silently install somewhere else.
    """
    lines = []
    for line in source.split("\n"):
        stripped = line.strip()
        if stripped.startswith("%pip ") or stripped == "%pip":
            args = stripped[4:].split()
            lines.append(
                "__kyuubi_shell__([__import__('sys').executable, '-m', 'pip'] + %r)" % (args,)
            )
        elif stripped.startswith("!"):
            args = stripped[1:].strip()
            if args:
                lines.append("__kyuubi_shell__(%r, shell=True)" % args)
        else:
            lines.append(line)
    return "\n".join(lines)


def execute(source, namespace):
    captured = []

    def shell(command, shell=False):
        if shell:
            completed = subprocess.run(
                command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, shell=True
            )
            if completed.stdout:
                captured.append({"stream": "stdout", "text": completed.stdout})
            if completed.returncode != 0:
                captured.append(
                    {
                        "stream": "stderr",
                        "text": "command exited with %d\n" % completed.returncode,
                    }
                )
        else:
            run_shell(command, captured)

    namespace["__kyuubi_shell__"] = shell

    stdout, stderr = sys.stdout, sys.stderr
    sys.stdout = Capture(captured, "stdout")
    sys.stderr = Capture(captured, "stderr")
    result = {"status": "ok", "outputs": captured, "rich": [], "result": None}
    try:
        prepared = expand_magics(source)
        parsed = ast.parse(prepared, mode="exec")
        # The last statement is evaluated separately so a bare expression shows its value, the
        # way a notebook is expected to behave.
        if parsed.body and isinstance(parsed.body[-1], ast.Expr):
            head = ast.Module(body=parsed.body[:-1], type_ignores=[])
            tail = ast.Expression(body=parsed.body[-1].value)
            exec(compile(head, "<cell>", "exec"), namespace)
            value = eval(compile(tail, "<cell>", "eval"), namespace)
            if value is not None:
                namespace["_"] = value
                result["result"] = repr(value)
                result["rich"] = rich_output(value)
        else:
            exec(compile(parsed, "<cell>", "exec"), namespace)
    except KeyboardInterrupt:
        result["status"] = "interrupted"
        result["error"] = "the execution was interrupted"
    except BaseException:
        result["status"] = "error"
        # The kernel frame is dropped so the traceback starts at the user's own code.
        result["error"] = "".join(traceback.format_exception(*sys.exc_info())[1:])
    finally:
        sys.stdout, sys.stderr = stdout, stderr
    return result


def main():
    apply_limits()
    # SIGINT must raise inside the running cell rather than kill the process, which is what makes
    # interrupt leave the interpreter and its variables alive.
    signal.signal(signal.SIGINT, signal.default_int_handler)
    namespace = {"__name__": "__main__"}
    # The pid travels in the ready message because the server targets Java 8 bytecode, where
    # Process.pid() does not exist; interrupt needs it to send SIGINT.
    print(
        json.dumps(
            {"type": "ready", "version": sys.version.split()[0], "pid": os.getpid()}
        ),
        flush=True,
    )

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except ValueError:
            continue
        if request.get("type") == "shutdown":
            break
        if request.get("type") != "execute":
            continue
        response = execute(request.get("source", ""), namespace)
        response["type"] = "result"
        response["id"] = request.get("id")
        print(json.dumps(response), flush=True)


if __name__ == "__main__":
    main()
