# Task 1 报告：运行、结果与模型计价结构

## 变更

- 新增 Flyway `V23__evaluation_runs_and_model_pricing.sql`：模型输入/输出每百万 Token 价格，以及评测运行和结果表。
- 新增 `evaluation.run` 的运行/结果状态、聚合实体和受限 Spring Data `Repository` 接口。
- 模型创建、更新和响应支持精确 `BigDecimal` 计价；缺省价格为 `0`，负数请求返回 400，响应不含 `apiKey`。
- 收紧 `ModelService.requireModel` 为包内实现；评测读取仅可获得无密钥 `EvaluationModelSnapshot` 或按模型 ID 即时读取当前凭据。既有运行时调用改用内部 `ModelRuntimeConfiguration`，没有把 `AiModel` 实体跨包暴露。

## 迁移结构

- `midplat_model` 增加 `input_price_per_million`、`output_price_per_million`，均为 `numeric(20,8) not null default 0`，并有非负 CHECK。
- `midplat_eval_run` 保存数据集版本、平台/模型/Prompt ID、无密钥目标快照、参数、状态/取消标记、进度统计、用量/成本、延迟、时间、固定安全错误摘要和 BaseEntity 审计列。
- `midplat_eval_result` 保存运行/案例/顺序、冻结案例快照、输出、评分/人工复核、状态、安全错误摘要、脱敏轨迹、延迟、用量/成本、尝试次数、人工评分时间/摘要和 BaseEntity 审计列。
- 结果表强制 `(run_id, case_id)` 和 `(run_id, order_no)` 唯一；两表均有状态、非负、终态时间等 CHECK。JSON 和大文本均采用 PostgreSQL/H2 可用的 `text`。

## RED / GREEN

- RED：先加入 `EvaluationRunRepositoryTest` 和模型 API/安全边界测试，执行 `mvn -Dtest=EvaluationRunRepositoryTest,PlatformApiTest test`；因运行实体、状态和仓储尚不存在，测试编译阶段报 23 个“找不到符号”。
- RED：补充公开实体边界反射断言，`mvn -Dtest=ModelServiceEvaluationAccessTest test` 失败，确认 `requireModel` 当时为 `public`。
- GREEN：实现 V23、实体、受限仓储、模型计价和无密钥评测读取边界后，指定回归及运行时回归全部通过。

## 测试证据

- `mvn -Dtest=EvaluationRunRepositoryTest,PlatformApiTest test`：10 tests，0 failures。
- `mvn -Dtest=ModelServiceEvaluationAccessTest test`：2 tests，0 failures。
- `mvn -Dtest=ModelServiceEvaluationAccessTest,RuntimeApiTest,PlatformApiTest,EvaluationRunRepositoryTest test`：16 tests，0 failures。
- `mvn test`：114 tests，0 failures / 0 errors，15 skipped（已有 PostgreSQL/Testcontainers 测试因本机 Docker 不可用跳过）。空 H2 从 V1 连续迁移至 V23 已由 Spring/Flyway 日志和仓储回归覆盖。
- `git diff --check`：通过。

## 接口裁决映射

| Ledger 裁决 | 落地 |
| --- | --- |
| 无密钥模型快照 + 当前凭据即时读取 | `EvaluationModelSnapshot` 不含密钥；`requireCurrentApiKey(id)` 仅在执行前按 ID 读取；运行实体拒绝包含 `apiKey`/`api_key` 的快照。 |
| 不公开评测写仓储 | 运行/结果仓储只继承 `Repository`，仅暴露 save/find/锁定/list/count；反射测试禁止 `delete*` 与 `saveAll`。 |
| 快照白名单，密钥不得落库 | V23 的运行快照为独立 text 列；实体构造时拦截密钥字段，测试使用示例 `sk-example-secret` 验证。 |
| 历史结果不可覆盖 | 数据库唯一约束保证每运行/案例和每运行/顺序各一条最终结果；没有公开删除或批量覆盖接口。 |

## 提交

- 代码提交哈希：`8d545c5`。
- 提交信息：`功能：建立评测运行与模型计价结构`。

## 遗留风险

- V23 已通过 H2 PostgreSQL 模式；真实 PostgreSQL 的并发/大结果/乐观锁验收仍属于 Task 5。
- Task 4/5 还需在现有受限仓储上实现编排、统计聚合、取消、重试和恢复；本任务未实现评分器、HTTP 执行器或运行编排。

## 审查修复

### RED / GREEN

