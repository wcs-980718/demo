# Task 4：真实 PostgreSQL 验证与跟踪更新 — 执行报告

## 完成范围

- 新增 `EvaluationDatasetLocalPostgresTest`，复用 P1 的本地 PostgreSQL 隔离 schema 模式，并以 `midplat_eval_dataset_it_` 随机前缀隔离本任务数据。
- 补充 `EvaluationDatasetApiTest`：HTTP 派生和冻结的过期 `expectedVersion` 返回 409；JSON 的缺失/null 字段返回 400；400/409 响应不回显路径外部 ID 或请求正文。
- 修复全局问题模型的安全实例路径：参数校验、参数错误、冲突、乐观锁和悲观锁固定为 `/api`，避免 Spring 默认 `instance` 回显含资源 ID 的请求 URL；404 继续使用领域声明的安全路径。
- 更新 P2 计划及主建设跟踪文档第 16、17、18、20 节，记录 P2 接口、迁移、证据、风险与 P3 衔接。

## RED / GREEN 记录

### RED

1. 新增 HTTP 边界断言后运行：

   ```bash
   mvn -Dtest=EvaluationDatasetApiTest test
   ```

   首次为 `BUILD FAILURE`，5 项中 2 项失败。`ProblemDetail.instance` 回显了数据集/版本 URL 中的外部 ID；冻结过期版本的 409 同样回显版本 ID。这是安全错误模型的真实缺口，不是测试装配错误。

2. 新增真实 PostgreSQL 测试后，首次迁移计数把 Flyway schema-history 初始化记录计入，得到 23 而非 22。测试已收紧为仅计 SQL 迁移记录；这是验收测试的计数前置修正，不是生产迁移失败。

3. 首次直接用 `JdbcTemplate` 建置唯一键冲突数据未提交；项目 Hikari 配置关闭自动提交，连接归还时回滚。测试改为显式 Spring 事务提交建置行后，真实数据库约束验证通过；生产唯一约束未发现缺陷。

### GREEN

- `GlobalExceptionHandler` 使用固定安全实例 `/api` 后，`EvaluationDatasetApiTest` 5 项通过。
- PostgreSQL 16.14、Hikari 显式 `TRANSACTION_READ_COMMITTED` 下，`EvaluationDatasetLocalPostgresTest` 5 项通过，并在真实 Spring 连接断言 `current_setting('transaction_isolation') = 'read committed'`。

## 真实 PostgreSQL 证据

- 数据库版本：PostgreSQL 16.14；事务隔离由 Hikari 显式设为 `TRANSACTION_READ_COMMITTED`，并在真实 Spring 连接查询验证为 `read committed`。
- 随机 schema 从空库执行 V1–V22，共 22 条 SQL 迁移成功。任务简报的 V1–V21 要求已覆盖；V22 是 P2 已有的版本推进迁移。
- 实际数据库拒绝 `(dataset_id, version_no)` 重复、同数据集第二个 `DRAFT`、`(version_id, case_id)` 重复和 `(version_id, order_no)` 重复。
- 两个相同 `expectedVersion` 的并发派生结果为 1 成功、1 统一 `ConflictException`；最终只有 1 个 V2 草稿，版本号列表为 `[1, 2]`，不存在复用。
- 两个不同数据集以相反顺序冻结同一对案例均完成；案例锁在读取端按 ID 稳定排序，无死锁。显式 `lock_timeout` 触发 PostgreSQL `55P03` 后转换为 `PessimisticLockingFailureException`；测试遍历异常 cause 链并断言该 SQLSTATE，既有全局异常模型将此类锁失败映射为 HTTP 409，而非 500。
- 冻结条目包含名称、分类、严重级别、输入、期望、评分器和内容哈希；已审核源案例修改被拒绝、归档后条目快照及 `snapshot_hash` 不变。
- 测试在运行前与 `@AfterAll` 删除后均只断言本次随机 schema 数为 0，不会把并行专项创建的 schema 判作残留；报告、源码与命令输出未写入数据库密码。

## 验证结果

