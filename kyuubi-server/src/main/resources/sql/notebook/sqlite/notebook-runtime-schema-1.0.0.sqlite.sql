--
-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- Runtime-side tables. internal_* columns hold Kyuubi session and operation handles and the
-- instance that owns them; they are never returned by the API.

CREATE TABLE IF NOT EXISTS notebook_session(
    id TEXT PRIMARY KEY NOT NULL,
    notebook_id TEXT NOT NULL,
    owner TEXT NOT NULL,
    state TEXT NOT NULL,
    runtime_profile TEXT,
    created_at INTEGER NOT NULL,
    last_activity_at INTEGER NOT NULL,
    stopped_at INTEGER,
    failure_message TEXT,
    kyuubi_instance TEXT,
    version INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS notebook_session_notebook_id_index ON notebook_session(notebook_id);
CREATE INDEX IF NOT EXISTS notebook_session_owner_index ON notebook_session(owner);

CREATE TABLE IF NOT EXISTS notebook_runtime(
    id TEXT PRIMARY KEY NOT NULL,
    notebook_session_id TEXT NOT NULL,
    runtime_spec_id TEXT NOT NULL,
    runtime_type TEXT NOT NULL,
    language TEXT NOT NULL,
    owner TEXT NOT NULL,
    state TEXT NOT NULL,
    generation INTEGER NOT NULL,
    environment_revision_id TEXT,
    created_at INTEGER NOT NULL,
    last_activity_at INTEGER NOT NULL,
    stopped_at INTEGER,
    failure_message TEXT,
    internal_runtime_handle TEXT,
    internal_runtime_location TEXT,
    version INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS notebook_runtime_notebook_session_id_index ON notebook_runtime(notebook_session_id);

CREATE TABLE IF NOT EXISTS notebook_execution(
    id TEXT PRIMARY KEY NOT NULL,
    notebook_id TEXT NOT NULL,
    notebook_session_id TEXT NOT NULL,
    runtime_id TEXT NOT NULL,
    runtime_generation INTEGER NOT NULL,
    cell_id TEXT,
    cell_version INTEGER,
    language TEXT NOT NULL,
    source_snapshot TEXT NOT NULL,
    state TEXT NOT NULL,
    submitted_at INTEGER NOT NULL,
    started_at INTEGER,
    finished_at INTEGER,
    submitted_by TEXT NOT NULL,
    error_code TEXT,
    error_message TEXT,
    client_request_id TEXT,
    notebook_run_id TEXT,
    internal_operation_handle TEXT,
    version INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS notebook_execution_notebook_id_index ON notebook_execution(notebook_id);
CREATE INDEX IF NOT EXISTS notebook_execution_notebook_session_id_index ON notebook_execution(notebook_session_id);
CREATE INDEX IF NOT EXISTS notebook_execution_runtime_id_index ON notebook_execution(runtime_id);
CREATE INDEX IF NOT EXISTS notebook_execution_notebook_run_id_index ON notebook_execution(notebook_run_id);
CREATE INDEX IF NOT EXISTS notebook_execution_request_index ON notebook_execution(submitted_by, client_request_id);

CREATE TABLE IF NOT EXISTS notebook_execution_event(
    execution_id TEXT NOT NULL,
    event_sequence INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    payload TEXT,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (execution_id, event_sequence)
);


CREATE TABLE IF NOT EXISTS notebook_run(
    id TEXT PRIMARY KEY NOT NULL,
    notebook_id TEXT NOT NULL,
    notebook_session_id TEXT NOT NULL,
    state TEXT NOT NULL,
    submitted_at INTEGER NOT NULL,
    started_at INTEGER,
    finished_at INTEGER,
    submitted_by TEXT NOT NULL,
    requested_cell_ids TEXT,
    current_cell_id TEXT,
    failure_policy TEXT NOT NULL,
    version INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS notebook_run_notebook_id_index ON notebook_run(notebook_id);