- RED：旧格式 PATCH 在价格字段缺省时把非零价格重置为零；小数超过 8 位的价格返回 201。新增真实 MockMvc 回归后，二者均按预期失败。
- RED：新增状态机、快照白名单和受限凭据载体测试时，因缺少类型/方法出现编译失败；新增运行时凭据载体可见性断言在原有公开 record 上失败。
- GREEN：创建时 `null -> 0`、更新时 `null -> 保留现值`；Controller 和领域层均强制 12 位整数/8 位小数、非负。补齐状态守卫、手工评分、版本守卫、白名单 DTO/受控序列化、不可公开的运行时凭据载体和 V23 外键后回归通过。

### 迁移、状态机与白名单

- V23 为运行的 dataset version、platform、model、prompt 添加默认 RESTRICT/NO ACTION 外键；运行创建的 FROZEN 状态校验仍由 Task 4 服务层负责。
- `EvaluationRun` 初始化总数，支持开始、终态结果统计累计、取消的 expectedVersion 守卫、终态完成和恢复失败；终态及时间字段不可被再次变更。
- `EvaluationResult` 强制 PENDING→RUNNING；普通完成只能自 RUNNING 进入终态；MANUAL 保存执行数据后回到 PENDING + `reviewRequired`，人工评分带 expectedVersion 后进入 PASSED/FAILED。
- `EvaluationTargetSnapshot` 和 `EvaluationRunParameters` 是字段白名单值对象，实体只接受这些值对象后用受控 Jackson 序列化；不存在可传任意 JSON 的公开构造入口。运行时的含凭据载体包内可见且 `toString()` 固定脱敏。

### 验证

- 定向模型/运行/运行时回归：25 tests，0 failures/errors。
- `mvn test`：124 tests，0 failures/errors，15 skipped（既有 Docker/PostgreSQL 用例在本机 Docker 不可用时跳过）。
- `git diff --check`：通过。

### 审查修复提交与遗留风险

- 修复提交哈希：`998c0a0`。
- 真实 PostgreSQL 下的外键、乐观锁并发和大结果验证仍由 Task 5 完成；本任务未新增执行器或异步编排。

## 第二轮审查修复

### RED / GREEN

- RED：先扩展运行状态测试，`mvn -Dtest=EvaluationRunStateTest test` 在编译阶段报 9 个缺少 `complete`、`cancel`、固定恢复失败入口和结果成本读取的方法错误；此时逐题统计仍把 P95 写成最大延迟。
- RED：先扩展 V23 元数据测试，`mvn -Dtest=EvaluationRunRepositoryTest test` 失败于缺失 `idx_eval_run_platform` / `idx_eval_run_model` / `idx_eval_run_prompt`。
- GREEN：P95 改为终结时基于完整延迟集合计算；补三项引用索引、显式终结领域方法、人工待复核重入保护以及价格显式 `null` 持久化回归后测试通过。

### P95 与状态机契约

- P95 不再在逐题累计时伪装成最大值。`complete` 接收本次运行已落库结果的完整延迟集合，按 nearest-rank：`rank = ceil(0.95 × N)`；空集为 `0`，单样本即该值。100 个延迟 `1..100` 的回归断言 P95 为 `95`，而非最大值 `100`；延迟集合不写入运行实体。
- 正常 `COMPLETED` / `PARTIAL` 仅可从 `RUNNING` 结束，且完成计数必须与 `totalCount` 及延迟集合大小一致；`CANCELLED` 仅在先请求取消后由 `QUEUED` 或 `RUNNING` 结束；普通失败仅能自 `RUNNING` 调用 `failDuringRun`，恢复失败使用固定安全原因。所有终态均写入完成时间，终态不可再次变更。
- MANUAL 执行结束后结果保持 `PENDING + reviewRequired`，拒绝再次 `markRunning`；人工评分只能携带期望版本一次性终结，重复评分不改变既有输出、Token 或成本。

| 起始状态 | 允许动作 | 结果状态 |
| --- | --- | --- |
| QUEUED | requestCancellation + cancel | CANCELLED |
| QUEUED | markRunning | RUNNING |
| RUNNING | complete(COMPLETED/PARTIAL) | COMPLETED / PARTIAL |
| RUNNING | failDuringRun / recoverAsFailed | FAILED |
| RUNNING | requestCancellation + cancel | CANCELLED |
| PENDING（结果） | markRunning | RUNNING |
| RUNNING（结果） | recordManualExecution | PENDING + reviewRequired |
| PENDING + reviewRequired | review(expectedVersion) | PASSED / FAILED |