| 命令 | 结果 |
| --- | --- |
| `mvn -Dmidplat.postgres.url=jdbc:postgresql://localhost:5432/postgres -Dtest=EvaluationDatasetLocalPostgresTest test` | 5 项通过，0 失败，0 错误 |
| `mvn -Dtest=EvaluationDatasetApiTest,EvaluationCaseApiTest test` | 25 项通过，0 失败，0 错误 |
| `mvn test` | 发现 104 项，其中 89 项执行通过、15 项条件式跳过、0 失败、0 错误 |
| `mvn -DskipTests package` | 成功生成可执行 JAR |
| `git diff --check` | 通过，无空白错误 |

## 提交

- 代码、测试、P2 计划与主跟踪文档提交：`6789ed3 修复：补齐评测集版本 PostgreSQL 验收边界`。
- 本报告在后续独立文档提交中固化，提交哈希见执行者最终回报。

## 遗留风险与下一阶段

- Flyway 9.22.3 对 PostgreSQL 16 仍提示未声明支持；当前 V1–V22 已在 PostgreSQL 16.14 实测，升级 Flyway 后必须重跑本专项。
- 默认全量测试保留 15 项条件式跳过（本地 PostgreSQL/Testcontainers 环境未显式开启）；CI 应强制运行本地或受控 PostgreSQL 专项，不能仅依赖 H2。
- P3 继续实现评测运行、确定性评分器、异步恢复和错误处理；不得绕过已冻结的数据集版本快照或 `expectedVersion`/审计边界。

## 审查修复

### RED / GREEN

- RED：在真实 PostgreSQL 测试中先新增 Hikari 隔离级别断言，首次运行得到 `transactionIsolation = null`，证明仅依赖数据库默认值不能满足验收；同时新增锁超时异常 cause 链 SQLSTATE 断言和本次随机 schema 的精确残留断言。
- GREEN：动态测试配置显式设置 `spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED`，真实 Spring `JdbcTemplate` 连接查询 `current_setting('transaction_isolation')` 为 `read committed`；5 项 PostgreSQL 专项全部通过。锁超时异常 cause 链含 `SQLException`/PostgreSQL 驱动异常且 SQLSTATE 为 `55P03`，仍统一映射为 HTTP 409。
- 清理范围改为仅本次 UUID 随机 schema；`@AfterAll` 无论测试成功或失败均执行删除并断言该 schema 残留数为 0，不会将其他并行专项的 schema 误判为残留。

### 迁移编号重排

| 阶段 | 原计划 | 修复后 | 说明 |
| --- | --- | --- | --- |
| P2 | V21、V22 | 保持 V21、V22 | 已落地历史迁移，不改写 |
| P3 | V22 | V23 | 避免与 P2 V22 冲突 |
| P5 发布门禁 | V23 | V24 | 紧随 P3 |
| P5 菜单 | V24 | V25 | 紧随发布门禁 |

### 最终验证与提交

- PostgreSQL 16.14：`EvaluationDatasetLocalPostgresTest` 5 项通过、0 失败、0 错误；空随机 schema 成功执行 V1–V22 共 22 条 SQL 迁移，隔离级别为显式配置并实测断言的 `read committed`，结束后本次 schema 残留数为 0。
- `EvaluationDatasetApiTest,EvaluationCaseApiTest`：25 项通过、0 失败、0 错误。
- `mvn test`：发现 104 项，其中 89 项执行通过、15 项条件式跳过、0 失败、0 错误。
- `mvn -DskipTests package` 与 `git diff --check`：均通过。
- 原建设提交：`6789ed3`；原报告提交：`d15db27`；本轮审查修复提交：`65a1f32 修复：收紧评测数据集 PostgreSQL 验收`。本报告补充内容在后续文档提交中固化。

### 遗留风险

- Flyway 9.22.3 仍对 PostgreSQL 16 提示版本警告；升级依赖后须重新执行至少 V1–V22 的真实 PostgreSQL 迁移回归。
- 默认全量测试的 15 项仍是环境条件式跳过；CI 必须保留受控 PostgreSQL 专项，避免只以 H2 结果作为数据库并发与约束证据。
