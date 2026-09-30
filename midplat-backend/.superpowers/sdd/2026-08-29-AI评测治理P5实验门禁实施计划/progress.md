# SDD ledger — plan: docs/superpowers/plans/2026-08-29-AI评测治理P5实验门禁实施计划.md

## 执行前接口扫描

| 任务/接口关系 | 生产者 | 消费者 | 扫描结论 |
| --- | --- | --- | --- |
| 终态运行与结果只读视图 | P3 `evaluation.run` 保存运行快照、聚合统计和结果 | Task 1 实验对比、Task 3 门禁决策、Task 4 总览 | 不向新包开放写仓储；增加最小只读端口或显式安全投影，比较响应不得包含输入、期望或完整输出 |
| 数据集一致性 | P3 `target_snapshot_json.datasetSnapshotHash` 与结果 `snapshot_content_hash` | Task 1 比较前置校验 | 比较必须使用运行时冻结快照哈希；`datasetVersionId` 相同不等于快照相同，哈希不一致返回 409 |
| 人工复核当前态 | P3 `PENDING + reviewRequired=true` 表示当前待审；复核后标志为 false | Task 1 分类、Task 3 门禁优先级、P4 展示 | 只有当前待审才产生 `REVIEW_REQUIRED`；已复核的 PASSED/FAILED 必须参与正常比较与门禁 |
| 比较纯算法 | Task 1 产生确定性分类、排序和四类指标差值 | Task 3 复用比较证据、P4 对比页 | 不持久化可变实验；按 caseId/快照内容对齐，固定分类优先级和稳定排序，数值使用落库统计 |
| 门禁持久化 | Task 2 产生 V24、策略和追加式决策 | Task 3 策略 API 与决策、Task 4 总览、P4 门禁页 | 策略可乐观锁更新；决策不可修改/删除，保存策略快照、证据和决定时间 |
| 门禁算法 | Task 1 比较结果 + Task 2 策略 | Task 3 生成 `PASS/FAIL/REVIEW_REQUIRED` | 已确定高风险失败优先于待审；否则执行错误/待审需复核；再检查通过率、成本、平均/P95 阈值 |
| 安全证据 | P3 结果含敏感输入、期望和输出正文 | Task 1 比较摘要、Task 3 evidence JSON、审计 | 响应/证据只允许安全摘要、结果 ID、案例 ID/名称与聚合数字；禁止正文、token/API key 和原始错误体 |
| 治理总览 | P1/P2/P3/P5 仓储统计 | Task 4 overview API、P4 总览 | 通过最小读端口聚合，空库全零；最近终态通过率取已落库运行统计，退化数取最近有效比较/决策的确定证据 |
| 菜单与路由 | V25 菜单迁移、`RouteCatalog` | P4 `/evaluation` 一级入口 | 菜单在 AI 工作台和开发中心之间，迁移幂等且路由目录锁定，前后端路径一致 |

Ruling: P5 只能通过 `evaluation.run` 提供的最小只读端口读取终态运行与安全结果投影，不给 `evaluation.experiment` 或 `evaluation.gate` 直接写 P3 聚合的能力 — 保持运行历史不可覆盖并缩小敏感正文暴露面 — 若判断错误，成本是一个映射层，但可以在端口测试中集中证明脱敏。

Ruling: 比较按 `caseId` 对齐并要求两个运行由各自按顺序结果 `caseId + snapshotContentHash` 重建的冻结 `datasetSnapshotHash` 完全一致；不得读取当前可变 `dataset_version.snapshot_hash`。缺项、重复项或快照内容哈希不一致视为冲突而不是默认为退化 — 避免不同样本集合产生虚假质量结论 — 若判断错误，旧数据需先治理后才能比较，但不会生成误导性发布证据。

Ruling: 分类优先级固定为执行错误 `ERROR`、当前待审 `REVIEW_REQUIRED`，其余再按 PASSED/FAILED 与 score 比较；只有分数差不足以跨越通过状态时才用于同状态细分 — 保证通过→失败必定退化、失败→通过必定改进 — 若判断错误，精细分数变化可能归为持平，但发布结论不会反转。

Ruling: 指标差值统一为候选减基线；成本使用 `BigDecimal`，延迟使用溢出安全的长整型差值或明确受控数值；质量通过率用已落库 passed/failed 的可判定样本，不把 ERROR/CANCELLED/当前待审当失败悄悄混入 — 使门禁优先级与展示含义一致 — 若判断错误，前端需要显式展示未判定数量，但不会掩盖执行异常。

Ruling: 门禁决策一经保存不可更新或删除；重复请求允许生成新的追加决策并各自冻结策略版本/快照，而不覆盖旧决策 — 支持同一运行在人工复核或策略更新后重新决策且保留审计链 — 若判断错误，会产生多条历史记录，但查询按 `decidedAt/id` 稳定排序即可解释。

