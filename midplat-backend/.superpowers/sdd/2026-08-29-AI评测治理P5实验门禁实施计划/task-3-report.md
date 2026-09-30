# Task 3 发布门禁算法、API 与审计报告

## 交付结果

- 新增门禁策略 API：`GET /api/evaluation/gates`、`POST /api/evaluation/gates`、`PATCH /api/evaluation/gates/{id}`。PATCH 强制 `expectedVersion`，过期版本和真实乐观并发冲突返回安全 409；列表按名称、ID 稳定排序，未开放 PUT/DELETE。
- 新增门禁决策 API：`GET /api/evaluation/gate-decisions?runId=...`、`POST /api/evaluation/runs/{id}/gate-decision`。请求必须显式指定唯一 `policyId`，不在多个启用策略中隐式猜测；停用策略在比较前即被拒绝。
- 每次决策都生成新 ID 并追加不可变历史，按 `decidedAt desc, id asc` 稳定查询；writer 在事务内依次锁定候选 run、按 `orderNo/id` 排序的全部 result 和策略行，重建候选状态指纹并复查策略版本、启用态和作用域后才写入。
- 策略 `platformId/category` 现已是真实决策约束：`null` 为通配；非空平台与候选运行精确匹配；非空分类要求候选运行不为空且全部案例分类一致，空结果、混合或不匹配均返回安全 409。
- 未修改 V24，未新增 V25、菜单、overview 或前端；Task 4 仍未开始，P5 整体仍为进行中。

## 决策与事务语义

1. 候选中已确定的 CRITICAL `FAILED` 无条件优先得到 `FAIL`，即使同时有 ERROR 或当前待审。V24 的 `requireCriticalCasesPassed` 仅作为冻结兼容元数据，不能关闭这条安全硬底线。
2. 否则，候选结果中的 ERROR/CANCELLED，以及 `PENDING + reviewRequired=true`，得到 `REVIEW_REQUIRED`；已复核为 PASSED/FAILED 且 `reviewRequired=false` 的结果按普通确定结果处理。
3. 再依次检查候选绝对通过率、相对成本增长、平均延迟、P95 延迟；任一严格超过阈值则 `FAIL`，等于阈值通过，全部满足则 `PASS`。
4. 基线成本为 0 时，候选也为 0 的增长是 0%；候选大于 0 时不做除法、不序列化 Infinity，直接确定 `FAIL`。其他成本比较用 `BigDecimal` 交叉乘法，不使用浮点数。
5. 无事务 `EvaluationGateService.decide` 作为 coordinator，通过 Spring AOP 代理调用 Task 1 `EvaluationExperimentService.compare`，使比较真实开启只读 `REPEATABLE_READ`。比较返回候选无正文状态的内部 SHA-256 指纹；独立 `EvaluationGateDecisionWriter` 开启可写事务，按 run→全部 results→policy 固定顺序持锁，在锁内用相同安全投影重建指纹并复查策略版本/启用态/作用域，再在同一事务中追加 decision 和 audit；指纹变化确定返回 409，审计注入失败时 decision 与 audit 一起回滚。

## 安全投影与证据

- Task 1 的 `Comparison` 只最小增加 `@JsonIgnore` 的基线/候选绝对质量、成本、平均/P95、候选 `platformId` 与 `candidateDecisionStateFingerprint`，以及候选结果状态、当前待审标志和 `snapshotCategory`。安全 run/result 投影增加版本和结果顺序，用于在 writer 锁内重建指纹。JPA 仍是精确物理列白名单，锁查询也仅 `SELECT id ... FOR UPDATE`，未 hydration 实体；原有八个公开 JSON 字段和六个案例字段白名单不变，不暴露输入、期望、完整输出、轨迹、目标快照或凭据。
- 算法只读上述落库安全投影，不用 `candidateOutputSummary` 识别成败。
- evidence 仅由 Task 2 结构化 codec 生成，只有 schema 版本、规则、阈值、候选聚合数字及失败案例 ID/名称。读 API 前会对已存 JSON 重做精确字段白名单校验；未知字段、重复键或超限数据不会直接返回。
- 策略 `name/platformId/category`、策略快照和 evidence 共用 gate 包内唯一安全元数据守卫：在凭据正则前按 Unicode code point 拒绝 ISO control、`Cf`、surrogate、line/paragraph separator，再拒绝 Bearer、JWT、private key、AKIA、通用 `sk-*`以及 api/access/refresh/password/passwd/secret 赋值形态。案例名称不安全时统一替换为“案例名称已脱敏”。领域直接构造/原子修订、持久化、策略快照、POST/PATCH API、审计和捕获日志边界均有自动化回归；正常中文、ASCII 空格、连字符和下划线保持可用。
- 生产 MVC 消息转换器使用独立拷贝的严格 ObjectMapper，开启重复字段检测与 `FAIL_ON_TRAILING_TOKENS`；POST/PATCH/decision 三类写请求的重复已知字段和合法 JSON 后尾随第二个 JSON 均返回安全 400，且不改变应用内部 ObjectMapper 契约。
- 更正并锁定容量口径：evidence 沿用 Task 2 codec 的 **64 KiB UTF-8 字节上限**，不是字符数上限。最坏组合中同一批 CRITICAL 失败会同时出现在高风险与通过率规则，再叠加执行错误与待审。每条规则最多保留 25 个脱敏案例，规则 `actual` 保留完整精确数量，使 128 个中文字符的最坏 UTF-8 组合仍可稳定编码。
- 审计白名单只最小增加 `enabled/version/policyId/policyVersion/conclusion`；不写入 policy/evidence JSON、正文、trace 或凭据。

