# Plan: Configurable StarRocks Ranger Mode for Kyuubi Spark AuthZ

## Summary

Implement an optional StarRocks Ranger authorization mode for
`extensions/spark/kyuubi-spark-authz` while keeping the current Spark/Hive Ranger
mode as the default. The goal is to let Spark SQL authorization reuse the same
Ranger `starrocks` service and catalog-aware policies that are already used by
StarRocks for Lakehouse data.

The implementation must be backward compatible, upstream-friendly, and complete
for authorization, catalog-level checks, row filtering, data masking,
show-object filtering, audit, tests, and docs.

Key references:

- `plans/ideal.md` — original requirement
- `plans/plan-claude.md` — review of this plan's first revision
- StarRocks Ranger plugin docs:
  https://docs.starrocks.io/docs/administration/user_privs/authorization/ranger_plugin/
- Official StarRocks Ranger service definition:
  https://raw.githubusercontent.com/StarRocks/starrocks/main/conf/ranger/ranger-servicedef-starrocks.json

## Verified Code Baseline

All facts below were verified against the working tree before writing this plan.
Ranger version is **2.5.0**, declared in `extensions/spark/kyuubi-spark-authz/pom.xml:35`
(not in the root `pom.xml`).

| Fact | Evidence |
|---|---|
| Singleton hard-codes appId/serviceType at class-load | `ranger/SparkRangerAdminPlugin.scala:30` — `object SparkRangerAdminPlugin extends RangerBasePlugin("spark", "sparkSql") with RangerConfigProvider` |
| Eager initialization in the extension constructor | `ranger/RangerSparkExtension.scala:45` — `SparkRangerAdminPlugin.initialize()` in the class body |
| Ranger conf keys interpolate the service type | `ranger/SparkRangerAdminPlugin.scala:42,60` — `s"ranger.plugin.${getServiceType}..."` |
| `initialize()` also registers the shutdown hook | `ranger/SparkRangerAdminPlugin.scala:67-85` |
| `getRangerConf` is an eager `val` bound to `this` as a `RangerBasePlugin` | `ranger/RangerConfigProvider.scala` — `val getRangerConf = invokeAs(this, "getConfig")` |
| Access string is derived from the Scala enum name | `ranger/AccessRequest.scala:60` — `req.setAccessType(accessType.toString.toLowerCase)` |
| `USE` is mapped to ANY_ACCESS, not a named access | `ranger/AccessRequest.scala:59` — `case USE => req.setAccessType(RangerPolicyEngine.ANY_ACCESS)` |
| User roles + cluster name are read by **reflection on the singleton**, in exception-swallowing `try/catch` | `ranger/AccessRequest.scala:47-56` (`getRolesFromUserAndGroups`), `62-67` (`getClusterName`) |
| `AccessResource` never emits a `catalog` resource key | `ranger/AccessResource.scala:54-77` — sets only `database`/`table`/`column`/`udf`/`url`; `catalog` is only a constructor field (line 31) |
| `VIEW` and `INDEX` are collapsed into the `table` resource key | `ranger/AccessResource.scala:64-66` — `case TABLE \| VIEW \| INDEX => setValue("table", ...)` |
| Service def is taken from the live delegate | `ranger/AccessResource.scala:78` — `resource.setServiceDef(SparkRangerAdminPlugin.getServiceDef)` |
| Hive-style access enum, decoupled from resource level | `ranger/AccessType.scala:28` — `NONE, CREATE, ALTER, DROP, SELECT, UPDATE, USE, READ, WRITE, ALL, ADMIN, INDEX, TEMPUDFADMIN` |
| `CREATETABLE` output builds a **table**-level resource with `CREATE` | `ranger/AccessType.scala:42-44` + `ObjectType.scala:31-38` |
| Main authz path already carries catalog | `ranger/AccessResource.scala:90-100` — `apply(obj, opType)` passes `obj.catalog` |
| Row filter drops catalog | `rule/rowfilter/RuleApplyRowFilter.scala:45` — `AccessResource(TABLE, db, table, null)` |
| Data masking drops catalog | `rule/datamasking/RuleApplyDataMaskingStage0.scala:64` — `AccessResource(COLUMN, db, table, attr.name)` |
| Show filtering drops catalog | `rule/rowfilter/RuleReplaceShowObjectCommands.scala:57,92,113`; `rule/rowfilter/FilteredShowObjectsExec.scala:52,76` |
| Catalog is already extracted and carried | `serde/Table.scala`, `serde/Database.scala`, `PrivilegeObject.scala` expose `catalog: Option[String]`; filled by `serde/catalogExtractors.scala` and `serde/tableExtractors.scala` |
| Test policies are **generated**, not hand-written | `src/test/gen/scala/.../gen/PolicyJsonFileGenerator.scala:63-86`, run via `dev/gen/gen_ranger_policy_json.sh` with `-Pgen-policy` and `KYUUBI_UPDATE=1` |
| The generated file embeds the Hive service def | `src/test/resources/policies_base.json` → `serviceDef.name = "hive"`, resources `[database, url, table, udf, column]`, accessTypes `[select, update, create, drop, alter, index, lock, all, read, write]` |
| Test client hard-codes the policy file name | `src/test/scala/.../ranger/RangerLocalClient.scala:36` — `getResourceAsStream("sparkSql_hive_jenkins.json")` |
| Test Ranger conf keys | `src/test/resources/ranger-spark-security.xml` — `ranger.plugin.spark.{service.name, policy.source.impl, policy.rest.url, policy.cache.dir}` |

