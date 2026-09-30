# P5 Task 1 实验对比报告

## 当前交付

- `GET /api/evaluation/experiments/compare` 只比较 `COMPLETED`、`PARTIAL`、`FAILED`、`CANCELLED` 终态运行。运行冻结哈希只由安全结果投影按 `orderNo` 明确排序的 `caseId + snapshotContentHash` 经 `DatasetSnapshotHasher` 重建；不读取当前 `dataset_version.snapshot_hash`，也不读取 `target_snapshot_json`。
- 结果严格按 `caseId` 一一对齐，并校验内容哈希、重复 ID 和数据集快照；哈希或对齐冲突返回安全 HTTP 409。质量差、成本差使用候选减基线；质量口径为 `passed/(passed+failed)*100`、四位 `HALF_UP`，零可判定样本为 `qualityAvailable=false` 和显式 `qualityDelta:null`。
- 分类优先级为 `REGRESSED/ERROR/REVIEW_REQUIRED/IMPROVED/UNCHANGED`，再按名称、caseId 稳定排序；ERROR/CANCELLED 优先于待审，已人工复核 PASSED/FAILED 正常参与比较，状态变化优先于分数变化。
- API 响应只含固定安全摘要“输出已脱敏”，不加载或暴露输入、期望、完整输出、轨迹、目标/参数/模型快照或凭据。

## RED / GREEN

- RED：`ExperimentComparisonSqlAssertionsTest` 新增 JOIN、CTE、逗号多表、SELECT/EXISTS 子查询、UNION、表达式、列别名和 `alias.*` 逃逸样例；复合列此前可借 `lastIndexOf('.')` 的末尾允许字段穿透。GREEN：解析器限定整句仅一个 `SELECT` 和 `FROM`，`FROM` source 必须完整匹配一张可选 schema 限定的 run/result 表及一个可选别名；每个逗号投影项必须整段匹配可选别名加单一物理列。
- RED：Inspector 仅捕获首字符是 `select` 的 SQL，因而遗漏前导空白、Hibernate 注释、行注释和 CTE statement。GREEN：目标 worker 的每一条 Hibernate statement 都先捕获；断言层仅对安全前导空白/注释 SELECT 规范化，CTE、非 SELECT 或第五条 statement 均失败。
- RED：安全 reader 投影按反向返回顺序计算哈希产生与 `DatasetSnapshotHasher` 不同的值。GREEN：在 reader 内按 `orderNo` 排序后再生成安全结果与冻结哈希，拒绝依赖仓储返回顺序。

## 当前验证证据

- 扩大定向：`mvn -Dtest=EvaluationExperimentServiceTest,EvaluationExperimentApiTest,EvaluationRunComparisonReaderTest,ExperimentComparisonSqlAssertionsTest,ExperimentComparisonBarrierInspectorTest test`，35/35。
- P3 + Task 1：`mvn -Dtest=EvaluationExperimentServiceTest,EvaluationRunComparisonReaderTest,ExperimentComparisonSqlAssertionsTest,ExperimentComparisonBarrierInspectorTest,EvaluationExperimentApiTest,EvaluationRunStateTest,EvaluationRunServiceTest,EvaluationRunApiTest,EvaluationRunRepositoryTest,EvaluationRunRecoveryTest test`，123/123。
- 真实 PostgreSQL：`mvn -Dmidplat.postgres.url=jdbc:postgresql://localhost:5432/postgres -Dtest=EvaluationExperimentLocalPostgresTest test`，PostgreSQL 16.14、Flyway V1–V23、2/2。随机 UUID schema 在运行前后均为 0，finally 只清理本 schema。
- fresh 全量：`mvn clean test`，297 found = 263 executed + 34 conditional skipped，0 failure/error。
- 构建：`mvn -DskipTests package` 成功；`git diff --check` 和 scoped review-package range `git diff --check 0df54f7..HEAD` 通过。

## PostgreSQL 安全与并发证据

- test-only `StatementInspector` 捕获 `compare-worker` 的全部 Hibernate statement，而非先按首字符或表名过滤；双运行比较精确为 4 条，任何 `dataset_version`、第二数据源、CTE、非 SELECT 或第五条 statement 立即失败。安全前导空白/块注释/行注释 SELECT 会规范化后逐条经完整 source clause 和单项精确物理列 allowlist 验证。MockMvc→Controller→Service→JPA 路径和比较 worker 都使用同一断言；四类大正文 sentinel 不进入捕获 SQL 或 JSON。
- Inspector 的 latch 固定在 worker 的 run/result 两段读取间，另一线程提交人工 review。服务的只读 `REPEATABLE_READ` 响应保持全旧 `qualityAvailable=false` 与 `REVIEW_REQUIRED`，不会形成旧统计加新案例的混合。
- compare 正常返回和已执行四条安全投影 SELECT 后的快照冲突异常，均在同一 `compare-worker` 线程立刻重新从 Hikari `DataSource` 借连接，断言 `READ_COMMITTED` 并读取有效 `pg_backend_pid()`；`@AfterEach` 无条件 reset Inspector 静态状态。

## 风险约束

Task 3 必须经 Spring 代理并从无外层事务上下文调用 `compare`，以保证 `REPEATABLE_READ` 生效；未引入 `REQUIRES_NEW` 连接嵌套。P5 整体仍为进行中，Task 1 已完成。
