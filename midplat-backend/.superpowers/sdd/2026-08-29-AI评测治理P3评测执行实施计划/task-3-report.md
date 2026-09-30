# Task 3 报告：目标快照与 OpenAI 兼容执行端口

## 变更

- `PromptService.requireEvaluationSnapshot(id)` 返回不可变 `EvaluationPromptSnapshot`：保存 ID、名称、版本、正文和 UTF-8 SHA-256；正文被后续编辑不会改变已取得的快照。
- 新增 `evaluation.target` 端口、无密钥请求/响应、固定安全异常、持久化安全的轨迹摘要，以及 `OpenAiEvaluationTargetExecutor`。
- 请求只含已冻结的模型端点/模型名/Prompt/输入/参数和模型 ID；执行瞬间通过 `ModelService.requireCurrentApiKey(modelId)` 读取当前凭据。

## 协议与安全边界

- 只接受无 user-info、无 query/fragment 的 HTTP(S) 基础 URL；固定拼接 `/chat/completions`，不接受调用方提供任意路径。温度范围 `0..2`、`maxTokens <= 32768`、`timeoutMs <= 120000`。
- 执行器发送 `POST` JSON（`model`、system/user messages、`temperature`、`max_tokens`）和当次 `Authorization: Bearer`，不记录请求或响应正文，也不将 API Key 写入请求、响应或轨迹对象。
- 2xx 响应采用启用重复字段检测和尾随 token 拒绝的 Jackson 解析；强制 `choices[0].message.content` 为字符串，`usage` 的 prompt/completion 为非负长整型，缺 `total_tokens` 时计算，存在但不等于两者之和则拒绝。成功响应最大读取 2 MiB。
- 请求/响应的 `toString()` 均把 Prompt、输入和输出标记为 `[redacted]`；异常仅含固定 `code`、`retryable` 和可选 status，禁用 cause/suppression/stacktrace 以避免通过异常链回显外部正文或凭据。

## 重试分类

| 情形 | code | retryable |
| --- | --- | --- |
| 模型不存在、空密钥、配置读取失败 | `CONFIGURATION_ERROR` | 否 |
| 3xx 或任意 4xx（含 408 / 429） | `UPSTREAM_HTTP_ERROR` | 否 |
| 5xx | `UPSTREAM_HTTP_ERROR` | 是 |
| 连接失败或 I/O | `NETWORK_ERROR` | 是 |
| 调用超时 | `TIMEOUT` | 是 |
| 响应超限、畸形、尾随或重复 JSON、字段/用量不合约 | `INVALID_RESPONSE` | 否 |
| 中断 | `INTERRUPTED`（恢复线程中断标志） | 否 |

执行器不在本层自动重试；Task 4 应按 `retryable` 最多额外尝试两次。

## RED / GREEN

- RED：先新增 JDK `HttpServer` 集成测试和 Prompt 快照测试，执行 `mvn -Dtest=OpenAiEvaluationTargetExecutorTest,PromptServiceEvaluationSnapshotTest test` 在 test-compile 阶段因 21 个缺失类型/方法失败，确认测试覆盖的是尚未存在的端口和快照接口。
- GREEN：实现最小快照读取和 HTTP 执行器后，同一命令通过；随后补充 token 缺省、超限响应、模型不存在、中断、重复字段及安全输出回归。

## 测试证据

- `mvn -Dtest=OpenAiEvaluationTargetExecutorTest,PromptServiceEvaluationSnapshotTest test`：11 tests，0 failures / 0 errors。
- `mvn test`：178 tests，0 failures / 0 errors，15 skipped（已有本地 PostgreSQL 条件测试）。
- `git diff --check`：通过。

## 提交

- 代码提交：`93d18b39127c9c9d27b7ebc1f5f380c72167aa93`（`功能：增加评测目标快照执行器`）。

## 风险与后续

- 本任务仅提供一次调用端口；运行级串行、最多两次重试、结果持久化和成本计算仍由 Task 4 负责。
- 端口以 OpenAI Chat Completions 响应结构为契约；不兼容该结构的供应商需要独立适配器，不能放宽当前严格解析规则。

