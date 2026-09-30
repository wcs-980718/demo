# Task 4 报告：持久化评测运行编排

## 架构与事务边界

- `EvaluationRunService` 在同一个创建事务中读取冻结数据集、无凭据平台/模型/Prompt 快照，写入 `QUEUED` run 与每个 `PENDING` result、写入 `RUN_CREATED` 审计并发布 `EvaluationRunEvent`。
- `EvaluationRunDispatcher` 仅以 `AFTER_COMMIT + @Async("evaluationTaskExecutor")` 派发。线程池默认 core=1、max=2、queue=50，拒绝时不回退到 HTTP 线程；运行仍保持 `QUEUED`。
- `EvaluationRunExecutionService` 每题按顺序调用 StateService 的短事务：`RUNNING` 标记/快照读取 → 事务外目标调用和纯评分 → 同一短事务保存结果与 run 累计 → 从已保存结果集合计算平均与 P95 并终结运行。
- 取消、错误重跑和人工评分锁定顺序均为 run 后 result。V23 增加非负 `retry_revision`，源 run 的版本守卫和该修订变更保证同版本的重跑只有一个成功者。

## RED / GREEN

- RED：先加入 `EvaluationRunServiceTest`，`mvn -Dtest=EvaluationRunServiceTest test` 因缺少 `EvaluationRunService` 在 test-compile 阶段失败。
- GREEN：增加服务、状态服务、执行器、提交后派发器、配置、冻结数据集读端口、平台安全快照与 V23 retry revision 后该测试通过。
- RED：随后加入 `EvaluationRunApiTest`，`mvn -Dtest=EvaluationRunApiTest,EvaluationRunServiceTest test` 因缺少 `EvaluationRunController` 在 test-compile 阶段失败。
- GREEN：增加七条 `/api/evaluation/runs` 路由、分页和固定安全错误边界后组合测试通过。

## 状态与接口契约

- retryable 目标错误至多三次总尝试；4xx/配置/评分错误仅一次。目标调用与评分前均检查不存在活动 Spring 事务。
- 自动评分写 `PASSED/FAILED`；评分配置问题写带成功输出、用量、成本的 `ERROR`；MANUAL 保存执行结果后保持 `PENDING + reviewRequired`。
- `results` 是唯一返回案例快照正文与实际输出的接口；列表和运行详情仅返回统计。Controller 对 400/404/409 使用固定 detail 与 `/api/evaluation/runs` instance。
- 成本以每百万 Token 输入/输出价格计算，HALF_UP 到 8 位；超出 `numeric(20,8)` 时安全 `ERROR`，不把数据库错误伪装成成功。

## 验证

- `mvn -Dtest=EvaluationRunServiceTest test`：1 tests，0 failures / 0 errors。
- `mvn -Dtest=EvaluationRunApiTest,EvaluationRunServiceTest test`：2 tests，0 failures / 0 errors。
- `mvn test`：186 tests，0 failures / 0 errors，15 skipped（既有 PostgreSQL/Testcontainers 条件跳过）。
- `git diff --check`：通过。

## 遗留 Task 5 风险

- 本任务不会在进程启动时扫描 `QUEUED/RUNNING` 任务；被队列拒绝或进程崩溃遗留的运行由 Task 5 恢复器处理。
- Task 5 还需在真实 PostgreSQL 上验证高并发取消/重跑、乐观锁与 V23 结构约束，并补全受控 fake executor 的时序集成回归。

## 审查修复

