# Đánh giá `plan-codex.md` & Đề xuất kế hoạch (plan-claude)

> Đánh giá `plans/plan-codex.md` dựa trên ý tưởng trong `plans/ideal.md` và mã nguồn thực tế
> của `extensions/spark/kyuubi-spark-authz`, kèm đề xuất kế hoạch triển khai hoàn chỉnh.

---

## 1. Bối cảnh (Context)

Theo `ideal.md`: Kyuubi Spark AuthZ hiện gọi **service `hive`/`sparkSql`** trên Ranger để
phân quyền. Service Hive chỉ phân quyền tới mức **database/table/column**, **không có mức
catalog**. Hệ thống đang dùng **StarRocks** làm query engine và đã có **service `starrocks`**
trên Ranger với phân quyền **tới mức catalog**. Mong muốn: tùy biến Spark AuthZ để **đánh giá
quyền theo service `starrocks`** thay vì service Hive, nhằm **thống nhất chính sách** giữa
StarRocks và Spark trên cùng dữ liệu Lakehouse.

Quyết định đã chốt với người dùng:
- **Mục tiêu: đóng góp upstream `apache/kyuubi`** → cần giữ tính tổng quát (mode `spark`
  mặc định + mode `starrocks` tùy chọn), tương thích ngược, và **xin đồng thuận maintainer**
  cho config/feature mới (theo `AGENTS.md` mục "Ask first").
- **Dữ liệu là Lakehouse (Iceberg/Hudi/Paimon)** → tên catalog của Spark **không trùng** tên
  catalog StarRocks ⇒ **bắt buộc có cơ chế ánh xạ catalog** Spark → StarRocks.
- **Phạm vi: đầy đủ ngay** (authz + row-filter + data-masking + show-filtering + audit + docs +
  tests). Đề xuất vẫn chia pha để giảm rủi ro, nhưng tất cả nằm trong cùng phạm vi giao hàng.

---

## 2. Tóm tắt đánh giá `plan-codex.md`

**Kết luận: hướng đi đúng và bao phủ tốt, nhưng đánh giá thấp phần khó nhất** — sự khác biệt
*mô hình đặc quyền* (privilege model) giữa Hive và StarRocks, và **một số điểm thiết kế cần
đơn giản hóa / làm rõ** trước khi code.

### Điểm mạnh (giữ nguyên)
- **Xác định đúng nút thắt kiến trúc**: `SparkRangerAdminPlugin` là `object` Scala kế thừa
  `RangerBasePlugin("spark", "sparkSql")` — appId/serviceType **hard-code lúc class-load**
  (`SparkRangerAdminPlugin.scala:30`), khởi tạo eager trong constructor của
  `RangerSparkExtension` (`RangerSparkExtension.scala:44`). Muốn đổi service phải phá bỏ ràng
  buộc này.
- **Đề xuất khởi tạo lazy** thay cho eager là **chính xác và là chìa khóa** (xem §4.1) — vừa
  giải bài toán đọc config đúng thời điểm, vừa giữ một delegate đúng cho cả JVM.
- **Nhận ra `AccessResource` chưa phát ra key `catalog`** và **row-filter/data-masking/show
  chưa truyền catalog** — đúng với code thực tế (xem §4.3, §4.4).
- **Giữ mặc định `spark` + thêm `starrocks`**: tương thích ngược, phù hợp tiêu chí upstream.
- **Xử lý URI**: chặn rõ ràng trong mode StarRocks (servicedef StarRocks không có resource
  `url`) — hợp lý.
- **Test plan tốt**: yêu cầu các suite cũ vẫn xanh + thêm test khẳng định resource-map và
  access-string chính xác.

### Điểm yếu / rủi ro / thiếu sót (cần bổ sung)
1. **Đánh giá thấp phần khó nhất — ánh xạ là 2 CHIỀU, không chỉ đổi chuỗi access.** Trong
   StarRocks, đặc quyền gắn với **cấp resource cụ thể**: `create table` cấp trên **DATABASE**
   (resource = catalog+database, *không có* table), `create database` cấp trên **CATALOG**.
   Code hiện tại cho `CREATETABLE` (output) lại dựng resource **mức TABLE** + access `CREATE`
   (`AccessType.scala:42-44`, `AccessResource.scala:64-66`). Vậy mapper StarRocks phải đổi
   **CẢ access-string LẪN cấp resource** — đây là khác biệt căn bản so với thiết kế hiện tại
   (`ObjectType` quyết định resource, `AccessType` tách rời). Plan-codex có nhắc "on database
   resource / on catalog resource" nhưng chưa nêu rõ đây là thay đổi cấu trúc, không phải swap
   enum. **Đây là nơi tập trung phần lớn công sức và rủi ro.**
