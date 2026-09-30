# Task 5 报告：评测运行启动恢复与 PostgreSQL 验收

## 结论

Task 5 于 2026-08-30 完成整改、最终复审修复和重新验收。恢复生命周期、租约防误杀、审计回滚、人工复核当前态、真实 PostgreSQL 约束矩阵、大字段往返、乐观并发、谱系和组合统计均有可重复的自动化证据。

## RED / GREEN

- RED：新增 MANUAL 运行聚合断言后，`mvn -DskipTests -Dtest=EvaluationRunLocalPostgresTest test-compile` 因 `EvaluationRun.getManualReviewCount()` 和 `RunSummary.manualReviewCount()` 不存在而在编译期失败。
- GREEN：V23 和运行聚合新增 `manual_review_count`，MANUAL 执行完成时递增，人工复核后递减并计入 passed/failed；摘要 API 同步暴露该计数。随后 PostgreSQL 专测 17/17 通过。
- RED：恢复生命周期测试首次观察到启动扫描调用 2 次；原因是测试容器中真实 `@Scheduled` 与 ApplicationReady 同时触发，并非生产恢复重复。
- GREEN：生命周期测试以可控 `TaskScheduler` 隔离周期竞态，分别证明默认启用、显式禁用和 ApplicationReady 触发；恢复专测 10/10 通过。
- 最终复审 RED：H2 服务/API 定向 2 项均失败，证明人工复核后的结果仍返回 `reviewRequired=true`；真实 PostgreSQL 定向 2 项均失败，分别证明旧 V23 接受 `PENDING + review_required=true + reviewed_at non-null` 的非法组合，以及生产复核路径仍保留 `review_required=true`。随后领域定向 1 项先红，证明实体曾允许空 `reviewSummary` 并把约束失败推迟到数据库。
- 最终复审 GREEN：`reviewRequired` 统一为“当前仍待人工复核”。待审结果严格为 `PENDING + reviewRequired=true + reviewedAt/reviewSummary=null`；复核成功后严格为 `PASSED/FAILED + reviewRequired=false + reviewedAt/reviewSummary non-null`，同时保留复核摘要和审计历史。H2 状态/服务/API 57/57、PostgreSQL 专测 17/17 均通过。

## 恢复与租约契约

- `midplat.evaluation.recovery.enabled` 默认为 `true`，ApplicationReady 后启动，同时保留周期扫描；显式 `false` 时不扫描。
- QUEUED 运行只提交既有受限 dispatcher。用真实饱和执行器证明提交被拒绝时 run 仍为 QUEUED，统计不变且目标端口未被调用。
- worker claim 写入 owner/heartbeat/lease，每题边界续租，终态清理租约。恢复仅处理锁内复核后仍已过期的 RUNNING，不会误杀其他实例的活跃租约。
- 遗留 RUNNING 结果转 ERROR，普通 PENDING 转 CANCELLED，自动终态和 MANUAL 待审 PENDING 保留；run 以固定 `RUN_RECOVERY_FAILED` 失败收口。
- 审计与 run/results/统计同事务；注入审计失败后，run、results、全部 count、`@Version` 和 audit 数均回滚，且不阻断后续 run 恢复。
- 重复恢复后所有自动结果均为终态，run/result version 和 audit 数不再变化。

## PostgreSQL 16.14 真实验收

- 命令：`mvn -Dmidplat.postgres.url=jdbc:postgresql://localhost:5432/postgres -Dtest=EvaluationRunLocalPostgresTest test`。
- 方言与隔离：PostgreSQL 16.14；Hikari 显式 `TRANSACTION_READ_COMMITTED`，真实连接断言 `read committed`。
- 迁移与清理：空随机 UUID schema 执行 V1–V23 共 23 条迁移。每次运行仅在 finally 删除本次 schema，运行前后 schema count 都断言为 0。
- 约束矩阵：四个 run 父级 FK、result→run FK、`source_run_id` 自引用与删除保护、必要索引、两个 result 唯一约束、run/result 状态、每个非负数值列、`completed_at` 双向一致、count 关系、review/retry/lease CHECK、模型单价与 run cost 数值边界及实体映射均独立发生并以事务回滚隔离。review CHECK 还独立拒绝待审结果提前写入复核历史、以及已复核结果继续标记待审；测试不依赖数据库自动约束名。
- 大字段：`snapshot_input_text`、合法 JSON 的 `snapshot_expected_json`、`actual_output`、`trace_summary_json` 分别完成 >=1 MiB 的真实往返，同时断言字节长度、字符长度与 SHA-256，不输出正文日志。
- 并发：cancel、retry、manual-review 都使用同一 `expectedVersion` 竞争并且恰好 1 个成功，失败方被收紧为稳定的 `ConflictException`，不再接受任意运行时异常。人工复核断言 result/run 的 `@Version` 各只递增 1、`manualReviewCount` 从 1 降为 0、API 返回 `reviewRequired=false` 并保留 `reviewedAt/reviewSummary`；retry 固定数据确定收口为 PARTIAL，源/新 run 与复制 result 的版本、终态和全部案例快照均精确断言。retry 测试等待异步新 run 收口后才清理。
- 真实组合：4 个 result 分别得到 PASSED/FAILED/ERROR/MANUAL，每项 `attempt_count=1`、token 正数、cost 正数。聚合值为 total/completed=4，passed/failed/error/manual=1/1/1/1，prompt/completion/total token=10/8/18，cost=`26.00000000`，avg/P95=25/40，`completedAt` 非空，MANUAL 结果保持待审。

## 最终验证证据

| 验证 | 结果 |
| --- | --- |
| `EvaluationRunRecoveryTest` | 10 tests，0 failures / 0 errors / 0 skipped |
| `EvaluationRunLocalPostgresTest` | 17 tests，0 failures / 0 errors / 0 skipped |
| `EvaluationRunApiTest,EvaluationRunServiceTest,EvaluationRunRecoveryTest` | 58 tests，0 failures / 0 errors / 0 skipped |
| `mvn test` | 259 found = 227 executed + 32 conditional skipped，0 failures / 0 errors |
| `mvn -DskipTests package` | BUILD SUCCESS |
| `git diff --check` 与基线 review-package diff-check | 无空白错误 |

## 保留风险

- Flyway 9.22.3 仅声明支持至 PostgreSQL 15，在 PostgreSQL 16.14 会输出升级警告；本次 V1–V23 实测通过，后续升级 Flyway 时必须重跑全部真实 PostgreSQL 专测。
- 普通 `mvn test` 按设计跳过需外部 PostgreSQL 的条件套件；CI 仍需保留强制的真实 PostgreSQL 任务。
- 本次只因 P3/V23 尚未发布而原位收紧 V23 CHECK。若任何环境已经执行旧 V23，必须新增后续迁移修正约束，绝不能修改已执行迁移的内容或 checksum。