**Good news that narrows the work:** `catalog` already exists end-to-end in
`PrivilegeObject` and is already passed on the main authorization path. The
remaining work is to (a) turn `catalog` into a real Ranger resource key, (b) build
a StarRocks resource+access mapper, (c) thread catalog into the secondary paths,
and (d) select the delegate from config.

## Official StarRocks Service Definition (verbatim)

Fetched from the URL above. Every resource key and access string used in
StarRocks mode MUST come from this list, character for character.

Service def name: `starrocks`

Resources — `name (level, parent, mandatory)`:

- `catalog` (10, —, true)
- `database` (20, `catalog`, true)
- `table` (30, `database`, true)
- `column` (40, `table`, true)
- `view` (30, `database`, true)
- `materialized_view` (30, `database`, true)
- `function` (30, `database`, true)
- `global_function` (10, —, true)
- `resource` (10, —, true)
- `resource_group` (10, —, true)
- `storage_volume` (10, —, true)
- `user` (10, —, true)
- `system` (10, —, true)

Access types:

`grant`, `node`, `operate`, `delete`, `drop`, `insert`, `select`, `alter`,
`export`, `update`, `usage`, `plugin`, `file`, `blacklist`, `repository`,
`refresh`, `impersonate`, `create database`, `create table`, `create view`,
`create function`, `create global function`, `create materialized view`,
`create resource`, `create resource group`, `create external catalog`,
`create storage volume`

Three consequences that drive the design:

1. **`catalog` is a mandatory level-10 resource** and `database` declares
   `catalog` as its parent. A resource map without `catalog` cannot match any
   StarRocks policy — catalog normalization is not optional.
2. **`view` and `materialized_view` are siblings of `table`, not aliases.** The
   current code collapses `ObjectType.VIEW` into the `table` key
   (`AccessResource.scala:64`), which is wrong for StarRocks.
3. **Four access strings contain spaces** (`create database`, `create table`,
   `create view`, `create function`). There is **no `url`, no `index`, and no
   `create index`** access type or resource.

## Design Decisions

- Add a configurable mode instead of replacing existing behavior:
  - `spark` mode remains the default and keeps the existing Hive-compatible
    Ranger behavior, byte-for-byte.
  - `starrocks` mode uses the native StarRocks Ranger service definition.
- Use one lazy, thread-safe Ranger delegate per JVM/application, selected from
  Spark conf. A Spark application should not initialize both Spark and
  StarRocks Ranger delegates.
- Treat StarRocks mapping as a two-dimensional mapping:
  - The access string changes, for example `select`, `usage`, `create table`.
  - The target resource level can also change, for example `CREATE TABLE`
    checks `create table` on a **database** resource, not a table resource.
- **Fail closed.** Any operation with no StarRocks equivalent raises
  `AccessControlException`. Never emit an access string that is absent from the
  service def — that produces a no-policy-match whose outcome depends on policy
  engine defaults, which is exactly how a silent bypass happens.
- Keep StarRocks service definition data in test resources unless maintainers
  explicitly request bundling it in main resources.
- Because this is intended for upstream Kyuubi, discuss the feature/config shape
  with maintainers before opening a PR.

## Public Configs