2. **`"one delegate per service type per JVM"` là over-engineering.** Một Spark application chỉ
   chạy **một** mode. Đề xuất: **một delegate duy nhất** chọn theo config, khởi tạo lazy idempotent
   (đơn giản hơn, ít bề mặt lỗi hơn).
3. **Rủi ro bảo mật: bypass âm thầm.** Nếu **tên resource key** hoặc **chuỗi access-type** lệch
   dù một ký tự so với `ranger-servicedef-starrocks.json`, Ranger **không match policy nào** →
   kết quả phụ thuộc default của policy engine (thường deny-all, nhưng có thể allow nếu cấu hình
   sai). Phải lấy **nguyên văn** tên resource + access từ servicedef, và **test phải khẳng định
   allow/deny thực tế** khi nạp servicedef + policy StarRocks thật, không chỉ kiểm tra hình dạng
   resource-map.
4. **Chưa nêu rõ nguồn & thời điểm đọc config `service.type`.** `object` khởi tạo lúc class-load,
   `RangerBasePlugin(appId, serviceType)` còn quyết định việc nạp `ranger-<serviceType>-security.xml`
   ⇒ có vòng lặp gà-trứng nếu đọc từ Ranger conf. Lazy-init lúc dùng đầu tiên (rule đã có
   `SparkSession`) giải quyết được — cần nói rõ (xem §4.1) và xử lý **thread-safety**.
5. **Thiếu xử lý ánh xạ catalog cho Lakehouse — chính là yêu cầu cốt lõi của người dùng.**
   Catalog Spark (iceberg/hudi/paimon) khác tên catalog StarRocks. Config
   `...starrocks.catalog.mapping` của plan-codex là đúng hướng nhưng cần đặc tả: định dạng,
   fallback `default_catalog`, và áp dụng **nhất quán** ở mọi điểm dựng resource (authz, row-filter,
   masking, show).
6. **Chưa làm rõ nguồn của servicedef lúc runtime.** Servicedef đi kèm policy tải từ Ranger admin;
   `AccessResource` đã `setServiceDef(SparkRangerAdminPlugin.getServiceDef)` (`AccessResource.scala:78`)
   nên khi delegate nạp policy StarRocks thì `getServiceDef` trả về servicedef StarRocks — **tự
   động đúng**. Bản `ranger-servicedef-starrocks.json` trong repo chỉ cần cho **test/tham chiếu**,
   không nhất thiết bundle vào main (cần quyết định, tránh phình runtime).
7. **Bỏ sót `serverOnly`/đăng ký config & quy trình docs.** Config mới phải có `version()`, chạy
   `dev/gen/gen_all_config_docs.sh` nếu thêm vào `KyuubiConf` (lưu ý: các config này là **Spark
   conf phía engine** `spark.kyuubi.authz.*`, đọc qua `spark.conf`, **không** nằm trong
   `KyuubiConf` của server — cần khẳng định đúng chỗ đăng ký).

---

## 3. Phát hiện then chốt từ mã nguồn (đã xác minh)

| Vấn đề | Bằng chứng |
|---|---|
| Singleton hard-code appId/serviceType | `SparkRangerAdminPlugin.scala:30` `object ... extends RangerBasePlugin("spark","sparkSql")` |
| Khởi tạo eager | `RangerSparkExtension.scala:44` gọi `SparkRangerAdminPlugin.initialize()` trong thân class |
| Config prefix bám serviceType | `SparkRangerAdminPlugin.scala:42,60` `s"ranger.plugin.${getServiceType}..."` |
| Nhiều call-site bám thẳng singleton | `RuleAuthorization`, `RuleFunctionAuthorization`, `AccessResource.scala:78`, `RuleApplyRowFilter.scala:47`, `RuleApplyDataMaskingStage0.scala:66`, `FilteredShowObjectsExec`, `RuleReplaceShowObjectCommands` |
| `AccessResource` không có key `catalog` | `AccessResource.scala:55-77` chỉ set `database/table/column/udf/url`; `catalog` chỉ lưu thuộc tính (dòng 31) |
| `AccessType` kiểu Hive, tách rời cấp resource | `AccessType.scala:24-85` (enum `SELECT/CREATE/DROP/ALTER/USE/UPDATE...`) |
| Row-filter không truyền catalog | `RuleApplyRowFilter.scala:45` `AccessResource(TABLE, db, table, null)` |
| Data-mask không truyền catalog | `RuleApplyDataMaskingStage0.scala:64` `AccessResource(COLUMN, db, table, col)` |
| SHOW filter không truyền catalog | `RuleReplaceShowObjectCommands.scala:52,57,92,113`, `FilteredShowObjectsExec` |
| Catalog ĐÃ được trích xuất & lưu sẵn | `Table.scala`/`Database.scala`/`PrivilegeObject.scala` có `catalog: Option[String]`; `tableExtractors.scala`/`catalogExtractors.scala` đã điền |
| Đường authz chính ĐÃ truyền catalog | `AccessResource.scala:90-100` `apply(obj, opType)` truyền `obj.catalog` |
| Hạ tầng test | `RangerLocalClient.scala` nạp `sparkSql_hive_jenkins.json`; `ranger-spark-security.xml` khai báo `ranger.plugin.spark.*`; suite gốc: `SparkRangerAdminPluginSuite`, `AccessResourceSuite`, `RangerSparkExtensionSuite` |
| Ranger version | `pom.xml`: `ranger.version=2.6.0`, deps `ranger-plugins-common/-audit/-cred`, `ranger-plugin-classloader` |

