# Task 3：数据集 REST 契约 — 执行报告

## 变更摘要

- 新增 `EvaluationDatasetController`，以既有 `ApiResponse` 包装和 `ProblemDetail` 异常模型开放数据集、版本编排、冻结与派生 HTTP 接口。
- 新增基于真实 Spring、JPA 与 MockMvc 的 `EvaluationDatasetApiTest`。测试经 HTTP 创建并审核两个案例、创建数据集、按指定顺序替换 V1 条目、冻结、验证冻结版本拒绝修改，并派生保持条目顺序的 V2；同时直接读取持久化聚合和冻结快照验证真实状态。
- 控制器边界校验名称（非空、最多 128）、说明（最多 1,000）、条目列表（非空、最多 1,000）与每个条目 ID（非空白）。服务层继续处理重复条目为既有统一的 HTTP 400。
- 所有已有资源的写操作请求均要求 `expectedVersion`：派生使用数据集版本，替换条目和冻结使用版本聚合版本。服务层的 `ConflictException` / JPA 锁冲突继续由既有全局异常映射为 HTTP 409。

## 路由与 DTO 契约

| 方法 | 路径 | 请求体 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/api/evaluation/datasets` | — | `ApiResponse<List<DatasetSummaryView>>` |
| GET | `/api/evaluation/datasets/{id}` | — | `ApiResponse<DatasetView>` |
| POST | `/api/evaluation/datasets` | `{name, description?}` | HTTP 201，`ApiResponse<DatasetView>`，自动含 V1 草稿 |
| POST | `/api/evaluation/datasets/{id}/versions` | `{expectedVersion}` | `ApiResponse<DatasetVersionView>` |
| PUT | `/api/evaluation/dataset-versions/{id}/items` | `{expectedVersion, caseIds}` | `ApiResponse<DatasetVersionView>` |
| POST | `/api/evaluation/dataset-versions/{id}/freeze` | `{expectedVersion}` | `ApiResponse<DatasetVersionView>` |

不存在数据集或版本时，既有服务使用固定详情“评测数据集不存在”或“评测数据集版本不存在”，`instance` 固定为 `/api/evaluation/datasets`，不回显外部 ID。校验失败为 400，过期 `expectedVersion` 和冻结后的写入为 409。

## RED / GREEN 证据

### RED

先仅新增 HTTP 契约测试并运行：

```bash
mvn -Dtest=EvaluationDatasetApiTest test
```

结果：`BUILD FAILURE`，3 项测试均因 `POST /api/evaluation/datasets` 尚未注册而获得 404（`No static resource api/evaluation/datasets`）。红灯由缺少目标控制器路由触发，而非测试装配错误。

### GREEN

实现最小控制器和请求 DTO 后，重跑定向测试：

```bash
mvn -Dtest=EvaluationDatasetApiTest test
```

结果：3 项执行通过，0 失败、0 错误、0 跳过。

## 测试命令与数量

```bash
mvn -Dtest=EvaluationDatasetApiTest test
mvn -Dtest=EvaluationDatasetApiTest,EvaluationCaseApiTest test
mvn test
git diff --check
```

- 数据集 API 定向：3 项通过。
- 与 P1 案例 API 联合回归：23 项通过。
- 全量回归：97 项执行，0 失败、0 错误、10 项条件式 PostgreSQL/Testcontainers 套件跳过（本机 Docker 不可用）。
- `git diff --check` 通过。

## 提交哈希

- 代码提交：`9c443d8 功能：开放评测数据集版本接口`。

## 遗留风险

- 本任务的 HTTP 契约在 H2 PostgreSQL 模式验证；真实 PostgreSQL 的数据集版本并发与约束专项验证仍属于 Task 4。由于本机 Docker 不可用，条件式 Testcontainers 套件按设计跳过。
- 数据集名写入既有白名单审计摘要，案例正文、期望和冻结快照不经控制器写入日志；审计脱敏与事务边界沿用 Task 2 已有实现。