## RED / GREEN 证据

1. 纯算法 RED：`mvn -Dtest=EvaluationGateServiceTest,EvaluationExperimentServiceTest test` 在 testCompile 以 32 个缺失符号失败，证明门禁服务、绝对指标和候选状态投影尚未存在。实现后门禁算法 13 项、实验服务 23 项、实验 API 4 项，当时 40/40 GREEN；最终算法扩展为 24 项。
2. API RED：`mvn -Dtest=EvaluationGateApiTest test` 首先在 testCompile 以 17 个缺失符号失败；最小 controller 落地后，先后暴露请求字段未被 Jackson 识别的 3 个 400 失败和 `ConstraintViolationException` 错误映射为 500 的 1 个失败。增加显式 JSON 属性和安全异常合同后当时 4/4 GREEN，最终 API 6/6 GREEN。
3. 真实服务编排 RED：集成测试首次为 5 个 Spring context error，原因是服务 bean 尚未存在。落地 coordinator/writer 后 5/5 GREEN，最终扩展为 10/10；事务边界继续单列 2/2，证明代理、隔离级别、只读标志、外层无事务和 writer 独立可写事务。
4. 脱敏 RED：input/expected/output 字段形态与控制换行的参数化用例初始 4/4 失败，表明仅拦截凭据不足；扩展结构正文标记与控制字符拦截后 GREEN。安全 500/409 回归也曾证明全局 handler 会在 test profile 日志或响应携带原异常，改为 gate controller 固定错误合同后 GREEN。
5. 大证据 RED：每规则保留 50 条的最坏多规则用例触发“门禁 JSON 超出字节上限”；将上限设为每规则 25 条、保留精确总数后 GREEN。
6. 关键回归第一次 256 项中有 9 个 setup error，根因是 Task 3 集成测试留下 gate decision 外键，阻断后续 P3 运行测试清库。在 Task 3 测试夹具增加事务化定向清理后，同一命令复跑 256/256 GREEN，未修改 P3。

## 独立审查失败与整改闭环

首次本地自审当时记录为未发现 Critical/Important；随后独立审查结论为 **FAIL（0 Critical / 6 Important）**。该历史结论保留，不用整改后结果覆盖。六项已按 TDD 闭环：

1. **作用域未执行**：安全 run/result 投影新增 `platformId/snapshotCategory`。RED 先以投影契约的 4 个缺失符号失败，以及服务用例“期望 409 却未抛冲突”失败；GREEN 覆盖平台不匹配、空结果、混合/不匹配分类的 0 decision/audit，以及 null 通配和全部精确匹配。SQL 物理列 allowlist 同步为 run 8 列/result 10 列，公开 JSON 白名单不变。
2. **CRITICAL 底线可被关闭**：RED 为 `requireCriticalCasesPassed=false` 时期望 FAIL、实际 PASS；GREEN 后已确定 `CRITICAL + FAILED` 始终最高优先级 FAIL，并始终生成 CRITICAL evidence。
3. **HTTP JSON 不严格**：RED 中重复 `name` 的 POST 期望 400、实际 201；GREEN 在生产 MVC converter 的独立 ObjectMapper 拷贝上开启重复检测和尾随 token 失败，POST/PATCH/decision 各两类非法请求共 6 个边界均为安全 400。
4. **安全文本规则漂移**：RED 批次 4/4 失败，证明通用 `sk-*`、策略领域构造、快照和案例名称还存在缺口；GREEN 抽取唯一 `GateSafeMetadata`，领域/codec/evidence/API/审计/日志共用同一规则。
5. **比较到写入的并发窗口**：RED 的仓储契约以 `NoSuchMethodException(findByIdForUpdate)` 失败；GREEN 新增唯一最小 `PESSIMISTIC_WRITE` 查询。PostgreSQL 更新先提交时决策为精确 `ConflictException` 且 decision/audit 均为 0；writer 先持锁时更新在 `Lock` 上等待，决策冻结版本 0，随后更新为版本 1。
6. **进度文档失真**：本轮同步修正计划 Task 1 补充和权威规格 Task 2 历史段的时态；当前始终为 Task 1–3 已完成、Task 4 未开始、P5 进行中。

