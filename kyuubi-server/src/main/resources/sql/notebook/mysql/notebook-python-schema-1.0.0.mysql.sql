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
    id varchar(36) PRIMARY KEY NOT NULL,
    owner varchar(255) NOT NULL,
    name varchar(255) NOT NULL,
    runtime_spec_id varchar(32) NOT NULL,
    python_version varchar(32),
    active_revision_id varchar(36),
    state varchar(32) NOT NULL,
    created_at bigint NOT NULL,
    created_by varchar(255) NOT NULL,
    updated_at bigint NOT NULL,
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL,
    KEY python_environment_owner_index(owner),
    KEY python_environment_owner_name_index(owner, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS python_environment_revision(
    id varchar(36) PRIMARY KEY NOT NULL,
    environment_id varchar(36) NOT NULL,
    revision_number bigint NOT NULL,
    state varchar(32) NOT NULL,
    requirements mediumtext,
    resolved_packages mediumtext,
    created_at bigint NOT NULL,
    created_by varchar(255) NOT NULL,
    activated_at bigint,
    failure_message varchar(4096),
    internal_environment_location varchar(4096),
    UNIQUE (environment_id, revision_number),
    KEY python_environment_revision_environment_id_index(environment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS python_package_operation(
    id varchar(36) PRIMARY KEY NOT NULL,
    environment_id varchar(36) NOT NULL,
    base_revision_id varchar(36),
    target_revision_id varchar(36),
    action varchar(32) NOT NULL,
    requested_packages mediumtext,
    state varchar(32) NOT NULL,
    submitted_at bigint NOT NULL,
    started_at bigint,
    finished_at bigint,
    submitted_by varchar(255) NOT NULL,
    client_request_id varchar(255),
    error_code varchar(32),
    error_message varchar(4096),
    operation_log mediumtext,
    version bigint NOT NULL,
    KEY python_package_operation_environment_id_index(environment_id),
    KEY python_package_operation_request_index(submitted_by, client_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