**Tin tốt:** catalog **đã có sẵn** trong `Table`/`Database`/`PrivilegeObject` và đường authz
chính đã truyền nó. Khối lượng còn lại tập trung vào: (a) làm `catalog` thành **resource key**,
(b) **mapper hai chiều** cho StarRocks, (c) **truyền catalog** vào row-filter/masking/show,
(d) **chọn delegate theo config**.

---

## 4. Đề xuất kế hoạch triển khai

Phạm vi: chỉ trong `extensions/spark/kyuubi-spark-authz` + docs + test resources. Giữ mode
`spark` làm mặc định; thêm mode `starrocks`. Tất cả các pha dưới đây nằm trong cùng phạm vi giao
hàng (người dùng chọn "đầy đủ ngay"); chia pha chỉ để kiểm soát rủi ro và review.

### Pha 0 — Pre-flight (theo `AGENTS.md`)
- `git remote -v` xác nhận remote `apache` → `apache/kyuubi`; `git fetch apache master` nếu cũ.
- Cây làm việc đang dirty (`.gitignore`, `plans/`): **không** đổi nhánh/RAT-check trước khi
  stash; nhánh mới từ `apache/master` (vd `kyuubi-NNNN-starrocks-authz`).
- **Tạo issue + thảo luận maintainer** trước khi mở PR (feature + config mới = "Ask first").

### Pha 1 — Trừu tượng hóa & chọn delegate theo config (nền tảng)
**File:** `SparkRangerAdminPlugin.scala`, `RangerSparkExtension.scala`, + 1 file profile mới.

- **Bỏ khởi tạo eager** ở `RangerSparkExtension.scala:44`. Khởi tạo **lazy, idempotent,
  thread-safe** (vd `lazy val` hoặc khối `synchronized`) ở lần dùng đầu tiên — lúc này rule đã có
  `SparkSession` để đọc Spark conf. Giải quyết bài toán thời điểm-đọc-config.
- Đọc **một** config `spark.kyuubi.authz.ranger.service.type ∈ {spark, starrocks}` (mặc định
  `spark`). **Một delegate duy nhất cho cả JVM** (không cần map theo type).
- Biến `SparkRangerAdminPlugin` thành **facade**: giữ nguyên chữ ký công khai mà các call-site
  đang dùng (`isAccessAllowed`, `evalRowFilterPolicies`, `evalDataMaskPolicies`, `getServiceDef`,
  `getRangerConf`, `getFilterExpr`, `getMaskingExpr`, `verify`, `authorizeInSingleCall`,
  `useUserGroupsFromUserStoreEnabled`), ủy quyền xuống delegate `RangerBasePlugin` được khởi tạo
  với `(appId, serviceType)` đúng (`("starrocks","starrocks")` cho mode StarRocks). Như vậy
  **không phải sửa hàng loạt call-site**.
- Giữ nguyên cơ chế shutdown hook (`SparkRangerAdminPlugin.scala:75-85`), đăng ký **một lần** cho
  delegate đã chọn.

### Pha 2 — Profile-aware resource + access mapping (phần khó nhất)
**File mới:** một `AuthzProfile`/`RangerServiceProfile` (sealed trait `SparkProfile` /
`StarRocksProfile`). **Sửa:** `AccessResource.scala`, `AccessType.scala` (hoặc tách mapper riêng
cho StarRocks).

