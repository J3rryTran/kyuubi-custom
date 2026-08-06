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
    id varchar(36) PRIMARY KEY NOT NULL,
    notebook_id varchar(36) NOT NULL,
    owner varchar(255) NOT NULL,
    state varchar(32) NOT NULL,
    runtime_profile varchar(255),
    created_at bigint NOT NULL,
    last_activity_at bigint NOT NULL,
    stopped_at bigint,
    failure_message varchar(4096),
    kyuubi_instance varchar(255),
    version bigint NOT NULL,
    KEY notebook_session_notebook_id_index(notebook_id),
    KEY notebook_session_owner_index(owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS notebook_runtime(
    id varchar(36) PRIMARY KEY NOT NULL,
    notebook_session_id varchar(36) NOT NULL,
    runtime_spec_id varchar(32) NOT NULL,
    runtime_type varchar(32) NOT NULL,
    language varchar(32) NOT NULL,
    owner varchar(255) NOT NULL,
    state varchar(32) NOT NULL,
    generation bigint NOT NULL,
    environment_revision_id varchar(36),
    created_at bigint NOT NULL,
    last_activity_at bigint NOT NULL,
    stopped_at bigint,
    failure_message varchar(4096),
    internal_runtime_handle varchar(255),
    internal_runtime_location varchar(255),
    version bigint NOT NULL,
    KEY notebook_runtime_notebook_session_id_index(notebook_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS notebook_execution(
    id varchar(36) PRIMARY KEY NOT NULL,
    notebook_id varchar(36) NOT NULL,
    notebook_session_id varchar(36) NOT NULL,
    runtime_id varchar(36) NOT NULL,
    runtime_generation bigint NOT NULL,
    cell_id varchar(36),
    cell_version bigint,
    language varchar(32) NOT NULL,
    source_snapshot mediumtext NOT NULL,
    state varchar(32) NOT NULL,
    submitted_at bigint NOT NULL,
    started_at bigint,
    finished_at bigint,
    submitted_by varchar(255) NOT NULL,
    error_code varchar(32),
    error_message varchar(4096),
    client_request_id varchar(255),
    notebook_run_id varchar(36),
    internal_operation_handle varchar(255),
    version bigint NOT NULL,
    KEY notebook_execution_notebook_id_index(notebook_id),
    KEY notebook_execution_notebook_session_id_index(notebook_session_id),
    KEY notebook_execution_runtime_id_index(runtime_id),
    KEY notebook_execution_notebook_run_id_index(notebook_run_id),
    KEY notebook_execution_request_index(submitted_by, client_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS notebook_execution_event(
    execution_id varchar(36) NOT NULL,
    event_sequence bigint NOT NULL,
    event_type varchar(32) NOT NULL,
    payload varchar(4096),
    created_at bigint NOT NULL,
    PRIMARY KEY (execution_id, event_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS notebook_run(
    id varchar(36) PRIMARY KEY NOT NULL,
    notebook_id varchar(36) NOT NULL,
    notebook_session_id varchar(36) NOT NULL,
    state varchar(32) NOT NULL,
    submitted_at bigint NOT NULL,
    started_at bigint,
    finished_at bigint,
    submitted_by varchar(255) NOT NULL,
    requested_cell_ids mediumtext,
    current_cell_id varchar(36),
    failure_policy varchar(32) NOT NULL,
    version bigint NOT NULL,
    KEY notebook_run_notebook_id_index(notebook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