- 原先的“类存在”测试已废弃；`EvaluationRunServiceTest` 现在启动真实 Spring Boot、Flyway V1--V23 和 H2 JPA，使用 `@Primary` 受控目标 fake。它验证事务提交后才进入真实线程池、目标调用无活动事务、两个冻结案例落库并正常终结。
- 取消改为单写者：`QUEUED` 在 cancel 事务内把每个 PENDING 结果改为 CANCELLED 并逐项累计完成数后终结；`RUNNING` 只置 flag，不再派发第二 worker，原 worker 在当前题落库后收口剩余案例。重复取消被领域状态机拒绝。
- `AFTER_COMMIT` listener 同步提交到显式 `evaluationTaskExecutor` 并在调用点捕获拒绝；不会 CallerRuns。队列拒绝保留 QUEUED 供 Task 5 恢复。
- 模型 kind 加入同一无密钥 evaluation snapshot，创建只使用该单次读取；V23 新增 `source_run_id` 自引用和索引，retry run 持久化 lineage，并保留 source retry revision 的乐观并发边界。
- 运行累计 token 使用 `Math.addExact`，总成本预检 numeric(20,8) 上界；输入/期望/输出快照与 view 的 `toString` 均脱敏。

本次修复定向测试 2 项、全量 186 项通过（15 条既有 PostgreSQL/Testcontainers 条件跳过）。仍待 Task 5 在真实 PostgreSQL 完成高并发、启动恢复和队列饱和的环境级验收。

## 第二轮审查修复

- `shouldLetRunningWorkerFinishCurrentThenCancelRemainingWithoutSecondDispatch` 使用 CountDownLatch 固定外调中的当前题；取消只置标记，释放外调后当前题 PASSED、剩余题 CANCELLED，run CANCELLED，`completedCount == totalCount`。
- 共享 `CONFIGURATION_ERROR`、损坏快照和空任务经状态服务收口为 FAILED；剩余 PENDING 逐项取消并累计。未知异常也会把 RUNNING 安全错误化后收口。
- 本轮 `EvaluationRunServiceTest` 为 2 个真实 Spring/H2/线程池测试，均通过；全量回归仍为 186 passed / 15 skipped。

## 第三轮审查修复

- 新增 `EvaluationRunSnapshotCodec`：目标与参数快照均封装 `schemaVersion:1`，持久化读取使用专用 mapper，拒绝未知字段、尾随内容、重复字段、缺失/空 creator 参数与未知版本。
- StateService 和 retry 均经 codec 反序列化，不再使用可宽松解释的应用 ObjectMapper。

## 接管修复验收

### RED / GREEN 记录

- RED：`shouldRunTargetAndEvaluatorWithoutAnyActiveTransaction` 首次失败，原因是执行服务内部 `new EvaluationEvaluatorRegistry()`，Spring 受控 spy 无法观测评分交易边界。GREEN：Registry 改为容器单例注入，target 与 evaluator 均证实无活动事务。
- RED：`shouldValidateTokenAndCostAggregateLimitsBeforeMutatingCounters` 首次失败，token 溢出后 `completedCount` 已从 1 变为 2。GREEN：先用 `Math.addExact` 和 numeric(20,8) 上界计算全部 next values，成功后才变更计数与累计值。
- RED：`shouldTreatSingleCaseNumericCostOverflowAsFatalFailedRun` 首次得到非 FAILED 收口。GREEN：单案例 `COST_OVERFLOW` 落为 ERROR，再以独立可提交状态事务把 run 收口为 FAILED。
- RED：真实 MockMvc API 批次暴露分页缺少 `totalPages`，校验错误的 `instance` 为全局 `/api`。GREEN：运行控制器返回真实 Page 元数据，并在七路由边界固定 400/404/409 的 `instance=/api/evaluation/runs`。
- RED：扩展 `shouldRedactEveryPromptInputExpectedAndOutputBearingDiagnosticRepresentation` 后，`PromptController.CreateRequest` 默认 `toString()` 泄露 prompt body。GREEN：Prompt 和案例中 5 个 HTTP 请求 record，连同 service/view/snapshot/execution/response 对象，均通过同一哨兵脱敏测试。

### `EvaluationRunServiceTest`（35 次测试）

