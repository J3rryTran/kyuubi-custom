/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.notebook

import org.apache.kyuubi.config.{ConfigEntry, OptionalConfigEntry}
import org.apache.kyuubi.config.KyuubiConf.buildConf

object NotebookConf {

  val NOTEBOOK_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.enabled")
      .doc("Whether to enable the notebook subsystem and its REST endpoints. The notebook " +
        "stores its state in the same JDBC database as the server metadata store, configured " +
        "by `kyuubi.metadata.store.jdbc.*`.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val NOTEBOOK_SCHEMA_INIT: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.store.schema.init")
      .doc("Whether to create the notebook tables at startup if they are missing.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val NOTEBOOK_CELL_SOURCE_MAX_SIZE: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.cell.source.max.size")
      .doc("Maximum size in bytes of a single cell source. Requests exceeding it are rejected.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .createWithDefault(1024 * 1024)

  val NOTEBOOK_MAX_CELLS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.max.cells")
      .doc("Maximum number of cells a single notebook may contain.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(500)

  val NOTEBOOK_MAX_PAGE_SIZE: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.max.page.size")
      .doc("Upper bound for the `limit` parameter of notebook list and search endpoints.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(200)

  val NOTEBOOK_AUTO_REVISION_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.revision.auto.enabled")
      .doc("Whether to snapshot a notebook automatically when its content changes.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val NOTEBOOK_MAX_REVISIONS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.revision.max.per.notebook")
      .doc("How many revisions to retain per notebook. The oldest unprotected revisions are " +
        "trimmed beyond this count; revisions created by a restore are protected and kept.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(100)

  val NOTEBOOK_RUNTIME_IDLE_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.runtime.idle.timeout")
      .doc("How long a runtime may sit idle before it is stopped and its resources released. " +
        "A session whose runtimes have all been reclaimed is stopped with them. Set to 0 to " +
        "keep runtimes until they are stopped explicitly.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.HOURS.toMillis(1))

  val NOTEBOOK_RUNTIME_IDLE_CHECK_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.runtime.idle.check.interval")
      .doc("How often idle runtimes are looked for.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.MINUTES.toMillis(5))

  val NOTEBOOK_EXECUTION_LOG_MAX_LINES: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.execution.log.max.lines")
      .doc("Maximum number of log lines returned by one execution log request.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(1000)

  val NOTEBOOK_EVENT_MAX_WAIT_MS: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.execution.event.max.wait.ms")
      .doc("Upper bound for the `waitMillis` long-polling parameter of the execution event " +
        "endpoint. It caps how long a request may occupy a server thread.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .createWithDefault(10000L)

  // ---------------------------------------------------------------------------------------------
  // Python runtime and environments
  // ---------------------------------------------------------------------------------------------

  val PYTHON_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.python.enabled")
      .doc("Whether to offer a CPython runtime. It requires `python3` with `venv` and `pip` on " +
        "the server host; the runtime spec reports itself disabled when they are missing.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val PYTHON_EXECUTABLE: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.executable")
      .doc("Interpreter used to build environments and to start runtimes.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("python3")

  val PYTHON_WORK_DIR: OptionalConfigEntry[String] =
    buildConf("kyuubi.notebook.python.work.dir")
      .doc("Root directory for Python environments and runtime scratch space. Defaults to " +
        "`<KYUUBI_HOME>/work/notebook-python`. Each user gets a private subdirectory.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createOptional

  val PYTHON_LIMIT_CPU_SECONDS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.limit.cpu.seconds")
      .doc("CPU seconds a Python runtime may consume before the operating system kills it.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(3600)

  val PYTHON_LIMIT_MEMORY_MB: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.limit.memory.mb")
      .doc("Address space a Python runtime may map, in MiB.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(2048)

  val PYTHON_LIMIT_PROCESSES: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.limit.processes")
      .doc("Maximum number of processes the runtime user may hold, which bounds a fork bomb.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(256)

  val PYTHON_EXECUTION_TIMEOUT_SECONDS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.execution.timeout.seconds")
      .doc("How long a single Python cell may run before it is interrupted.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(3600)

  val PYTHON_VENV_SYSTEM_SITE_PACKAGES: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.python.venv.system.site.packages")
      .doc("Whether an environment can see the packages installed in the server's own Python. " +
        "This is what makes packages baked into the image usable without a download, and it " +
        "keeps each revision small because only the difference is installed. A user can still " +
        "install another version of the same distribution; theirs takes precedence.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val PYTHON_PACKAGE_INDEX_URL: OptionalConfigEntry[String] =
    buildConf("kyuubi.notebook.python.package.index.url")
      .doc("Package index or internal mirror used for installs. Callers cannot override it: a " +
        "request carries requirement names only, never pip options.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createOptional

  val PYTHON_PACKAGE_ALLOWLIST: ConfigEntry[Seq[String]] =
    buildConf("kyuubi.notebook.python.package.allowlist")
      .doc("Distributions users may install. Empty means no allowlist, in which case only the " +
        "denylist applies. Names are compared after PEP 503 normalization.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .toSequence()
      .createWithDefault(Nil)

  val PYTHON_PACKAGE_DENYLIST: ConfigEntry[Seq[String]] =
    buildConf("kyuubi.notebook.python.package.denylist")
      .doc("Distributions users may never install, applied even when an allowlist permits them.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .toSequence()
      .createWithDefault(Nil)

  val PYTHON_PACKAGE_CONSTRAINTS_FILE: OptionalConfigEntry[String] =
    buildConf("kyuubi.notebook.python.package.constraints.file")
      .doc("Path to a pip constraints file applied to every install, so an administrator can " +
        "pin transitive versions without restricting what users may ask for.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createOptional

  val PYTHON_PACKAGE_MAX_COUNT: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.package.max.count")
      .doc("Maximum number of directly requested packages an environment may hold.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(100)

  val PYTHON_PACKAGE_INSTALL_TIMEOUT_SECONDS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.package.install.timeout.seconds")
      .doc("How long one package operation may run before it is killed and marked failed.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(900)

  val PYTHON_ENVIRONMENT_MAX_SIZE_MB: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.environment.max.size.mb")
      .doc("Disk an environment revision may occupy. A build that exceeds it is discarded and " +
        "the previous revision stays active.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(4096)

  val PYTHON_ENVIRONMENT_MAX_PER_USER: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.environment.max.per.user")
      .doc("How many environments one user may own.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(5)

  val PYTHON_ENVIRONMENT_KEEP_REVISIONS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.python.environment.keep.revisions")
      .doc("How many superseded revisions to keep on disk for rollback.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(2)

  val NOTEBOOK_IMPORT_MAX_SIZE: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.import.max.size")
      .doc("Maximum size in bytes of a notebook document accepted by the import endpoint.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .createWithDefault(16 * 1024 * 1024)
}
