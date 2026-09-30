# Task 2：版本编排、冻结快照和哈希 — 执行报告

## 变更摘要

- 新增 `DatasetSnapshotHasher`：按条目顺序对 `caseId`、`contentHash` 逐段写入 4 字节大端长度前缀后计算 SHA-256。
- 新增事务性 `EvaluationDatasetService`：原子创建数据集与 V1 草稿、读取/列表、草稿条目替换、冻结完整案例快照、从冻结版本派生下一个草稿。
- 冻结事务锁定数据集版本及案例行，冻结前要求至少一个 `ACTIVE + REVIEWED` 案例；复制名称、分类、严重级别、输入、期望 JSON、评分器及内容哈希。快照一旦写入不可替换。
- 新增 V22：`item_revision` 使条目替换推进版本聚合的 JPA 乐观锁版本；`last_derived_at` 使派生推进数据集聚合版本。二者配合 PostgreSQL `READ_COMMITTED` 下的悲观行锁，保证替换、冻结及派生的 `expectedVersion` 契约。
- `EvaluationAuditService`、其构造器与 `recordResource` 均改为 `public`；通用审计入口只允许标量白名单元数据，拒绝正文、期望与完整快照字段。创建、编排、冻结、派生均追加审计，审计失败会回滚同一业务事务。
- `EvaluationCaseRepository` 增加跨领域冻结所需的锁定批量读取；数据集仓储增加锁定读取和草稿条目替换所需的受限操作。审计仓储未新增删除或覆盖能力。

## RED / GREEN 证据

### RED

命令：

```bash
mvn -Dtest=DatasetSnapshotHasherTest,EvaluationDatasetServiceTest test
```

结果：`BUILD FAILURE`；测试编译明确报告 `DatasetSnapshotHasher`、`EvaluationDatasetService` 缺失，且数据集测试不能访问跨领域案例批量读取入口。这是缺少目标服务/哈希器导致的预期红灯，而非测试装配失败。

### GREEN

命令：

```bash
mvn -Dtest=DatasetSnapshotHasherTest,EvaluationDatasetServiceTest,EvaluationDatasetServiceRollbackTest,EvaluationCaseApiTest,EvaluationAuditLogRepositoryContractTest test
```

结果：`Tests run: 32, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

覆盖了哈希确定性和顺序敏感、空/未审核/归档案例冻结拒绝、草稿编排顺序和去重/不存在 ID、冻结快照与不可替换、V2 派生顺序及版本递增、期望版本冲突、审计正文拦截、审计写入失败的事务回滚，以及 P1 案例 API 与审计仓储不暴露删除/批量覆盖契约。

## 测试命令与数量

```bash
mvn -Dtest=DatasetSnapshotHasherTest,EvaluationDatasetServiceTest,EvaluationDatasetServiceRollbackTest,EvaluationCaseApiTest,EvaluationAuditLogRepositoryContractTest test
mvn test
```

- 定向回归：32 项执行通过，0 失败、0 错误。
- 全量回归：86 项执行通过，0 失败、0 错误、10 项条件式 PostgreSQL/Testcontainers 套件因本机 Docker 不可用跳过。

## 接口与约束映射

| 契约/约束 | 落点 |
| --- | --- |
| 创建数据集与 V1 草稿原子化 | `EvaluationDatasetService#create`；审计失败回滚测试 |
| 版本编排与 `expectedVersion` | `replaceItems`、`EvaluationDatasetVersion#replaceItems`、`item_revision` |
| 冻结资格、完整不可变快照与哈希 | `freeze`、`EvaluationDatasetItem#freezeSnapshot`、`DatasetSnapshotHasher` |
| 冻结后不可替换 | 领域状态校验与服务测试 |
| 从冻结版本按最大版本号派生 | `deriveDraft`、数据集行锁、`last_derived_at` |
| 只追加、脱敏审计 | `EvaluationAuditService#recordResource` 白名单；审计仓储契约测试 |
| PostgreSQL `READ_COMMITTED` 并发边界 | 数据集/版本/案例的悲观行锁；H2 仅执行快速回归 |