- `shouldDispatchAfterCommitOutsideTransactionAndFinish`：真实事务提交后派发并终结。
- `shouldRunTargetAndEvaluatorWithoutAnyActiveTransaction`：target/evaluator 均在事务外。
- `shouldNotDispatchWhenOuterTransactionRollsBack`：外层回滚时 run/result 不留库且不派发。
- `shouldKeepCommittedRunQueuedWhenRealExecutorRejectsWithoutCallerRuns`：真实 `ThreadPoolTaskExecutor` 两 worker + 50 queue 饱和，HTTP create 仍 201/QUEUED，无 CallerRuns、无 target 调用，固定无 runId/正文日志。
- `shouldLetRunningWorkerFinishCurrentThenCancelRemainingWithoutSecondDispatch`：RUNNING 取消完成当前题、取消剩余题，无第二 worker，计数一致。
- `shouldCancelQueuedRunInsideTransactionAndRejectRepeatedCancellation`：QUEUED 事务内收口，重复取消冲突。
- `shouldRetryOnlyRetryableFailuresAtMostThreeTimesAndContinueOtherCases`：覆盖第 2 次成功、第 3 次成功、3 次失败、4xx 一次即停与后续案例继续。
- `shouldKeepOutputUsageAndCostForScoringConfigurationErrorThenContinueAsPartial`：单案例评分配置错误保留 output/usage/cost，run PARTIAL。
- `shouldFailWholeRunForSharedTargetConfigurationErrorAndCloseEveryResult`：共享 target 配置错误导致 FAILED，当前 ERROR，剩余 CANCELLED。
- `shouldFailAndCloseEveryResultForCorruptOrUnknownSnapshotSchema`：损坏 JSON 与未知 schema 均 FAILED 且无悬空。
- `shouldFailZeroCaseRunWithoutLeavingQueuedOrRunningState`：零案例 FAILED。
- `shouldCloseCurrentAndRemainingResultsWhenIndependentStoreFails`：store 独立事务失败后当前 ERROR、剩余 CANCELLED。
- `shouldCloseAllPendingResultsWhenUnexpectedWorkerFailureOccurs`：未知 worker 异常后无 RUNNING/PENDING 悬空。
- `shouldRetryOnlyErrorResultsWithLineageAndOriginalImmutableSnapshots`：仅复制 ERROR，保留 sourceRunId、target/parameters/案例快照，旧历史不变。
- `shouldAllowOnlyOneConcurrentRetryForTheSameExpectedVersion`：同 expectedVersion 并发仅一个新 run。
- `shouldKeepManualResultsPendingThenAtomicallyApplyPassAndFailReviews`：MANUAL 执行后待审，首次 pass/fail 原子更新 result 和 run 统计，重复审核冲突。
- `shouldRejectManualScoresThatAreNotStrictZeroOrOneAndKeepStatisticsUnchanged`：严格 0/1 与 passed 一致，失败时统计不变。
- `shouldAllowOnlyOneConcurrentManualReviewAndNeverExceedTotalCount`：并发仅一次成功，统计不超 total。
- `shouldRollbackCreateAndNeverDispatchWhenCreateAuditFails`：create audit 失败整体回滚且不派发。
- `shouldRollbackQueuedCancellationWhenCancelAuditFailsWithoutDispatching`：cancel audit 失败回滚。
- `shouldRollbackRetryRevisionAndNewRunWhenRetryAuditFailsWithoutDispatching`：retry audit 失败回滚 source revision 和新 run，不派发。
- `shouldRollbackResultAndRunStatisticsWhenReviewAuditFails`：review audit 失败回滚 result 与 run 统计。
- `shouldValidateTokenAndCostAggregateLimitsBeforeMutatingCounters`：累计 token/cost 越界前验证，异常不污染计数。
- `shouldPersistLegalLongTokenAndNumericCostBoundaries`：`Long.MAX_VALUE` token 与 `999999999999.99999999` 成本上界可持久化。
- `shouldFailAndCloseRunWhenTokenAggregateOverflows`：累计 token 溢出 FAILED 收口。
- `shouldFailAndCloseRunWhenAggregateCostExceedsNumericBoundary`：累计成本越界 FAILED 收口。
- `shouldTreatSingleCaseNumericCostOverflowAsFatalFailedRun`：单案例成本越界安全落库并 FAILED 收口。
- `shouldRejectEveryMalformedTargetAndParameterSnapshotShape`：6 组参数化调用，target 与 parameters 同时拒绝 unknown/trailing/duplicate/missing/null/unknown-version。
- `shouldRoundTripVersionedSnapshotsWithoutChangingTheApplicationObjectMapper`：create/retry 同样的 codec roundtrip，专用 mapper 不污染全局配置。
- `shouldRedactEveryPromptInputExpectedAndOutputBearingDiagnosticRepresentation`：22 类 prompt/input/expected/output 载体的同一哨兵不出现于 `toString()`。

