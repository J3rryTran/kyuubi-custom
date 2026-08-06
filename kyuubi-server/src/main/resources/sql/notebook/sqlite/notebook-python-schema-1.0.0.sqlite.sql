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

-- Python environments. internal_environment_location holds the on-disk path of a revision and
-- is never returned by the API.

CREATE TABLE IF NOT EXISTS python_environment(
    id TEXT PRIMARY KEY NOT NULL,
    owner TEXT NOT NULL,
    name TEXT NOT NULL,
    runtime_spec_id TEXT NOT NULL,
    python_version TEXT,
    active_revision_id TEXT,
    state TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    updated_by TEXT NOT NULL,
    version INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS python_environment_owner_index ON python_environment(owner);
CREATE INDEX IF NOT EXISTS python_environment_owner_name_index ON python_environment(owner, name);

CREATE TABLE IF NOT EXISTS python_environment_revision(
    id TEXT PRIMARY KEY NOT NULL,
    environment_id TEXT NOT NULL,
    revision_number INTEGER NOT NULL,
    state TEXT NOT NULL,
    requirements TEXT,
    resolved_packages TEXT,
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    activated_at INTEGER,
    failure_message TEXT,
    internal_environment_location TEXT,
    UNIQUE (environment_id, revision_number)
);

CREATE INDEX IF NOT EXISTS python_environment_revision_environment_id_index ON python_environment_revision(environment_id);

CREATE TABLE IF NOT EXISTS python_package_operation(
    id TEXT PRIMARY KEY NOT NULL,
    environment_id TEXT NOT NULL,
    base_revision_id TEXT,
    target_revision_id TEXT,
    action TEXT NOT NULL,
    requested_packages TEXT,
    state TEXT NOT NULL,
    submitted_at INTEGER NOT NULL,
    started_at INTEGER,
    finished_at INTEGER,
    submitted_by TEXT NOT NULL,
    client_request_id TEXT,
    error_code TEXT,
    error_message TEXT,
    operation_log TEXT,
    version INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS python_package_operation_environment_id_index ON python_package_operation(environment_id);
CREATE INDEX IF NOT EXISTS python_package_operation_request_index ON python_package_operation(submitted_by, client_request_id);