- **Resource key (`AccessResource.apply`)**: tách phần "set key theo ObjectType" ra theo profile.
  - Mode `spark`: giữ nguyên `database/table/column/udf/url` (KHÔNG đổi → bảo đảm tương thích).
  - Mode `starrocks`: phát `catalog` (top-level, bắt buộc), `database`, `table`, `column`,
    `view`, `function`. **Lấy nguyên văn tên resource từ `ranger-servicedef-starrocks.json`.**
- **Access mapping (2 CHIỀU) cho StarRocks** — mỗi operation trả về **cặp (cấp resource, chuỗi
  access)**, không chỉ chuỗi access:
  - `QUERY/DESCTABLE/SHOWCOLUMNS/table-scan` → resource **table/column**, access `select`.
  - `CREATEDATABASE` → resource **catalog**, access `create database`.
  - `CREATETABLE/CTAS` (output) → resource **database**, access `create table`; CTAS input vẫn
    `select` trên bảng nguồn (đường input→SELECT đã có sẵn ở `AccessType.scala:42-44`).
  - `CREATEVIEW` → resource **database**, access `create view`.
  - `CREATEFUNCTION` → resource **database**, access `create function`.
  - `LOAD/INSERT` → resource **table**, access `insert`.
  - `UPDATE/TRUNCATETABLE` → resource **table**, access `update`.
  - `DELETE` → resource **table**, access `delete`.
  - `DROP*` → access `drop` ở cấp tương ứng.
  - `ALTER*` → access `alter` ở cấp tương ứng.
  - `SHOWDATABASES`/kiểm tra hiển thị catalog → resource **catalog**, access `usage`.
  - UDF execution → resource **function**, access `usage`.
  - URI (DFS/local) → **ném lỗi unsupported rõ ràng** trong mode StarRocks.
  - **Mọi chuỗi access & quy tắc gắn-cấp lấy nguyên văn từ servicedef StarRocks.**
- **Ánh xạ catalog Lakehouse → StarRocks** (yêu cầu cốt lõi):
  - `spark.kyuubi.authz.ranger.starrocks.default.catalog` (mặc định `default_catalog`) — fallback
    khi không trích được catalog.
  - `spark.kyuubi.authz.ranger.starrocks.catalog.mapping` — danh sách `sparkCatalog=srCatalog`
    (vd `iceberg_prod=lakehouse`, `spark_catalog=default_catalog`). Áp dụng tại **một điểm**
    (hàm chuẩn hóa catalog) dùng chung cho mọi nơi dựng resource.

### Pha 3 — Truyền catalog vào row-filter / data-masking / show-filtering
**File:** `RuleApplyRowFilter.scala:45`, `RuleApplyDataMaskingStage0.scala:64`,
`RuleReplaceShowObjectCommands.scala`, `FilteredShowObjectsExec.scala`.

- Truyền `catalog = table.catalog` (đã chuẩn hóa qua mapping ở Pha 2) vào các lời gọi
  `AccessResource(...)` hiện đang bỏ qua catalog.
- Ở mode `spark`: catalog tiếp tục bị bỏ qua khi tạo resource key (servicedef Hive không có
  `catalog`) → hành vi cũ **không đổi**.

### Pha 4 — Test resources cho StarRocks
**File mới dưới `src/test/resources/`:** `ranger-starrocks-security.xml`,
`starrocks_<service>.json` (policy + servicedef dựa trên `ranger-servicedef-starrocks.json`
chính thức), và bản tham chiếu `ranger-servicedef-starrocks.json`.
- Cho `RangerLocalClient` nhận biết profile để nạp đúng file policy theo service type (thêm biến
  thể/đối tượng cấu hình thay vì hard-code `sparkSql_hive_jenkins.json`).

### Pha 5 — Tài liệu
**File:** `docs/security/authorization/spark/install.md` (+ `overview.rst` nếu cần).
- Hướng dẫn bật mode StarRocks: `spark.kyuubi.authz.ranger.service.type=starrocks`,
  `ranger.plugin.starrocks.service.name`, `...policy.rest.url`, `...policy.cache.dir`.
- Giải thích `catalog.mapping`, fallback `default_catalog`, và **URI không được hỗ trợ** ở mode
  StarRocks.

---

## 5. Rủi ro chính & cách kiểm soát