额外真链路缺口由 `EvaluationGateFullChainLocalPostgresTest` 闭环：不 mock `EvaluationExperimentService`，使用真实 run/result 安全投影、真实 compare 的 `REPEATABLE_READ + readOnly`、真实作用域和带锁 writer，覆盖成功追加、scope 拒绝与两种策略更新交错。首次运行为 4 found 中 3 通过 + 1 失败，失败根因是测试夹具对 Flyway 字符串版本取 `max` 得到 9，改为整数取最大后 4/4 GREEN；该失败不是产品链路失败。

## 第三轮整改：第二轮独立复审失败与闭环

第二轮独立复审结论为 **FAIL（0 Critical / 2 Important）**。该历史结论与首次 6 Important 一并保留，不用最终绿灯覆盖：

1. **compare 后人工复核会写入陈旧结论**：审查方在真实 PostgreSQL 复现了“compare 得到 `CRITICAL PENDING/review=true`，writer 等待策略锁期间人工复核为 `FAILED/review=false`，旧 `REVIEW_REQUIRED` 仍以 201 入库”。本地首批 RED 为指纹/投影测试 testCompile 的 18 个缺失符号，随后 writer 锁边界 RED 为 7 个缺失符号。GREEN 后 Task 1 在只读 `REPEATABLE_READ` 快照内生成 `@JsonIgnore` 指纹；指纹以长度前缀规范编码和 SHA-256 覆盖 run id/status/platform/version、冻结哈希、质量可用性/通过率、成本、平均/P95、结果数，以及按 `orderNo/resultId` 排序的每条 result 的 orderNo/resultId/caseId/规范化名称/category/severity/status/reviewRequired/version，并保守覆盖 score/content hash，绝不包含正文、错误、trace、token、key 或 target JSON。writer 按 run→全部 results→policy 固定顺序锁定后重建；人工复核先提交则确定 `ConflictException` 且 decision/audit=0，writer 先锁则复核在 PostgreSQL `Lock` 上真实等待，决策冻结锁定的 `REVIEW_REQUIRED`，复核随后提交为 FAILED。该顺序和 P3 人工复核的 run→result 一致，不形成反向锁环。
2. **Unicode 隐形/方向控制绕过元数据守卫**：`mvn -Dtest=GateSafeMetadataTest,EvaluationGateServiceTest test` 的 RED 为 29 executed / 7 failures，证明 U+200B/U+202E 能拆开凭据形态。GREEN 后同命令 29/29；守卫在凭据正则之前拒绝 ISO control、`Character.FORMAT`、surrogate、line/paragraph separator，并覆盖拆分的通用 `sk-*`、Bearer、JWT、private key、AKIA、api/access/refresh/password/passwd/secret。领域 create/revise 原子性、策略快照、案例 evidence 脱敏、POST/PATCH 400、policy/audit 零变化和 `CapturedOutput` 不泄漏均有回归。

补充覆盖：策略 POST/PATCH/decision 的合法 JSON 后尾随对象、数组、数字、布尔和字符串均为安全 400；该字符化测试在已有生产严格 mapper 上直接 GREEN。非 gate 运行 API 同时验证 JavaTime、BigDecimal 和 ProblemDetail 未因全局严格解析退化。Task 1 SQL statement allowlist 仅精确增加 run/result 的 `version`，结果原有 `order_no` 已在允许集合内；锁 SQL 仅允许安全 ID，无 `*` 或正文列。第一次扩展安全投影组合回归的唯一失败是旧 barrier SQL 测试夹具未补 `version`，按系统化定位仅同步夹具后 36/36 GREEN，未削弱 allowlist。

## 最终验证