## 提交哈希

代码提交：`2b67f8b 功能：实现评测集冻结快照与版本派生`。

本报告在该代码提交后的独立文档提交中固化；其最终提交哈希见执行者最终回报。

## 遗留风险

- 本任务只运行 H2 快速回归；真实 PostgreSQL 的并发、唯一约束和冻结不可变性专项验证仍应由 Task 4 在本地受控 schema / CI 环境执行。
- `V22` 为实现版本聚合的真实乐观锁推进而增加两个最小持久化字段；后续 REST 层需始终回传并要求相应的 `expectedVersion`，不得绕过服务层直接写入版本或条目表。

## 审查修复

### 修复内容

- 将 `EvaluationAuditService` 的摘要值收紧为显式标量集合：`String`、`Number`、`Boolean`、`Enum`、`Instant` 与 `null`。允许字段名承载 `EvaluationCase` 等 Bean 时会在序列化前拒绝；回归测试读取实际 `summary_json`，确认敏感 `inputText` 与 `expectedJson` 未泄漏。
- 将 `EvaluationCaseRepository` 恢复为包私有；新增公开只读/锁定端口 `EvaluationCaseSnapshotReader`，仅暴露不可变案例快照和存在性查询，不向 dataset 域泄露 `save`、`saveAll`、`delete*` 或 `flush`。契约测试同时验证仓储不可公开访问。
- 批量案例悲观锁查询固定按案例 `id` 升序取锁；普通存在性校验改为无锁 ID 查询。`PessimisticLockingFailureException`（涵盖 Spring 转换后的锁等待、死锁和序列化冲突）统一映射为既有 409 冲突问题模型。
- 补齐 `replaceItems`、`freeze`、`deriveDraft` 在审计追加失败时的完整事务回滚。冻结中前一条目已尝试写入快照、后一案例资格失败的用例，以新事务确认所有快照列仍为空、版本仍为 `DRAFT` 且无冻结审计。

### RED / GREEN

RED 先新增审查回归测试并运行：

```bash
mvn -Dtest=EvaluationCaseSnapshotReaderContractTest,EvaluationDatasetAuditSafetyTest,EvaluationDatasetServiceRollbackTest,GlobalExceptionHandlerTest test
```

结果：`BUILD FAILURE`。编译明确指出跨域最小端口 `EvaluationCaseSnapshotReader` 尚不存在；此后按测试所表达的 API 边界、摘要 Bean 拒绝和锁冲突契约补最小实现。

GREEN：

```bash
mvn -Dtest=DatasetSnapshotHasherTest,EvaluationDatasetServiceTest,EvaluationDatasetServiceRollbackTest,EvaluationDatasetAuditSafetyTest,EvaluationCaseSnapshotReaderContractTest,EvaluationCaseApiTest,EvaluationAuditLogRepositoryContractTest,GlobalExceptionHandlerTest test
mvn test
```

- 定向回归：`Tests run: 42, Failures: 0, Errors: 0, Skipped: 0`。
- 全量回归：`Tests run: 94, Failures: 0, Errors: 0, Skipped: 10`；跳过项为本机 Docker 不可用时的条件式 PostgreSQL/Testcontainers 套件。

### 提交

- 审查修复代码提交：`29b49e4 修复：收紧评测集审计与并发边界`。
- 本报告追加内容在后续独立文档提交中固化。

### Task 4 PostgreSQL 验证遗留

- 仍需在真实 PostgreSQL `READ_COMMITTED` 下实测两个重叠冻结请求是否按 `id` 排序取锁且任一锁/死锁/序列化失败返回 409，而非 500。
- 仍需实测并发 `deriveDraft` 的数据集行锁、唯一草稿约束和 `max(version_no)+1` 在冲突后的最终状态。
