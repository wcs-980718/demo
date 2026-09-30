# Task 2 — 确定性评分器报告

## 契约

- `EvaluationEvaluatorRegistry.evaluate(EvaluatorType, expectedJson, actualOutput)` 是无网络、无时间和无随机依赖的纯确定性入口；结果为 `EvaluationOutcome(score, passed, reviewRequired, reasonCode, reason, errorCode)`。
- 自动评分成功或失败始终为 `score=1/0` 与相应 `passed=true/false`，并且 `reviewRequired=false`。`MANUAL` 仅验证期望值为 JSON object，返回 `score/passed=null`、`reviewRequired=true`，不读取输出。
- 所有非法期望配置（JSON 或类型错误、缺少字段、非法范围、非法点分 `jsonPath`）均返回 `EVALUATOR_CONFIG_INVALID`，不抛出且不包含正文；实际输出为 `null` 的自动评分返回 0 与 `ACTUAL_OUTPUT_MISSING`。
- `EXACT` 以 Unicode 空白折叠并去首尾后比较；`CONTAINS_ALL` 与 `FORBIDDEN_TERMS` 只在理由中给计数；`JSON_VALID` 只检查根对象字段；`NUMERIC_RANGE` 支持点分标识符路径，或从文本取得第一个完整的正负小数/科学计数数字，边界包含。
- 所有 reason/error 都是固定代码或固定模板加计数；不复制期望、输出、异常信息或日志内容。

## RED / GREEN

- RED：先新增 `EvaluationEvaluatorRegistryTest`，执行 `mvn -Dtest=EvaluationEvaluatorRegistryTest test`；因 `EvaluationEvaluatorRegistry` 和 `EvaluationOutcome` 尚不存在，testCompile 按预期失败。
- GREEN：新增无状态注册表、函数式评分接口与安全结果 record 后，定向测试通过。

## 测试证据

- `mvn -Dtest=EvaluationEvaluatorRegistryTest test`：11 tests，0 failures / 0 errors。
- `mvn test`：153 tests，0 failures / 0 errors，15 skipped（已有 PostgreSQL/Testcontainers 条件跳过）。
- `git diff --check`：通过。

测试覆盖 EXACT 的 Unicode 空白、CONTAINS_ALL 缺失片段、JSON 非法/根非对象/字段缺失、NUMERIC_RANGE 的嵌套路径/边界/负数/小数/科学计数/非法路径、禁词命中、MANUAL、空输出、配置错误不抛出、敏感正文不泄漏以及并发重复调用一致性。

## 提交

- 代码提交：`824a632`（`功能：实现评测确定性评分器`）。

## 遗留风险

- 点分 `jsonPath` 只接受 ASCII 标识符段，不支持数组下标或 JSON Pointer；这是当前明确且可测试的安全契约。
- 评分器不负责 HTTP 执行、持久化状态映射或重试；Task 4 应将 `EVALUATOR_CONFIG_INVALID` 映射为不可重试的结果 `ERROR`，并将 `MANUAL_REVIEW_REQUIRED` 保持为待人工复核。

## 审查修复

### 严格 JSON 与边界裁决

- 评分器持有私有 `ObjectMapper` 副本，启用 `FAIL_ON_TRAILING_TOKENS` 与 `STRICT_DUPLICATE_DETECTION`；所有 expectedJson 均经同一严格入口解析。尾随垃圾、多根 JSON、重复对象字段、未知字段和 MANUAL 的非空 object 均为 `EVALUATOR_CONFIG_INVALID`。
- JSON_VALID 与有 `jsonPath` 的 NUMERIC_RANGE 实际输出同样严格解析；解析失败仅产生自动评分失败，绝不升级为配置错误或回显正文。requiredFields 为空时任意严格合法 JSON 通过；非空时必须是根 object 且字段存在、值非 null。
- 三类字符串数组拒绝重复项、null/非字符串、空串及 Unicode 空白（含 NBSP）。NUMERIC_RANGE 的路径仅为点分安全对象字段，数组路径和数字字符串叶子均不接受；无路径才使用原始文本首个完整数提取。
- `EvaluationOutcome` 已由 public record 改为 final class + private constructor。公开面仅保留只读访问、equals/hashCode/toString；包内工厂只接收固定枚举原因和非负计数，并校验自动通过/失败与原因码匹配，因此没有公共构造或任意 reason 文本注入通道。

### RED / GREEN

- RED：新增严格 expected/actual JSON、未知字段、重复数组项/NBSP、required null、数字字符串/数组路径、全类型 null output、Outcome 公共构造与非法结构化组合测试。运行定向测试得到 6 个失败，随后 Outcome 不变量测试额外确认当前工厂错误接受配置错误作为自动通过原因。
- GREEN：实现私有 mapper 副本、严格 schema 校验和封闭 Outcome 后，`EvaluationEvaluatorRegistryTest` 通过 19 项。

### 验证与提交

- `mvn -Dtest=EvaluationEvaluatorRegistryTest test`：19 tests，0 failures / 0 errors。
- `mvn test`：161 tests，0 failures / 0 errors，15 skipped（既有 PostgreSQL/Testcontainers 条件跳过）。
- `git diff --check`：通过。
- 审查修复提交：`7aa1f02`（`修复：收紧评测评分器安全契约`）。

### 剩余风险

- 结果对象通过正常 Java API 无法构造非法形状；反射可绕过任何 Java 私有边界，属于 JVM 通用信任模型，不构成该领域 API 的输入面。

## 第二轮审查修复

### BigDecimal 语义与错误分类

- 私有 `ObjectMapper` 副本增加 `USE_BIG_DECIMAL_FOR_FLOATS` 和 `USE_BIG_INTEGER_FOR_INTS`，且来源 mapper 的三个数值/严格特性在构造前后保持不变。
- Jackson Tree 对极大指数仍可能生成 `DoubleNode(Infinity)`；因此严格 Tree 只承担 JSON 结构、尾随 token 和重复字段校验。NUMERIC_RANGE 的 expected `min/max` 与实际 `jsonPath` 数值改由严格流式 parser 的原始 number token 直接构造 `BigDecimal`，不经过 `double`。原始文本首数也以 `BigDecimal(String)` 比较，三种路径统一精确语义。
- expected 数字无法解析仍是配置错误；actual JSON 无效、路径缺失、叶子非数字或数值不可转换均返回自动失败 `NUMERIC_NOT_FOUND`，不抛出且不含正文。`1e400` 可作为精确 JSON 数与大指数范围比较。

### RED / GREEN

- RED：高精度 `0.100000000000000005` 在 JSON path 与 raw 比较均因 Double 近似失败；`1e400` 被错误映射为配置错误；注入 source ObjectMapper 的精确数测试同样失败。
- GREEN：启用私有 mapper 数值特性并从流式 token 构造 BigDecimal 后，等值边界、下一位超界、`1e400`、`.5`、负号、带符号指数、不完整指数和超大不可转换实际数均符合安全契约。

### 验证与提交

- `mvn -Dtest=EvaluationEvaluatorRegistryTest test`：25 tests，0 failures / 0 errors。
- `mvn test`：167 tests，0 failures / 0 errors，15 skipped（既有 PostgreSQL/Testcontainers 条件跳过）。
- `git diff --check`：通过。
- 第二轮修复提交：`c4973d9`（`修复：保持评分器数值精度`）。

### 剩余风险

- 流式 number token 的指数范围仍受 JDK `BigDecimal` 指数上限约束；实际值超过该上限稳定降级为自动失败，期望值超过上限稳定判为配置错误。