| 范围 | 命令 | found / executed / skipped | 结果 |
| --- | --- | --- | --- |
| Task 3 + Task 1 相关定向 | `mvn -Dtest='EvaluationGate*Test,GateDecisionPayloadCodecTest,GateSafeMetadataTest,EvaluationExperimentServiceTest,EvaluationExperimentApiTest,EvaluationRunComparisonReaderTest,EvaluationRunDecisionStateFingerprintTest,JpaEvaluationRunDecisionStateLockReaderTest,ExperimentComparisonSqlAssertionsTest,ExperimentComparisonBarrierInspectorTest,EvaluationRunApiTest' test` | 125 / 112 / 13 | 0 failure、0 error；13 项为未配置 PostgreSQL 时的条件跳过 |
| P1–P3 + Task 1–3 非条件关键回归 | 从 `evaluation` 测试目录显式组装并排除 `LocalPostgresTest/PostgresContainerTest` 后运行 `mvn -Dtest=... test` | 277 / 277 / 0 | 0 failure、0 error |
| Task 1 + Task 3 + 无 mock 真链路 PostgreSQL | `mvn -Dmidplat.postgres.url=jdbc:postgresql://localhost:5432/postgres -Dtest='EvaluationExperimentLocalPostgresTest,EvaluationGateLocalPostgresTest,EvaluationGateFullChainLocalPostgresTest' test` | 15 / 15 / 0 | 0 failure、0 error；Gate 7 + FullChain 6 + Experiment 2；FullChain 新增双向人工复核交错 |
| 五套历史 PostgreSQL 合跑诊断 | 同时运行 Case/Dataset/Run/Experiment/Gate 五个 `LocalPostgresTest` | 36 / 36 / 0 | **34 通过 + 2 failure，不记为通过**；Dataset 历史测试硬编码期望 V22、Run 历史测试硬编码期望 V23，当前合法实际迁移为 V24 |
| fresh 后端全量 | `mvn clean test` | 378 / 331 / 47 | 0 failure、0 error；47 项均为未配置本地 PostgreSQL/Testcontainers 的条件跳过 |
| 打包 | `mvn -DskipTests package` | 不适用 | `target/midplat-backend.jar` 构建成功 |
| 静态差异 | `git diff --check` | 不适用 | 通过，无空白错误 |
| 敏感字段扫描 | 对 Task 3 生产差异扫描正文/凭据字段引用 | 不适用 | gate 算法无 `candidateOutputSummary` 依赖；只命中固定“输出已脱敏”投影、隐藏比较契约和安全守卫正则，未新增正文读取或凭据字面量 |

PostgreSQL 环境为 16.14，服务器默认隔离级别为 `read committed`，Hikari 配置为 `TRANSACTION_READ_COMMITTED`；无 mock 真链路在 compare 的两次 reader 调用内都断言 `REPEATABLE_READ + readOnly`，writer 锁后安全投影则为可写默认隔离事务。Task 1/Task 3/FullChain 随机 schema 均从空库执行 V1–V24；15/15 独立专项成功后，三类随机 schema 前缀计数为 0。所有命令都只传递本地 URL，未打印用户名、密码或连接凭据。

## 接口扫描与自审

- producer：P3 `EvaluationRunComparisonReader` 提供终态运行 `platformId` 和无正文结果 `snapshotCategory`等精确投影；Task 1 `EvaluationExperimentService.compare` 组装对比、隐藏绝对聚合/候选状态/作用域；Task 2 策略/决策仓储和 codec 负责乐观修订、不可变追加和结构化编码。
- consumer：Task 3 只通过 `EvaluationExperimentService.compare` 读取上述安全投影，并通过最小 `EvaluationRunDecisionStateLockReader` 在 writer 事务中锁定 run/result 的安全 ID、重投影和校验指纹；之后仅通过 Task 2 append-only 边界写决策，未获得 P3 运行写仓储。Task 1 的公开 JSON 精确白名单回归仍为 8 个 data 字段和 6 个案例字段。
- 对生产者/消费者引用、HTTP mapping、严格 JSON converter、新增 `@JsonIgnore` 指纹、SQL/JSON 白名单、审计 allowlist、Unicode/敏感词形、run/result/policy 固定锁顺序与 Task 3 scoped diff 进行了人工扫描。首次本地自审的“未发现”结论已被后续首次 6 Important 和第二轮 2 Important 推翻；两轮均按上述 RED/GREEN 和 PostgreSQL 真链路证据逐项闭环。

## 已知边界与后续风险

- 五套 PostgreSQL 合跑的两条历史迁移版本断言对未来迁移不兼容；本任务遵守 Task 3 文件所有权不修改 P2/P3 测试，并准确保留 36 = 34 通过 + 2 失败的诊断记录。Task 1 + Task 3 + 无 mock 真链路 PostgreSQL 15/15 独立通过（Gate 7 + FullChain 6 + Experiment 2）。
- 策略选择首期是显式 `policyId` 语义；平台/分类作用域已执行确定性校验，但不在多个启用策略中自动选择。未来如果要自动命中，需另行定义通配/精确匹配优先级和多策略冲突契约。
- 首期复用中台部署边界，不在本模块重建企业 RBAC；统一身份与权限仍是中台级后续治理项。
- 指纹校验会保守拒绝任何候选安全状态变化，即使个别变化最终不改变结论；调用方需重新发起决策，这优先保证不会保存陈旧 evidence。
- Flyway 9.22.3 会对 PostgreSQL 16.14 给出未完全声明支持的版本警告；V1–V24 已在该真实方言运行。
- Task 4 的 V25、菜单、治理总览和阶段最终验收尚未开始，不应据本报告宣称 P5 整体完成。