## 审查修复：正文截止时间、取消与快照凭据裁决

### 根因与修复

- 原实现使用 `BodyHandlers.ofInputStream()`；`HttpClient.send()` 在响应头和 `InputStream` 就绪后即可返回，后续同步正文读取不再受到 `HttpRequest.timeout` 约束，也不能可靠响应调用线程中断。
- 改为 `sendAsync` 加自定义有界 `BodyHandler`/`BodySubscriber`。2xx 的 subscriber 增量拷贝 `ByteBuffer`，最多 2 MiB；声明超限或 chunked 流累计跨限时只终结一次、取消 `Subscription`，并以内部无正文失败映射为固定 `INVALID_RESPONSE`。非 2xx 使用丢弃 subscriber，不保存错误正文。
- 从发送请求开始计算绝对截止时间，使用 `future.get(remaining)` 同时覆盖建连、响应头和完整正文；`TimeoutException` 显式 `future.cancel(true)` 后返回可重试 `TIMEOUT`。调用线程中断时同样取消 future、恢复中断标志并返回不可重试 `INTERRUPTED`。正文中途断连的 subscriber 失败被映射为可重试 `NETWORK_ERROR`，底层 cause 和半截正文均不对外暴露。

### 新增真实 HttpServer 证据

- 先发 headers 后停顿正文、chunked 小块慢滴流均在 deadline 内返回 `TIMEOUT`；不再等服务器的延迟正文结束。
- 正文已开始读取时中断工作线程，worker 在 500ms 内结束，`INTERRUPTED` 保留中断标志且不可重试。
- chunked 且未知 `Content-Length` 的正文超过 2 MiB 时被 subscriber 取消并安全失败；固定长度和未知长度两条路径均受限。
- 声明更长正文后中途关闭连接得到 `NETWORK_ERROR`，没有回显半截正文。

### URL 矩阵与规格裁决

- 仅允许 HTTP(S)、host 存在、无 user-info/query/fragment、端口为默认 `-1` 或 `1..65535`、且没有 `.`/`..` 或百分号编码路径的 base URL。端口 `0`、超界端口、dot segment 与编码路径均在构造期固定为 `CONFIGURATION_ERROR`，不会进入网络或解析分支。
- 已同步主规格 §11.1：每个尚未发出的案例仅在即将调用外部目标时按 model ID 读取当前 API Key。运行快照仍永不含密钥；因此密钥轮换会影响同一运行中后续未发出的案例。这与 Task 3 明确契约和 P3 ledger 一致，Task 4 不得在运行开始时缓存凭据。

### RED / GREEN 与验证

- RED：新增 headers 后停顿、慢滴流、中断、chunked 超限、半途断连及 URL 矩阵测试后，`mvn -Dtest=OpenAiEvaluationTargetExecutorTest test` 为 16 tests，4 failures、1 error：两种正文延迟错误地成功，中断 worker 超时，且 URL 仍抛无 code 的 `IllegalArgumentException`。
- GREEN：替换读取路径、统一 absolute deadline、取消 future/subscription 和构造期 `CONFIGURATION_ERROR` 后，定向目标执行器 16 tests 通过；带 Prompt 快照的定向组合 17 tests 通过。
- `mvn test`：184 tests，0 failures / 0 errors，15 skipped；`git diff --check` 通过。

### 提交与残余线程/资源风险

- 审查修复代码提交：`70bb37b2fc3b91d96dc93952938579b7a48728da`（`修复：收紧评测目标正文超时`）。
- `future.cancel(true)` 加上 subscriber `Subscription.cancel()` 防止 Task 4 的有限 worker 因永不结束的正文长期占用；底层 HTTP 实现的连接回收由 JDK `HttpClient` 管理。每次失败均丢弃 bounded buffer 引用，最长只会暂存 2 MiB，且没有可达的正文/cause 进入异常、日志或轨迹。
