# P5 Task 2 门禁策略与不可变决策报告

## 当前交付

- V24 新增 `midplat_eval_gate_policy` 和 `midplat_eval_gate_decision`。策略支持可选 `platform_id` FK 与 `category` scope，`min_pass_rate numeric(5,2)` 为 0–100，`max_cost_growth_percent numeric(20,8)` 固定为 0–`999999999999.99999999`（含 PostgreSQL numeric NaN 拒绝），平均/P95 延迟非负；名称/分类和 JSON 同时拒绝空格、tab、LF、CR、FF 空白，`@Version` 与数据库 version CHECK 同构。
- 决策引用运行和策略的 FK，保存 `policy_version`、`PASS/FAIL/REVIEW_REQUIRED`、策略快照、证据与 `decided_at`；run/policy 时间序索引服务历史查询。决策不含 `updated_at` 或 setter，标注 Hibernate `@Immutable`；生产仓储只公开 `append`、查询和计数，`append` 使用 `EntityManager.persist + flush`，不公开 save/saveAll/update/delete。
- 决策只接受结构化 `EvaluationGatePolicy` 与 `EvidenceSnapshot`，由实体内部 codec 生成快照和证据；任何包含策略/证据原始 JSON String 的实体构造均为 private，JPA 仅有 protected 无参构造。codec 先限制原始长度、规范化为新 record，再限制最终 UTF-8 字节（策略 8 KiB、证据 64 KiB）；拒绝未知规则/schema、空白、超项和 Bearer/JWT/私钥/AKIA/sk-mid/常见 key-token-password-secret 形态。实体 `toString()` 不输出 JSON。

## RED / GREEN

- RED：新增 `EvaluationGateRepositoryTest` 后，由于 V24、策略/决策实体、仓储和 codec 都不存在，`mvn -Dtest=EvaluationGateRepositoryTest test` 在 testCompile 阶段以 gate 类型缺失失败。
- GREEN：复审整改后，H2 门禁仓储/codec 契约 12/12 通过。实体和数据库分别拒绝越界百分比、负阈值、空白名称/分类/JSON、错误结论及缺失父行；原子 `revise(draft, expectedVersion)` 的所有非法 draft 保持字段和 version 不变。反射证明决策仓储无 save/saveAll/update/delete 且公开 append 只接收结构化快照；同持久上下文、已持久化和 PG 两事务的重复 ID 均确定失败，历史行不变。

## 验证证据

- H2 定向：`mvn -Dtest=GateDecisionPayloadCodecTest,EvaluationGateRepositoryTest test`，12/12，0 failure/error。
- PostgreSQL：`mvn -Dmidplat.postgres.url=jdbc:postgresql://localhost:5432/postgres -Dtest=EvaluationGateLocalPostgresTest test`，PostgreSQL 16.14、Flyway V1–V24、READ_COMMITTED，5/5。随机 UUID schema 前后计数均为 0；逐项验证 FK、结论、数值/NaN/上界、控制空白 JSON、version、索引和 numeric 实体 roundtrip；ready/release 并发断言策略恰一成功/一 JPA-Hibernate 乐观锁失败、决策重复 ID 均为 `DataIntegrityViolationException`。
- P1–P3 + Task 1 关键回归及本任务：26 个测试类，221/221，0 failure/error。
- fresh 全量：`mvn clean test`，314 found = 275 executed + 39 conditional skipped，0 failure/error；`mvn -DskipTests package` 成功，`git diff --check` 通过。

## 范围与风险

- 未修改 V23，且 gate 包未取得运行写仓储或写能力；Task 3 才实现门禁算法、策略 API、审计和决策生成编排。
- PostgreSQL 专项会记录预期的乐观锁和主键冲突为 Hibernate 日志，测试不把任意 Throwable 作为成功条件。Flyway 9.22.3 对 PostgreSQL 16.14 的版本提示仍为现有基础设施风险，迁移与约束已在该方言实际通过。P5 总体与发布门禁总体仍进行中；Task 3（算法/API）和 Task 4（验收）未开始。
