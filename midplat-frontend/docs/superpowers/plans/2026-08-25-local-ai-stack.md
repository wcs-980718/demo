# Local AI Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 执行中台前端 `npm run dev` 时自动启动并核验四个本地 AI 项目及中台自身，使所有本地入口使用固定地址可访问。

**Architecture:** 在中台前端创建 Node.js 进程监管模块，以声明式服务清单生成启动环境、检查端口、复用健康服务、记录自有 PID 并集中写日志。问数项目继续复用现有 screen 栈，只把硬编码 Agent 端口参数化；其他项目不修改用户业务代码，通过环境变量覆盖端口和代理地址。

**Tech Stack:** Node.js 18+、ES modules、node:test、Bash、lsof、curl、screen、Umi/Vite、Spring Boot/Maven。

**Spec:** `docs/superpowers/specs/2026-08-25-local-ai-stack-design.md`

## Global Constraints

- 不建设端口管理页面或端口配置表。
- 固定使用设计文档中的端口；不能冲突时随机改端口。
- 只停止监管模块本次创建的进程，不终止启动前已有的服务。
- 知识库、标注和智能体平台的现有用户修改必须保留。
- 根因分析与鱼骨图继续使用远程地址，不启动本地 `close_loop`。
- 日志不得输出数据库密码、API Key 或完整敏感环境变量。
- 每次提交只暂存当前任务文件，不使用 `git add .` 或 `git commit -a`。

---

### Task 1: Parameterize the Indicator Agent Port

**Files:**
- Modify: `agent 接入管理indicator/agent/scripts/local-stack.sh`
- Modify: `agent 接入管理indicator/agent/scripts/local-stack-service.sh`
- Create: `agent 接入管理indicator/agent/scripts/test-local-stack-ports.sh`

**Interfaces:**
- Consumes: optional `AGENT_PORT`, default `8000`.
- Produces: all Agent health checks and frontend targets derived from `AGENT_PORT`.

- [ ] **Step 1: Write a failing shell contract test**

The test reads both scripts and fails unless:

```bash
AGENT_PORT="${AGENT_PORT:-8000}"
```

is declared, `SERVER_PORT` uses the variable, both frontend target variables use it, and status/start/port-conflict loops do not hardcode Agent port 8000.

- [ ] **Step 2: Run the shell test and verify RED**

Run `bash scripts/test-local-stack-ports.sh`. Expected: hardcoded 8000 assertion failure.

- [ ] **Step 3: Implement minimal parameterization**

Export `AGENT_PORT` from the orchestrating script into screen sessions. Replace only Agent-specific 8000 occurrences; do not alter unrelated ports or remote database validation.

- [ ] **Step 4: Run syntax and contract tests**

Run:

```bash
bash -n scripts/local-stack.sh
bash -n scripts/local-stack-service.sh
bash scripts/test-local-stack-ports.sh
AGENT_PORT=8002 bash scripts/local-stack.sh status
```

- [ ] **Step 5: Commit in the indicator repository**

Stage only the two scripts and contract test. Commit message: `feat: parameterize local indicator agent port`.

---

### Task 2: Declarative Local Service Catalog

**Files:**
- Create: `midplat-frontend/scripts/dev-stack/services.mjs`
- Create: `midplat-frontend/tests/devStackServices.test.mjs`

**Interfaces:**
- Produces: `createServiceCatalog(workspaceRoot, env)`.
- Each service record contains `id`, `label`, `cwd`, `port`, `command`, `args`, `env`, `healthUrl`, `requiredCommands`, and `kind`.

- [ ] **Step 1: Write failing catalog tests**

Assert all 12 fixed ports, exact project directories, frontend proxy environment values, the four expected local URLs, and absence of any `close_loop` service.

- [ ] **Step 2: Run test and verify RED**

Run `node --test tests/devStackServices.test.mjs`. Expected: module missing.

- [ ] **Step 3: Implement the catalog**

Use `process.execPath` for Node-launched scripts. Resolve Maven as `env.MAVEN_BIN || 'mvn'` and Yarn as `env.YARN_BIN || 'yarn'`. Commands must be argument arrays, never interpolated shell strings.

Required environment mappings:

```js
annotationBackend.env.SERVER_PORT = '8081';
annotationFrontend.env.API_TARGET = 'http://127.0.0.1:8081';
knowledgeBackend.env.SERVER_PORT = '8082';
knowledgeFrontend.env.VITE_API_TARGET = 'http://127.0.0.1:8082';
agentManagerFrontend.env.PORT = '8001';
agentManagerFrontend.env.API_PROXY_TARGET = 'http://127.0.0.1:8741';
indicatorStack.env.AGENT_PORT = '8002';
```

- [ ] **Step 4: Run catalog tests**

Expected: all pass.

- [ ] **Step 5: Commit catalog and tests**

Commit message: `feat: define local AI service catalog`.

---

### Task 3: Process Ownership and Health Classification

**Files:**
- Create: `midplat-frontend/scripts/dev-stack/runtime.mjs`
- Create: `midplat-frontend/tests/devStackRuntime.test.mjs`

**Interfaces:**
- Produces `classifyService(service, adapters)` returning `healthy-existing`, `free`, or `occupied-unhealthy`.
- Produces `readOwnedState`, `writeOwnedState`, `startService`, `stopOwnedService`, and `waitForHealth`.

- [ ] **Step 1: Write failing behavior tests**