| Rủi ro | Kiểm soát |
|---|---|
| **Bypass âm thầm** do lệch tên resource/access | Lấy nguyên văn từ servicedef; test khẳng định **allow/deny thực** với servicedef+policy StarRocks thật |
| Mapper 2 chiều phức tạp (cấp resource đổi theo op) | Tách `StarRocksAccessMapper` riêng, bảng ánh xạ tường minh + unit test cho từng op |
| Hồi quy mode `spark` | Không đổi nhánh `spark` trong `AccessResource`/`AccessType`; chạy lại toàn bộ suite cũ (Iceberg/Hudi/Paimon/JDBC V2) |
| Lazy-init đa luồng | `lazy val`/`synchronized`, đăng ký shutdown hook đúng một lần |
| Lệch tên catalog Lakehouse ↔ StarRocks | `catalog.mapping` + fallback, áp dụng tại một điểm chuẩn hóa duy nhất |
| Xung đột classpath khi nạp 2 servicedef | Chỉ nạp delegate của service type đã chọn; servicedef StarRocks chỉ ở test trừ khi maintainer yêu cầu bundle |
| Phạm vi PR | Tách refactor facade (Pha 1) thành **PR riêng** nếu maintainer muốn — "One concern per PR" |

---

## 6. Kế hoạch kiểm thử (mở rộng từ plan-codex)

- **Unit**: `AccessResourceSuite` cũ vẫn nguyên; thêm test StarRocks khẳng định **map resource
  chính xác** (catalog/database/table/column/view/function) và **chuỗi access chính xác**
  (`create database`, `create table`, `insert`, `select`, `usage`, `drop`, `alter`, `update`,
  `delete`) **kèm đúng cấp resource**. Test fallback + `catalog.mapping`.
- **Tích hợp** (suite mới kế thừa `RangerSparkExtensionSuite`, theo mẫu các suite catalog hiện
  có): SELECT theo policy catalog/db/table/column; deny một cột cho phép cột khác; CREATE
  DATABASE cần `create database` cấp catalog; CREATE TABLE/CTAS cần `create table` cấp database
  (CTAS thêm `select` nguồn); INSERT/UPDATE/DELETE/ALTER/DROP dùng access StarRocks; row-filter &
  masking chỉ áp khi policy khớp catalog; SHOW DATABASES/TABLES/COLUMNS lọc qua resource
  StarRocks; **mode `spark` giữ nguyên** để chứng minh tương thích ngược.
- **Lệnh** (theo `AGENTS.md`):
  ```
  build/mvn test -pl :kyuubi-spark-authz_2.12 -am -DwildcardSuites=org.apache.kyuubi.plugin.spark.authz.ranger.SparkRangerAdminPluginSuite
  build/mvn test -pl :kyuubi-spark-authz_2.12 -am -DwildcardSuites=org.apache.kyuubi.plugin.spark.authz.ranger.AccessResourceSuite
  build/mvn test -pl :kyuubi-spark-authz_2.12 -am -DwildcardSuites=org.apache.kyuubi.plugin.spark.authz.ranger.RangerSparkExtensionSuite
  dev/reformat   # trước khi commit
  ```
- **Bắt buộc**: revert thay đổi → test mới phải **fail** (test có ý nghĩa, theo `AGENTS.md`).

---

## 7. Cần đồng thuận maintainer trước khi mở PR (vì hướng upstream)

1. Có chấp nhận thêm **mode authz đa-service (profile)** vào `kyuubi-spark-authz` không, hay
   maintainer muốn module/extension riêng?
2. Tên & namespace config: `spark.kyuubi.authz.ranger.service.type`,
   `...starrocks.default.catalog`, `...starrocks.catalog.mapping` — và **`version()`** đặt ở
   release nào; đăng ký ở đâu (Spark engine conf, không phải `KyuubiConf` server).
3. Có **bundle `ranger-servicedef-starrocks.json`** vào main resources không (kèm cập nhật
   `LICENSE-binary`/`NOTICE` nếu là tài sản bên thứ ba) hay chỉ để ở test.
4. Tách **PR refactor facade** (Pha 1) khỏi **PR tính năng StarRocks** (Pha 2–5)?

---

## 8. Kết luận

`plan-codex.md` **đúng hướng và bao phủ rộng**; nên áp dụng phần lớn. Khác biệt chính trong đề
xuất này: (1) **làm nổi bật ánh xạ 2 chiều** (cấp resource + chuỗi access) như rủi ro/công sức
trọng tâm; (2) **đơn giản hóa** thành một delegate/JVM; (3) **đặc tả ánh xạ catalog Lakehouse→
StarRocks** vì đó là yêu cầu cốt lõi; (4) nhấn mạnh **test allow/deny thực** để chặn bypass âm
thầm; (5) bổ sung **quy trình đồng thuận maintainer** do hướng upstream.