Read these as Spark-side authz configs from `SparkSession.conf`, not as
server-side `KyuubiConf` entries. Because they are engine-side Spark confs, they
are **not** registered in `KyuubiConf` and therefore do **not** require
`dev/gen/gen_all_config_docs.sh`; they are documented by hand in the Spark AuthZ
docs instead. Confirm this placement with maintainers.

- `spark.kyuubi.authz.ranger.service.type`
  - Allowed values: `spark`, `starrocks`
  - Default: `spark`
  - Reject any other value at initialization with a clear error.
- `spark.kyuubi.authz.ranger.starrocks.default.catalog`
  - Default: `default_catalog`
  - Used when Spark does not expose a catalog.
- `spark.kyuubi.authz.ranger.starrocks.catalog.mapping`
  - Optional comma-separated mapping: `sparkCatalog=starrocksCatalog`
  - Example: `iceberg_prod=lakehouse,spark_catalog=default_catalog`
  - Apply this mapping in one shared catalog-normalization function used by all
    authorization, row-filter, masking, and show-filter resource creation.

Runtime Ranger configs. Note that `SparkRangerAdminPlugin` interpolates
`getServiceType` into its conf keys (`SparkRangerAdminPlugin.scala:42,60`), so the
existing tuning knobs automatically move to the `starrocks` namespace:

- Existing mode keeps `ranger.plugin.spark.*`.
- StarRocks mode uses `ranger.plugin.starrocks.*`, including:
  - `ranger.plugin.starrocks.service.name`
  - `ranger.plugin.starrocks.policy.source.impl`
  - `ranger.plugin.starrocks.policy.rest.url`
  - `ranger.plugin.starrocks.policy.cache.dir`
  - `ranger.plugin.starrocks.authorize.in.single.call`
  - `ranger.plugin.starrocks.use.usergroups.from.userstore.enabled`

## Implementation Plan

### 1. Pre-flight

- Verify an `apache` remote points to `https://github.com/apache/kyuubi.git`.
- Do not switch branches while local `.gitignore` or `plans/` changes are
  present unless the user stashes or commits them.
- Keep edits scoped to `extensions/spark/kyuubi-spark-authz`, its tests,
  test resources, and Spark AuthZ docs unless a build file change is required.
- If this becomes an upstream PR, create or link a Kyuubi issue and ask
  maintainers about the profile/config approach first.

### 2. Lazy Ranger Delegate

Refactor `ranger/SparkRangerAdminPlugin.scala` and `ranger/RangerSparkExtension.scala`.

- Remove eager initialization from `RangerSparkExtension.scala:45`.
- Convert `SparkRangerAdminPlugin` into a facade that lazily initializes exactly
  one `RangerBasePlugin` delegate on first use, when a `SparkSession` exists and
  Spark conf can be read.
- Select delegate from `spark.kyuubi.authz.ranger.service.type`:
  - `spark` -> current behavior, equivalent to `RangerBasePlugin("spark", "sparkSql")`
  - `starrocks` -> `RangerBasePlugin("starrocks", "starrocks")`
- Keep facade methods expected by current call sites:
  `isAccessAllowed`, `evalRowFilterPolicies`, `evalDataMaskPolicies`,
  `getServiceDef`, `getRangerConf`, `getFilterExpr`, `getMaskingExpr`, `verify`,
  `authorizeInSingleCall`, `useUserGroupsFromUserStoreEnabled`.
- Make initialization idempotent and thread-safe with `synchronized` or an
  equivalent lazy holder.
- Register the cleanup shutdown hook once, for the selected delegate. Note that
  today `initialize()` does both `init()` and hook registration
  (`SparkRangerAdminPlugin.scala:67-70`); keep them together in the lazy path.

#### 2a. Three refactor hazards that must be handled explicitly

These are the reason this step is riskier than it looks. All three are silent
failures — nothing throws, behavior just degrades.

1. **Reflection against the singleton breaks.** `AccessRequest.scala:47-56` and
   `62-67` call `invokeAs(SparkRangerAdminPlugin, "getRolesFromUserAndGroups")`
   and `invokeAs(SparkRangerAdminPlugin, "getClusterName")` — methods that exist
   only because the object *is* a `RangerBasePlugin`. Both calls sit inside
   `try { ... } catch { case _: Exception => }`. If the facade stops extending
   `RangerBasePlugin`, these silently no-op: **Ranger role-based policies stop
   applying and audit records lose the cluster name, with no error.** Fix by
   routing both through explicit facade methods that forward to the delegate, and
   add a test asserting user roles are populated on the request.