Use injected adapters rather than real ports. Cover free port, healthy existing process, unhealthy occupied port, spawn metadata, state file ownership, unknown PID protection, and redaction of keys matching `/password|secret|token|api.?key/i`.

- [ ] **Step 2: Run tests and verify RED**

Run `node --test tests/devStackRuntime.test.mjs`. Expected: module missing.

- [ ] **Step 3: Implement runtime helpers**

Spawn regular services with `detached: true`, `stdio` directed to per-service log files, and process groups so owned children can be terminated without broad `pkill` patterns. Store `{ id, pid, startedAt, commandHash }`; before stop, require both a live PID and matching command metadata.

- [ ] **Step 4: Implement health polling**

Use TCP port presence first and optional HTTP URL second. A timeout returns a structured failure with log path; it must not throw away the statuses of other services.

- [ ] **Step 5: Run runtime and catalog tests**

Expected: all pass with no leaked child processes.

- [ ] **Step 6: Commit runtime module**

Commit message: `feat: supervise owned local AI processes`.

---

### Task 4: Dev Stack Commands

**Files:**
- Create: `midplat-frontend/scripts/dev-stack.mjs`
- Modify: `midplat-frontend/package.json`
- Modify: `midplat-frontend/.gitignore`
- Create: `midplat-frontend/tests/devStackCli.test.mjs`

**Interfaces:**
- CLI commands: `start`, `status`, `stop`, with `start` as default.

- [ ] **Step 1: Write failing CLI tests**

Test argument parsing, dependency failure messages, environment file loading without logging values, start ordering (backends before frontends), partial failure summary, and signal cleanup selection. Use injected catalog/runtime adapters; do not start real services in unit tests.

- [ ] **Step 2: Run test and verify RED**

Run `node --test tests/devStackCli.test.mjs`. Expected: CLI module missing.

- [ ] **Step 3: Implement CLI orchestration**

Start central backend and local AI backends first, then frontends. Invoke the indicator stack as one managed external stack with `AGENT_PORT=8002`; when all four indicator ports were free before start, mark the stack as owned and call its `stop` action during cleanup.

- [ ] **Step 4: Add package scripts**

Set exact scripts:

```json
"dev": "node scripts/dev-stack.mjs start",
"dev:portal": "max dev",
"dev:status": "node scripts/dev-stack.mjs status",
"dev:stop": "node scripts/dev-stack.mjs stop"
```

Ignore `.dev-runtime/`.

- [ ] **Step 5: Run all Node tests**

Run `node --test tests/*.test.cjs tests/*.test.mjs`. Expected: all pass.

- [ ] **Step 6: Commit CLI**

Commit message: `feat: start local AI stack with portal`.

---

### Task 5: Stable Local Entry Configuration

**Files:**
- Create: `midplat-backend/src/main/resources/db/migration/V17__align_local_project_entries.sql`
- Modify: `midplat-frontend/src/agentPlatformConfig.ts`
- Create: `midplat-backend/src/test/java/com/yiwei/midplat/platform/LocalPlatformEntryConfigTest.java`
- Modify: `midplat-frontend/tests/developerCenterMenu.test.cjs`

**Interfaces:**
- Produces the four stable URLs from the design.

- [ ] **Step 1: Write failing backend and frontend URL tests**

Assert exact smart-query, knowledge, annotation, and agent-platform URLs. Also assert root-cause URLs are not changed by this migration/config.

- [ ] **Step 2: Run tests and verify RED**

Expected: smart-query currently points at 8086 and agent platform is not configured to 8001.

- [ ] **Step 3: Implement migration and agent config**

Update only `plat-qa`, `plat-kb`, and `plat-an` entry URLs. Set the development default iframe URL to `http://127.0.0.1:8001/appMarket`, preserving an environment override for deployments.

- [ ] **Step 4: Run focused and full tests**

Run focused Maven and CJS tests, then full backend and frontend suites.

- [ ] **Step 5: Commit stable URLs**

Commit backend and frontend changes separately because they are separate repositories. Use message `feat: align managed local project entries` in each repository.

---

### Task 6: Real Stack Verification

**Files:**
- No production files unless a failing verification produces a regression test first.

**Interfaces:**
- Verifies `npm run dev`, `npm run dev:status`, and `npm run dev:stop`.

- [ ] **Step 1: Record the pre-existing port snapshot**

Run `npm run dev:status` and `lsof` for all fixed ports. Record which services already exist so cleanup ownership can be verified.

- [ ] **Step 2: Start the stack**

Run `npm run dev` in a PTY. If `LOCAL_STACK_ENV_FILE` is required, pass only its path. Do not print its contents.

- [ ] **Step 3: Verify every local entry**

Use `curl` for health/routes and the in-app browser for the four user-facing URLs. Confirm the two close-loop homepage cards still resolve to remote URLs.

- [ ] **Step 4: Verify idempotent reuse**

In a second terminal, run `npm run dev:status` and a second start probe. Confirm it reports healthy existing processes and does not allocate alternate ports.

- [ ] **Step 5: Stop owned services and compare snapshots**

Run `npm run dev:stop`; confirm only processes absent from the pre-start snapshot were stopped.

- [ ] **Step 6: Run final regression suite**

Run:

```bash
node --test tests/*.test.cjs tests/*.test.mjs
npm run build
mvn test
bash scripts/test-local-stack-ports.sh
```

Report any external dependency that prevents a child service from becoming healthy; do not claim that service is running without evidence.