### `EvaluationRunApiTest`（8 次测试）

- `shouldExerciseCreateListDetailAndResultsWithRealPagingAndBodyBoundaries`：真实 POST create 返回 201/QUEUED 并在 10s 内轮询终态；GET list 走真实 JPA Pageable，三个 run 分两页、倒序、`totalElements=3/totalPages=2`；GET detail/results 走真实 JPA，仅 results 含 input/expected/output，list/detail 不含正文、promptBody 或原始快照 JSON。
- `shouldExerciseCancelRouteAgainstQueuedAggregate`：POST cancel 真实收口 run/result。
- `shouldExerciseRetryErrorsRouteWithLineageAndOnlyErrorSnapshot`：POST retry-errors 仅复制 ERROR 并返回 lineage。
- `shouldExerciseManualReviewRouteAndRejectRepeatedReview`：POST review 真实更新，重复请求固定 409。
- `shouldRejectAllCreateAndPagingBoundaryViolationsWithFixedSafeProblem`：覆盖 missing/null/temperature/maxTokens/timeout/page/size 边界，均固定安全 400。
- `shouldRejectMissingNullAndInvalidGovernanceBodiesWithFixedSafeProblem`：cancel/retry/review 缺失、null 与非法 score 均固定安全 400。
- `shouldReturnFixedSafe404ForEveryUnknownResourceRoute`：detail/results/cancel/retry/review 未知资源均固定安全 404。
- `shouldReturnFixedSafe409ForStaleRunAndResultVersions`：run/result 过期 expectedVersion 均固定安全 409。

上述 API 的 400/404/409 均断言 `instance=/api/evaluation/runs`，且 detail/errors 不含 path ID、input、expected、output 或 key 哨兵。七条运行路由均已命中真实 service/JPA。

### 准确验证数量与提交

- `mvn -Dtest=EvaluationRunServiceTest test`：35 tests，0 failures / 0 errors / 0 skipped。
- `mvn -Dtest=EvaluationRunApiTest,EvaluationRunServiceTest test`：43 tests，0 failures / 0 errors / 0 skipped。
- `mvn -Dtest=EvaluationDatasetApiTest,EvaluationCaseApiTest,EvaluationRunRepositoryTest test`：45 tests，0 failures / 0 errors / 0 skipped。
- `mvn test`：227 tests，0 failures / 0 errors，15 skipped。
- `git diff --check`：通过。
- 实现与测试提交：`b7bf126d5f38907f708388aa2eba13a416891765`（`修复：收口评测运行治理边界`）。
- 本轮未新增 V24，未开始 Task 5/P5，未修改 `progress.md`。

### 仅 Task 5 遗留风险

- 真实 PostgreSQL 上的高并发锁竞争、乐观锁/悲观锁异常映射与 V23 结构/约束仍需 Task 5 环境验收；本机无 Docker，因此 15 条既有 PostgreSQL/Testcontainers 条件测试跳过。
- 进程重启后对队列拒绝留下的 QUEUED，以及进程崩溃留下的 RUNNING 执行恢复，仍属于 Task 5 启动恢复器范围。

## 复审最终修复