2. **`RangerConfigProvider.getRangerConf` is an eager `val`** that does
   `invokeAs(this, "getConfig")`. On a facade that is no longer a
   `RangerBasePlugin`, or that is not yet initialized, this either throws at
   class-load or captures the wrong config. Convert it to a `def` that reads from
   the resolved delegate, and keep the Ranger 2.0-vs-2.1 branch intact.
3. **Config-read ordering.** `RangerBasePlugin(appId, serviceType)` decides which
   `ranger-<serviceType>-security.xml` is loaded, so the service type cannot be
   read from Ranger conf — it must come from Spark conf, which is only available
   after the session exists. This is precisely why initialization must move out of
   the extension constructor.

Consider proposing this step as its own PR; see the maintainer questions.

### 3. Profile-Aware Resource Model

Add a small service profile abstraction, for example `RangerServiceProfile`, with
`SparkProfile` and `StarRocksProfile`. Put the profile behind the single resolved
delegate so there is exactly one profile per JVM.

For `spark` mode:

- Preserve current resource keys and behavior exactly: `database`, `table`,
  `column`, `udf`, `url`; `VIEW`/`INDEX` continue to collapse into `table`.
- Do not emit `catalog` into the Ranger resource map.

For `starrocks` mode:

- Emit resource keys matching the official StarRocks service definition exactly:
  - `catalog` — always set, after normalization; mandatory level-10 resource
  - `database`, `table`, `column`
  - `view` — for `ObjectType.VIEW`, which must **no longer** collapse into `table`
  - `function` — replaces the Hive `udf` key
- Optional, decide with maintainers: map Spark materialized views to
  `materialized_view`. If not mapped, treat as unsupported rather than silently
  using `table`.
- Do not emit `global_function`, `resource`, `resource_group`, `storage_volume`,
  `user`, or `system`; Spark SQL has no corresponding privilege objects.
- Normalize catalog through the shared mapping/default logic before setting the
  Ranger resource.
- Use the StarRocks service definition returned by the initialized delegate via
  `getServiceDef` (`AccessResource.scala:78` already does this, so it becomes
  correct automatically once the delegate loads StarRocks policies).
- Explicitly reject DFS/local URI authorization in StarRocks mode with a clear
  unsupported-resource error, because the StarRocks service def has no `url`
  resource.

### 4. StarRocks Access and Resource-Level Mapping

Do not implement StarRocks as a simple replacement for the current Hive-style
`AccessType` enum. Add a mapper (for example `StarRocksAccessMapper`) that returns
both the target resource level and the native StarRocks access string.

#### 4a. Blocker: the access string cannot come from the enum name

`AccessRequest.scala:60` builds the access string as
`accessType.toString.toLowerCase`. A Scala `Enumeration` value auto-named from its
identifier can never render `create table` — identifiers cannot contain spaces.
Additionally `AccessRequest.scala:59` special-cases `USE` to
`RangerPolicyEngine.ANY_ACCESS`, whereas StarRocks expects the named access
`usage` on a catalog resource.

Therefore `AccessRequest` must stop deriving the string from the enum name.
Required change: have the profile/mapper supply the literal access string, and let
`AccessRequest.apply` set it directly. Keep the `spark`-mode result identical
(lowercased enum name, `USE` -> `ANY_ACCESS`) so existing policies keep matching.

#### 4b. Required StarRocks mappings

| Kyuubi operation | StarRocks resource level | Access string |
|---|---|---|
| `QUERY`, table scan, `ANALYZE_TABLE`, `SHOW_CREATETABLE`, `SHOW_TBLPROPERTIES`, `SHOWPARTITIONS` | table, or column when columns are present | `select` |
| `SHOWCOLUMNS`, `DESCTABLE` | column / table | `select` |
| `CREATEDATABASE` | catalog | `create database` |
| `CREATETABLE`, `CREATETABLE_AS_SELECT` (output) | database | `create table` |
| `CREATETABLE_AS_SELECT` (input) | table / column | `select` |
| `CREATEVIEW` (output) | database | `create view` |
| `ALTERVIEW_AS` (input) | table / column | `select` |
| `CREATEFUNCTION` | database | `create function` |
| `LOAD` (output), insert, insert overwrite | table | `insert` |
| `LOAD` (input) | table / column | `select` |
| `UPDATE`, `TRUNCATETABLE`, `PrivilegeObjectActionType` other-than-OTHER writes | table | `update` |
| Delete operations (`PrivilegeObjectActionType.DELETE`) | matching object level | `delete` for row deletes, `drop` for object removal |
| `DROPDATABASE` | database | `drop` |
| `DROPTABLE` | table | `drop` |
| `DROPVIEW` | view | `drop` |
| `DROPFUNCTION` | function | `drop` |
| `ALTERDATABASE`, `ALTERDATABASE_LOCATION` | database | `alter` |
| `ALTERTABLE_*`, `MSCK` | table | `alter` |
| `ALTERVIEW_RENAME` | view | `alter` |
| `SHOWDATABASES`, `SWITCHDATABASE`, `DESCDATABASE`, `SHOWTABLES`, catalog visibility | catalog, and database where Spark exposes it | `usage` |
| `SHOWFUNCTIONS`, `DESCFUNCTION`, UDF execution | function | `usage` |