### 外键、索引与计价回归

- V23 保持四个引用的默认 `NO ACTION/RESTRICT` 外键，并新增 `idx_eval_run_platform`、`idx_eval_run_model`、`idx_eval_run_prompt`；H2 元数据测试确认 dataset version 与三个新增索引均存在。
- 四个缺失父引用分别在独立测试事务中失败；已有运行时删除模型/平台、更新 Prompt / dataset version 主键均被外键拒绝。
- 模型 PATCH 缺省价格和显式 JSON `null` 均保持既有价格。回归在 flush + clear 后的新读取中按 `compareTo` 与 8 位小数字符串验证精度；API 新请求同样验证 `1.25000000` / `2.50000000` 的原样返回。

### 验证与提交

- `mvn -Dtest=EvaluationRunStateTest,EvaluationRunRepositoryTest,PlatformApiTest,ModelServiceEvaluationAccessTest test`：35 tests，0 failures / 0 errors。
- `mvn -Dtest=EvaluationRunRepositoryTest,EvaluationRunStateTest,PlatformApiTest,ModelServiceEvaluationAccessTest,RuntimeApiTest test`：39 tests，0 failures / 0 errors。
- `mvn test`：137 tests，0 failures / 0 errors，15 skipped（既有 Docker/PostgreSQL 用例在本机 Docker 不可用时跳过）。
- `git diff --check`：通过。
- 修复提交哈希：`7e521a1`（`修复：完善评测运行终态与分位统计`）。

### 遗留风险

- V23 已由 H2 从空库 V1–V23 迁移和元数据测试覆盖；PostgreSQL 上的索引计划、外键行为及高并发乐观锁仍需集成环境验证。
- Task 4 需从已持久化结果查询完整延迟集合后调用运行终结方法；本任务未新增异步执行或编排。

## 第三轮审查修复

### RED / GREEN

- RED：先将 `complete` 调整为不带目标状态的测试，并加入只读延迟重载与双向完成时间 CHECK 测试；`mvn -Dtest=EvaluationRunStateTest,EvaluationRunRepositoryTest test` 在编译阶段报 19 个旧三参数 `complete` 和缺失 `getLatencyMs` 错误。
- GREEN：移除可任选 `COMPLETED/PARTIAL` 的终结入口；运行只根据 `errorCount` 推导正常终态。结果实体公开只读 `getLatencyMs`，受限仓储按 `runId/orderNo` 重载后可在新的事务中完成运行。

### 统计、状态与数据库约束

- `complete(completedAt, latencies)` 校验延迟集合长度与 `completedCount` 一致、仅能从 `RUNNING` 调用；`errorCount == 0` 推导 `COMPLETED`，否则推导 `PARTIAL`。MANUAL 已执行案例计入完成数且不计 error；取消、执行失败和恢复失败仍使用原有专用终结路径。
- 平均延迟与 P95 均仅在终结时从完整结果延迟集合计算，不在逐题累计时做滚动整数除法。平均值包含真实的 `0ms`，以整型总和一次除以数量；P95 为 nearest-rank。`[1,2,3]` 与 `[3,1,2]` 均验证平均 `2`、P95 `3`，100 条 `1..100` 的 P95 仍为 `95`。
- 运行结果重载测试在首个事务中写入两个自动结果和一个待人工复核结果，flush + clear 后第二个事务按 `orderNo` 得到 `[3,1,2]`，第三个事务成功终结运行并持久化平均/P95。
- V23 的 `chk_eval_run_terminal_completion` 改为双向：`QUEUED/RUNNING` 必须无完成时间，四种终态必须有完成时间。独立原生 SQL 测试验证两种非法组合均失败、两种合法组合均可插入。

### 验证与提交

- `mvn -Dtest=EvaluationRunStateTest,EvaluationRunRepositoryTest,PlatformApiTest,ModelServiceEvaluationAccessTest,RuntimeApiTest test`：44 tests，0 failures / 0 errors。
- `mvn test`：142 tests，0 failures / 0 errors，15 skipped（既有 Docker/PostgreSQL 用例在本机 Docker 不可用时跳过）。
- `git diff --check`：通过。
- 修复提交哈希：`0f9f01b`（`修复：完善评测运行统计终结`）。

### 遗留风险

- H2 的空库 V1–V23 迁移和事务重载已覆盖；真实 PostgreSQL 的约束、数值边界及并发乐观锁仍需集成环境验证。
