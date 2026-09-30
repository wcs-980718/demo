# Task 1：建立数据集、版本与快照条目结构 — 执行报告

## 实现内容

- 新增 Flyway V21：创建评测数据集、数据集版本、数据集条目三张表。
- 版本表落实 `(dataset_id, version_no)` 唯一约束和 `DRAFT`、`FROZEN`、`ARCHIVED` 状态检查；条目表落实 `(version_id, case_id)`、`(version_id, order_no)` 唯一约束。
- 条目表包含冻结快照字段 `snapshot_name`、`snapshot_category`、`snapshot_severity`、`snapshot_input_text`、`snapshot_expected_json`、`snapshot_evaluator_type`、`snapshot_content_hash`；草稿创建时均可为空。
- 新增 JPA 实体、`DatasetVersionStatus` 与三个仓储。版本提供按数据集倒序查询及草稿存在性判断，条目提供按版本顺序查询。
- `EvaluationDatasetVersion.createDraft(id, datasetId, versionNo)` 创建 `DRAFT`；`EvaluationDatasetItem.forDraft(id, versionId, caseId, orderNo)` 创建未冻结条目。

## 文件

- `src/main/resources/db/migration/V21__evaluation_dataset_versions.sql`
- `src/main/java/com/yiwei/midplat/evaluation/dataset/DatasetVersionStatus.java`
- `src/main/java/com/yiwei/midplat/evaluation/dataset/EvaluationDataset.java`
- `src/main/java/com/yiwei/midplat/evaluation/dataset/EvaluationDatasetVersion.java`
- `src/main/java/com/yiwei/midplat/evaluation/dataset/EvaluationDatasetItem.java`
- `src/main/java/com/yiwei/midplat/evaluation/dataset/EvaluationDatasetRepository.java`
- `src/main/java/com/yiwei/midplat/evaluation/dataset/EvaluationDatasetVersionRepository.java`
- `src/main/java/com/yiwei/midplat/evaluation/dataset/EvaluationDatasetItemRepository.java`
- `src/test/java/com/yiwei/midplat/evaluation/dataset/EvaluationDatasetRepositoryTest.java`

## TDD 记录

### RED

命令：

```bash
mvn -Dtest=EvaluationDatasetRepositoryTest test
```

关键输出：`EvaluationDatasetRepository`、`EvaluationDatasetVersionRepository`、`EvaluationDatasetItemRepository`、`EvaluationDataset`、`EvaluationDatasetVersion`、`EvaluationDatasetItem` 和 `DatasetVersionStatus` 均“找不到符号”，Maven 以 `BUILD FAILURE` 结束。这确认测试因所需迁移/类型尚未实现而失败。

### GREEN

命令：

```bash
mvn -Dtest=EvaluationDatasetRepositoryTest test
```

关键输出：Flyway 成功执行 V1–V21，日志显示 `Successfully applied 21 migrations ... now at version v21`；`EvaluationDatasetRepositoryTest` 共 2 项测试，`Failures: 0, Errors: 0`，Maven `BUILD SUCCESS`。

## 全量测试

命令：

```bash
mvn test
```

关键输出：`Tests run: 70, Failures: 0, Errors: 0, Skipped: 10`，Maven `BUILD SUCCESS`。10 项跳过为既有 PostgreSQL/Testcontainers 测试；本机 Docker 不可用时其条件性跳过保持原状。

## 自审

- V21 包含任务要求的三张表、三项唯一性约束和版本状态检查约束。
- JPA 映射与迁移字段一致，所有实体复用项目的 `BaseEntity` 审计时间戳与乐观锁版本列。
- 版本仓储按 `version_no desc` 查询；条目仓储按 `order_no asc` 查询；定向测试覆盖二者及草稿状态/存在性、草稿条目无快照。
- 数据集版本及条目对数据集/版本/案例设置外键，避免悬空关联。
- `git diff --check` 无空白错误；未修改任务 2 以后的服务、控制器或文档。

## Concerns

- 本任务按边界仅建立持久化结构和草稿存在性查询；“创建数据集自动创建 V1 草稿”、单草稿并发控制、冻结前 ACTIVE+REVIEWED 校验、快照一次性写入与追加脱敏审计须由后续冻结/创建服务实现。
- 生产 PostgreSQL 集成测试因本机 Docker 不可用被既有条件跳过；H2 PostgreSQL 模式下 V1–V21 迁移和仓储回归已验证。

## 审查修复

### 修复内容

- V21（尚未发布）补充版本级 `snapshot_hash` 与 `frozen_at`，`EvaluationDatasetVersion.freeze(hash, frozenAt, expectedVersion)` 将草稿改为 `FROZEN` 并持久化两项元数据；新增 getter。
- V21 增加可空 `draft_slot`。CHECK 规定仅 `DRAFT` 为 `1`，其余状态为 `NULL`；`unique (dataset_id, draft_slot)` 因而在 PostgreSQL 与 H2 PostgreSQL 模式下均可原子拒绝同一数据集的第二个草稿。
- V21 增加冻结状态完整性 CHECK：草稿不得含冻结元数据，`FROZEN`/`ARCHIVED` 必须同时有快照哈希和冻结时间。
- 三个数据集仓储改为 Spring Data `Repository`，仅公开本任务所需的 `save`、`findById` 和派生查询，不再继承通用 `delete*` API。
- 仓储测试新增冻结元数据往返、数据库单草稿拒绝、非法/不完整状态拒绝和反射验证无通用删除 API；测试总数从 2 增至 7。

### RED 证据

1. 命令：

   ```bash
   mvn -Dtest=EvaluationDatasetRepositoryTest#shouldPersistSnapshotHashAndFrozenAtWhenFreezingDraft test
   ```

   结果：编译失败，`EvaluationDatasetVersion` 缺少 `freeze(String, Instant, long)`、`getSnapshotHash()` 和 `getFrozenAt()`。

2. 命令：

   ```bash
   mvn -Dtest=EvaluationDatasetRepositoryTest test
   ```

   结果：7 项中 2 项失败。第二个草稿未抛出 `PersistenceException`，证明原 `(dataset_id, version_no)` 无法防止双草稿；三个仓储的反射检查发现 `delete*` 方法。非法状态和缺失冻结元数据的数据库 CHECK 已按预期拒绝插入。

### GREEN 与全量验证

1. 定向命令：

   ```bash
   mvn -Dtest=EvaluationDatasetRepositoryTest test
   ```

   结果：7 项通过，Flyway V1–V21 成功；日志显示第二草稿被 `uq_eval_dataset_version_draft` 拒绝，非法/不完整状态被 CHECK 拒绝。

2. 全量命令：

   ```bash
   mvn test
   ```

   结果：`Tests run: 75, Failures: 0, Errors: 0, Skipped: 10`，`BUILD SUCCESS`。10 项仍是本机无 Docker 时既有 PostgreSQL/Testcontainers 条件性跳过。

### 修复提交

`693830a 修复：补齐评测数据集版本约束`

### 遗留风险

- 本任务未另写线程并发测试：数据库唯一约束已在 PostgreSQL `READ_COMMITTED` 下提供原子冲突保护。Task 2 创建/派生服务需要将唯一冲突转换为领域冲突并追加审计；Task 4 的 PostgreSQL 集成测试应覆盖两个并发创建/派生草稿请求时一方成功、一方冲突的端到端行为。
- 本机 Docker 不可用，生产 PostgreSQL/Testcontainers 测试仍按既有条件跳过；H2 PostgreSQL 模式下的 V1–V21 全新迁移链和仓储边界已验证。