Ruling: Task 3 的策略选择语义锁定为请求显式传入唯一 `policyId`；不在多个 `enabled` 策略间做隐式优先级或自动命中。显式策略仍必须和 Task 1 安全投影中的候选平台/分类匹配：`null` 是通配，非空平台精确匹配，非空分类要求候选的非空全部案例分类均一致 — 避免全运行级聚合指标被混合分类冒充为分类策略证据 — 若后续增加自动命中，仍需另行定义精确匹配、通配和冲突优先级。

Ruling: 候选中已确定的 `CRITICAL + FAILED` 是不可关闭的安全硬底线，始终高于 ERROR/当前待审和数值阈值；`requireCriticalCasesPassed` 仅保留为 V24 冻结兼容元数据 — 防止旧字段使安全底线变成可选项 — 若未来清理该字段，必须通过新迁移和版本化 API 完成。

Ruling: 门禁决策 coordinator 不开启外层事务，必须通过 Spring 代理调用 Task 1 的只读 `REPEATABLE_READ` 比较，比较完成后再进入独立 writer 的可写事务原子追加 decision + audit — 防止长事务混合快照和写入，也避免 self-invocation 让隔离级别失效。

Ruling: Task 1 比较为候选无正文状态生成 `@JsonIgnore` SHA-256 指纹；writer 在可写事务中依次锁定 candidate run、按 `orderNo/id` 排序的全部 result、显式策略，再从同一安全物理列投影重建指纹并复查策略版本、启用态和作用域，之后才追加 decision + audit — 同时关闭人工复核以及策略停用/升版发生在 compare 与 append 之间的陈旧决策窗口 — 锁顺序与人工复核的 run→result 一致，PostgreSQL 双向交错证明先提交方决定后续是确定冲突或串行等待。

Ruling: Task 2 仅创建 V24，Task 4 才创建 V25；P3 的 V23 已明确未发布边界，后续不得再原位修改 V23 — 保持 Flyway 序列和部署契约清晰 — 若判断错误，需要额外迁移，但不会制造 checksum 漂移。

## 任务进度

- P5 整体：进行中。
- Task 1：已完成。新增只读 `EvaluationRunComparisonReader` 安全投影与 `evaluation.experiment` 纯比较服务/API；运行冻结哈希仅由安全结果投影按 `orderNo` 重建，已覆盖终态、快照、案例一一对齐、人工复核、五分类、精确差值、溢出和脱敏契约。PG 16.14 实测比较 worker 的全部 Hibernate statement，再以保守整句 SQL/单项物理列 allowlist 验证；前导注释 SELECT 可规范化，CTE/非 SELECT/第五条 statement 失败，并覆盖受控复核并发及正常和投影后异常的同 worker `READ_COMMITTED` 恢复；当前证据见 `task-1-report.md`。
- Task 2：已完成。新增 V24 策略与不可变决策表、`evaluation.gate` 聚合和 append-only 仓储；策略按可选平台/分类作用域保存精确阈值，以完整 draft 验证后按期望版本原子修订。决策仅从 `EvaluationGatePolicy + EvidenceSnapshot` 追加并冻结策略版本、白名单策略快照、白名单证据和决定时间；无原始 JSON 业务入口，codec 限制原始/UTF-8 字节、规范化 record 并拦截凭据形态。V24 同构拒绝 NaN、越界和控制空白。H2 12/12、PostgreSQL 16.14 随机 schema V1–V24 专项 5/5、P1–P3+Task1 关键回归 221/221、fresh 全量 314 found = 275 executed + 39 条件跳过均通过；策略 ready/release 并发恰一成功且失败方为乐观锁类型，已提交及并发重复决策 ID 为确定 `DataIntegrityViolationException`，schema 零残留。证据见 `task-2-report.md`。
- Task 3：已完成；首次独立审查 FAIL（0 Critical / 6 Important）和第二轮独立复审 FAIL（0 Critical / 2 Important）的历史结论均已保留并逐项闭环。策略 API 仅开放 GET/POST/PATCH，PATCH 强制 `expectedVersion`；写请求使用生产 MVC 严格 JSON 解析，元数据与 evidence 共用安全守卫并先拒绝 Unicode 隐形/方向控制。决策必须显式选择唯一启用 `policyId`，并校验候选平台/全案例分类作用域；每次请求追加新历史。算法按不可关闭的 CRITICAL 已确定失败、执行错误/当前待审、质量/成本/平均/P95 阈值的固定优先级执行，使用 Task 1 隐藏安全投影，不根据输出摘要判定。无事务 coordinator 经 Spring 代理进入只读 `REPEATABLE_READ` 比较；独立 writer 按 run→results→policy 固定顺序持锁、重建内部候选指纹并原子写入决策与审计，拒绝 compare 后发生人工复核的陈旧结论。第三轮整改（第二轮独立复审）后定向 125 found = 112 executed + 13 条件跳过，关键回归 277/277，Task 1 + Task 3 + 无 mock 真链路 PostgreSQL 16.14 随机 schema V1–V24 专项 15/15，fresh 全量 378 found = 331 executed + 47 条件跳过，全部 0 failure/error；JAR 打包成功，随机 schema 零残留。证据见 `task-3-report.md`。P5/发布门禁阶段总体仍进行中，Task 4 未开始。
- Task 4：待开始。