Note the semantic split that `AccessType.scala:81` currently blurs:
`PrivilegeObjectActionType.DELETE` maps to Hive `DROP`, but StarRocks has both
`delete` (row-level DML) and `drop` (object removal) as distinct access types. The
mapper must choose based on the operation, not the action type alone.

#### 4c. Fail-closed list

These have no StarRocks equivalent and MUST raise `AccessControlException` with a
message naming the unsupported operation and the configured service type:

- `ObjectType.URI` / `PrivilegeObjectType.DFS_URI` / `LOCAL_URI` — no `url`
  resource exists.
- `AccessType.TEMPUDFADMIN` (`OperationType.ADD`, i.e. `ADD JAR`/`ADD FILE`) — the
  StarRocks `plugin` and `file` access types are system-level and are not an
  equivalent; do not repurpose them.
- `AccessType.INDEX` (`CREATEINDEX`), `DROPINDEX`, `ALTERINDEX_REBUILD`, and
  `ObjectType.INDEX` — no `index` resource or access type exists.
- `AccessType.NONE` — must not be silently sent as the string `none`.

Add one unit test per entry asserting the exception, and assert that no Ranger
request is issued.

All resource keys and access strings must be copied exactly from the StarRocks
Ranger service definition. Tests must verify real allow/deny behavior, not only
object shapes.

### 5. Propagate Catalog Everywhere

The main authorization path already carries `catalog` in `PrivilegeObject`
(`AccessResource.scala:90-100`); the missing work is the secondary paths. Update:

- `rule/rowfilter/RuleApplyRowFilter.scala:45` — build table resources with
  `table.catalog`.
- `rule/datamasking/RuleApplyDataMaskingStage0.scala:64` — build column resources
  with `table.catalog`.
- `rule/rowfilter/RuleReplaceShowObjectCommands.scala:57,92,113` — include catalog
  for show-table, show-column, and show-function checks where Spark exposes it.
- `rule/rowfilter/FilteredShowObjectsExec.scala:52,76` — include catalog for V2
  show namespace/table filtering.

These call sites use the `AccessResource(objectType, first, second, third)`
overload, which defaults `catalog = None`; passing catalog means using the
existing named parameter, not a new overload.

In `spark` mode this remains behaviorally unchanged because the Spark profile
does not emit `catalog` as a Ranger resource key.

### 6. Test Resources

The Hive test policy file is **generated**, not hand-maintained:
`PolicyJsonFileGenerator.scala:63-86` merges generated `RangerPolicy` objects into
`policies_base.json` (which supplies `serviceName`, `serviceId`, `policyVersion`,
and the `hive` `serviceDef`) and writes `sparkSql_hive_jenkins.json`. It runs via
`dev/gen/gen_ranger_policy_json.sh`, which uses the `gen-policy` Maven profile and
`KYUUBI_UPDATE=1`; with `KYUUBI_UPDATE=0` the same suite asserts the checked-in
file is current. A hand-written StarRocks JSON would break that golden-file check
convention, so mirror the generator instead.

Work items under `extensions/spark/kyuubi-spark-authz/src/test`:

- Add `resources/starrocks_policies_base.json` carrying the official StarRocks
  `serviceDef` verbatim (all resources and access types listed above) plus
  `serviceName`/`serviceId`/`policyVersion`.