### RED / GREEN 与行为证据

- `shouldAllowOnlyOneConcurrentClaimForTheSameQueuedRun`：RED 时两个并发 `start` 都返回 `true`（预期 1 个、实际 2 个）；GREEN 后悲观锁内只有观察到 `QUEUED` 并完成 `QUEUED -> RUNNING` 的调用返回 `true`，另一个观察到 `RUNNING` 后立即返回 `false`。
- `shouldExitDuplicateDispatcherWithoutStartingASecondTargetCall`：RED 时同一事件投递两次会让第二次 target 调用进入；GREEN 后真实双线程 dispatcher 只有一个 worker、一条当前 target 调用，重复 worker 退出。随后 RUNNING 取消时当前题 PASSED、剩余题 CANCELLED，单 run 全程串行且 `completedCount == totalCount`。
- `shouldLetRunningWorkerFinishCurrentThenCancelRemainingWithoutSecondDispatch` 与 `shouldExerciseCancelRouteAgainstQueuedAggregate`：RED 时 cancel 响应仍携带 expectedVersion；GREEN 后审计成功后显式 flush，响应 `version > expectedVersion`，并与独立 repository 读取的持久化版本完全相等，没有手工 `+1`。
- `shouldExerciseManualReviewRouteAndRejectRepeatedReview`：RED 时 review 响应 `data` 为 null，无法返回已修改 Result/run 的真实版本；GREEN 后返回 `ReviewView`，result 状态与 run 统计均为本次原子更新结果，两个响应版本均大于旧版本且等于独立落库读取。
- `shouldPreserveManualReviewWhileCancellingCurrentAndRemainingAutomatedCases`：MANUAL 首题执行后保持 `PENDING + reviewRequired`；后续自动题 RUNNING 时取消，当前题完成、剩余题取消，run 终态为 CANCELLED。CANCELLED 后仍允许唯一一次人工 review 并更新统计，重复 review 返回冲突；review 响应中的 run/result 版本均等于落库版本。
- `shouldReturnFixedSafe409ForStaleRetryErrorsVersion`：retry-errors 的过期 expectedVersion 返回固定 409，`instance=/api/evaluation/runs`，响应不回显 run ID、input、expected、output 或 key 哨兵。
- `shouldRollbackRealStoreFlushFailureThenCloseRunInIndependentFailureTransaction`：通过测试专用 H2 CHECK 约束在实体完成变更后的真实 Hibernate flush 点制造数据库失败；原 `store` 短事务回滚，随后独立 fatal 事务把当前 RUNNING 结果置 ERROR、剩余 PENDING 置 CANCELLED、run 置 FAILED，且实际输出未残留。该场景未 stub 整个 state/store。

### 准确验证数量与提交

- `mvn -Dtest=EvaluationRunServiceTest,EvaluationRunApiTest,EvaluationRunStateTest test`：57 tests，0 failures / 0 errors / 0 skipped（Service 39、API 9、State 9）。
- `mvn -Dtest=EvaluationDatasetApiTest,EvaluationCaseApiTest,EvaluationRunRepositoryTest test`：45 tests，0 failures / 0 errors / 0 skipped。
- `mvn test`：232 tests，0 failures / 0 errors，15 skipped。
- `git diff --check`：通过。
- 最终修复实现与测试提交：`d9d633760b0b51654ac4b1451a93d9dcbccd8570`（`修复：确保评测运行单次领取与版本刷新`）。
- 本轮未新增 V24，未开始 Task 5/P5，未修改 `progress.md`。

### 仅 Task 5 遗留风险

- 真实 PostgreSQL 的高并发锁调度、提交异常映射与 V23 约束仍需 Task 5 环境级验收；本机 Docker 不可用，因此 15 条既有 PostgreSQL/Testcontainers 条件测试按条件跳过。
- 队列拒绝遗留 QUEUED 和进程崩溃遗留 RUNNING 的启动恢复仍属于 Task 5 恢复器范围。