- Add `gen/scala/.../gen/StarRocksPolicyJsonFileGenerator.scala` mirroring the
  existing generator, emitting `starrocks_policies.json`. Extend
  `RangerGenWrapper.scala` with StarRocks helpers alongside the existing
  `KRangerPolicyResource.{databaseRes, tableRes, columnRes}` (line 88-96) and
  `RangerAccessType` string constants (line 199) — add `catalogRes`, `viewRes`,
  `functionRes`, and the StarRocks access constants including the space-containing
  ones.
- Add `dev/gen/gen_ranger_starrocks_policy_json.sh` mirroring the existing script,
  and register the new suite so `KYUUBI_UPDATE=0` guards it in CI.
- The generated policy set must include: catalog/database/table/column access
  policies, at least one row-filter policy, at least one data-mask policy, and at
  least one policy that intentionally denies a resource other users can read
  (needed to prove deny, not just allow).
- Add `resources/ranger-starrocks-security.xml` with the `ranger.plugin.starrocks.*`
  keys mirroring `ranger-spark-security.xml`, including
  `ranger.plugin.starrocks.policy.source.impl` pointing at `RangerLocalClient`.
- Make `RangerLocalClient.scala:36` profile-aware instead of hard-coding
  `sparkSql_hive_jenkins.json` — select the policy file from the configured
  service type, defaulting to the current file so existing suites are untouched.

### 7. Documentation

Update Spark AuthZ docs, primarily `docs/security/authorization/spark/install.md`,
and `docs/security/authorization/spark/overview.rst` if a mode overview fits there.

Document:

- How to enable StarRocks mode, and that `spark` remains the default.
- Required `ranger.plugin.starrocks.*` configs, and that the existing
  `authorize.in.single.call` / userstore knobs move to the `starrocks` namespace.
- Catalog mapping and default catalog fallback, with a Lakehouse example
  (`iceberg_prod=lakehouse`).
- The expected StarRocks policy hierarchy: catalog -> database -> table -> column,
  and that `catalog` is mandatory.
- That views resolve to the StarRocks `view` resource, not `table`.
- Unsupported in StarRocks mode: URI/path authorization, `ADD JAR`, and index
  operations — these fail closed.

## Test Plan

Unit tests:

- Existing Spark/Hive `AccessResourceSuite` and `SparkRangerAdminPluginSuite`
  remain unchanged and green.
- Add StarRocks resource tests for `catalog`, `database`, `table`, `column`,
  `view`, and `function` resource maps, including that `catalog` is always set and
  that `ObjectType.VIEW` produces `view` rather than `table`.
- Add StarRocks access-mapping tests asserting **both** the access string and the
  resource level for: `usage`, `select`, `insert`, `update`, `delete`, `drop`,
  `alter`, `create database`, `create table`, `create view`, `create function`.
- Add a test asserting every access string produced by the mapper exists in the
  service def's access-type list — this is the guard against silent bypass from a
  typo.
- Add catalog fallback and catalog-mapping tests, including an unmapped catalog
  falling back to `default_catalog`.
- Add fail-closed tests for URI, `ADD JAR`, index operations, and `NONE`.
- Add a test asserting user roles are populated on `AccessRequest` after the
  facade refactor (guards hazard 2a.1).
- Add a test asserting an invalid `service.type` value fails fast.

Integration-style tests (new suite alongside the existing
`RangerSparkExtensionSuite` patterns):

- `SELECT` allow/deny by catalog/database/table/column policy.
- Deny one column while allowing another column from the same table.
- Two catalogs mapping to different StarRocks catalogs: allowed in one, denied in
  the other, same database and table names — this is the test that proves
  catalog-level authorization actually works.
- `CREATE DATABASE` requires `create database` on catalog.
- `CREATE TABLE` and CTAS require `create table` on database; CTAS also checks
  `select` on input resources.
- `INSERT`, `UPDATE`, `DELETE`, `ALTER`, and `DROP` use StarRocks-native access
  strings.
- Row filters and data masks apply only when the policy catalog matches the
  normalized StarRocks catalog.
- `SHOW DATABASES`, `SHOW TABLES`, and `SHOW COLUMNS` are filtered through
  StarRocks resources.
- Existing Spark/Hive mode tests continue to pass to prove backward
  compatibility.

Recommended commands:

```bash
build/mvn test -pl :kyuubi-spark-authz_2.12 -am \
  -DwildcardSuites=org.apache.kyuubi.plugin.spark.authz.ranger.SparkRangerAdminPluginSuite

build/mvn test -pl :kyuubi-spark-authz_2.12 -am \
  -DwildcardSuites=org.apache.kyuubi.plugin.spark.authz.ranger.AccessResourceSuite

build/mvn test -pl :kyuubi-spark-authz_2.12 -am \
  -DwildcardSuites=org.apache.kyuubi.plugin.spark.authz.ranger.RangerSparkExtensionSuite

# regenerate policy golden files after touching the generators
dev/gen/gen_ranger_policy_json.sh
dev/gen/gen_ranger_starrocks_policy_json.sh   # new

# verify golden files are current, as CI does
KYUUBI_UPDATE=0 dev/gen/gen_ranger_policy_json.sh
```

Also run the relevant catalog suites if touched: Iceberg, Hudi, Paimon, and JDBC
V2. Run `dev/reformat` before committing.

The new tests must fail if the StarRocks resource/access mapping is reverted.

## Risks and Controls

- Silent policy mismatch from a typo in a resource or access name:
  - Control by copying names verbatim from the service def, asserting every
    produced access string exists in the service def's access-type list, and
    testing real allow/deny results.
- Incorrect resource level:
  - Control with a dedicated StarRocks mapper whose tests assert both resource
    level and access string for each operation.
- Silent degradation from the facade refactor (lost Ranger roles, lost cluster
  name, broken `getRangerConf`):
  - Control per §2a: explicit forwarding methods instead of reflection on the
    object, `getRangerConf` as a delegate-backed `def`, and a test asserting roles
    are populated. Do not rely on the existing swallowing `try/catch`.
- Access strings with spaces cannot be produced by the current enum plumbing:
  - Control per §4a: the mapper supplies the literal string; `AccessRequest` stops
    deriving it from the enum name, with `spark`-mode output unchanged.
- Operations with no StarRocks equivalent:
  - Control per §4c: fail closed with `AccessControlException`, one test each.
- Spark/Hive mode regression:
  - Control by leaving the Spark profile's resource keys, access strings, and
    `USE -> ANY_ACCESS` behavior untouched, and running existing suites.
- Thread-safety around lazy initialization:
  - Control with synchronized/lazy-holder initialization and exactly one shutdown
    hook for the resolved delegate.
- Spark catalog and StarRocks catalog name mismatch:
  - Control with centralized catalog normalization plus mapping and fallback tests.
- Divergence from the golden-file test convention:
  - Control by generating the StarRocks policy JSON through a generator suite and
    guarding it with `KYUUBI_UPDATE=0`, per §6.
- Runtime/resource bloat:
  - Keep the StarRocks service definition in test resources unless maintainers
    request bundling; if bundled, update `LICENSE-binary`/`NOTICE` as required.

## Maintainer Questions Before Upstream PR

- Should this live as a profile in `kyuubi-spark-authz`, or as a separate
  StarRocks-specific Spark extension?
- Are the proposed config names acceptable, and is `SparkSession.conf` (rather
  than `KyuubiConf`) the right home for them? Which release should `version()`
  reference if any config is registered?
- Should the StarRocks service definition JSON be bundled in main resources or
  test resources only?
- Should the facade/lazy-initialization refactor (§2, including the §2a hazards)
  be split into a separate PR from StarRocks support? It is a behavioral risk to
  the existing Hive mode on its own and may deserve independent review.
- Is changing `AccessRequest` to accept a literal access string (§4a) acceptable,
  or do maintainers prefer extending `AccessType` with explicit `Value("...")`
  names?
- Should Spark materialized views map to the StarRocks `materialized_view`
  resource, or be treated as unsupported in the first iteration?

## Assumptions

- StarRocks mode should use native StarRocks Ranger semantics, not a custom
  Hive-compatible service definition.
- Spark catalog names can differ from StarRocks catalog names in Lakehouse
  deployments, so explicit catalog mapping is required.
- The current Spark/Hive mode must remain the default and behaviorally unchanged.
- URI authorization, `ADD JAR`, and index operations are intentionally unsupported
  in StarRocks mode because the official StarRocks Ranger service definition
  models no `url` or index resource; they fail closed rather than degrade.
- A single Spark application runs exactly one authorization mode, so one delegate
  per JVM is sufficient.
- The StarRocks service definition fetched from `StarRocks/starrocks@main` is the
  version deployed on the target Ranger admin. Re-verify against the actual
  deployed servicedef before rollout, since access-type lists have grown across
  StarRocks releases.
