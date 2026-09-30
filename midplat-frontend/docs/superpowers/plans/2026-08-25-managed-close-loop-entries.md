# Managed Close-loop Entries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将闭环根因分析作为完整 AI 项目纳管，在保留模型、提示词和真实接口配置的同时，管理根因报告与鱼骨图两个远程入口。

**Architecture:** 后端新增独立的项目入口模块，并通过 `ManagedPlatform` 外键归属项目；项目模块继续管理模型与提示词。前端以小型数据适配器把入口接口转换为首页卡片，项目详情新增“应用入口”和“接口”视图，远程地址保持不变。

**Tech Stack:** Java 17、Spring Boot 3.2、Spring Data JDBC、Flyway、JUnit/MockMvc、React 18、TypeScript、Umi Max、TanStack Query、Ant Design、Node test runner。

**Spec:** `docs/superpowers/specs/2026-08-25-managed-close-loop-entries-design.md`

## Global Constraints

- 根因报告和鱼骨图必须继续打开 `company-portal.example.com:30200` 的现有远程地址。
- `plat-close-loop` 必须保留模型、提示词和接口配置。
- 目标系统没有对应热更新契约时，只能显示“中台已记录，待目标项目同步”。
- 接口目录只登记 `lcfz` 分支中真实存在的接口，不生成虚构指标。
- 不回退当前前端工作区中的既有确认修改。
- 每次提交只暂存当前任务文件，不使用 `git add .` 或 `git commit -a`。

---

### Task 1: Platform Entry Persistence and Seed Data

**Files:**
- Create: `midplat-backend/src/main/resources/db/migration/V16__managed_close_loop_entries.sql`
- Create: `midplat-backend/src/main/java/com/yiwei/midplat/platformentry/PlatformEntry.java`
- Create: `midplat-backend/src/main/java/com/yiwei/midplat/platformentry/PlatformEntryRepository.java`
- Create: `midplat-backend/src/test/java/com/yiwei/midplat/platformentry/PlatformEntrySeedTest.java`

**Interfaces:**
- Produces: `PlatformEntry` with getters for `platformId`, `name`, `description`, `icon`, `entryUrl`, `status`, and `sortOrder`.
- Produces: `PlatformEntryRepository.findByPlatformIdOrderBySortOrderAsc(String)`.

- [ ] **Step 1: Write the failing seed test**

Create a Spring test that loads `plat-close-loop`, asserts it is bound to `menu-scene-insight`, and asserts two ordered entries with IDs `root-cause-report` and `fishbone-analysis`. Also assert `consume=false` and both remote URLs exactly match the spec.

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./mvnw -Dtest=PlatformEntrySeedTest test
```

If `mvnw` is absent, run the configured Maven binary with the same arguments. Expected failure: missing table/entity/seed project.

- [ ] **Step 3: Add schema and seed records**

The migration must:

```sql
create table midplat_platform_entry (
  id varchar(64) primary key,
  platform_id varchar(64) not null references midplat_platform(id) on delete cascade,
  name varchar(128) not null,
  description varchar(512) not null,
  icon varchar(64) not null,
  entry_url varchar(512) not null,
  status varchar(32) not null,
  sort_order integer not null default 0,
  created_at timestamp with time zone not null default current_timestamp,
  updated_at timestamp with time zone not null default current_timestamp,
  version bigint not null default 0,
  constraint chk_platform_entry_status check (status in ('ONLINE', 'OFFLINE'))
);
```

Insert `plat-close-loop`, bind it to `menu-scene-insight`, and insert the two specified entries idempotently. Bind a real existing LLM and prompt only when their IDs exist; otherwise leave bindings null rather than inventing records.

- [ ] **Step 4: Implement entity and repository**

Use the existing `BaseEntity` audit/version convention. Repository order must be deterministic:

```java
List<PlatformEntry> findByPlatformIdOrderBySortOrderAsc(String platformId);
Optional<PlatformEntry> findByIdAndPlatformId(String id, String platformId);
```

- [ ] **Step 5: Run the seed test and full backend tests**

Run the focused test, then `mvn test`. Expected: all pass.

- [ ] **Step 6: Commit backend persistence**

Stage only the migration, entity, repository, and seed test. Commit message: `feat: seed managed close-loop entries`.

---

### Task 2: Platform Entry CRUD Interface

**Files:**
- Create: `midplat-backend/src/main/java/com/yiwei/midplat/platformentry/PlatformEntryService.java`
- Create: `midplat-backend/src/main/java/com/yiwei/midplat/platformentry/PlatformEntryController.java`
- Create: `midplat-backend/src/test/java/com/yiwei/midplat/platformentry/PlatformEntryApiTest.java`

**Interfaces:**
- Produces: `PlatformEntryView(String id, String platformId, String name, String description, String icon, String entryUrl, String status, int sortOrder)`.
- Produces the four REST routes defined in the spec.

- [ ] **Step 1: Write failing CRUD and validation tests**

Cover list ordering, create, update, delete, unknown platform, entry from another platform, blank name, unsupported `ftp://` URL, and status outside `ONLINE|OFFLINE`. Assert existing unified response/error format.

- [ ] **Step 2: Run focused test and verify RED**

Run `mvn -Dtest=PlatformEntryApiTest test`. Expected: 404 because controller does not exist.

- [ ] **Step 3: Implement the service interface**

Validation rules:

```java
private URI requireHttpUrl(String value) {
    URI uri = URI.create(value == null ? "" : value.trim());
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
        throw new IllegalArgumentException("入口地址必须是有效的 HTTP/HTTPS 地址");
    }
    return uri;
}
```

Create IDs with `Identities.newId()` for user-created entries. Seed IDs remain stable.

- [ ] **Step 4: Implement the controller**

Use `@RequestMapping("/api/platforms/{platformId}/entries")`, `@Valid`, `@NotBlank`, and the existing `ApiResponse` wrapper. `PATCH` replaces all editable fields so frontend state stays simple.

- [ ] **Step 5: Run focused and full backend tests**

Expected: CRUD test and all backend tests pass.

- [ ] **Step 6: Commit backend CRUD**

Commit message: `feat: manage platform application entries`.

---

### Task 3: Real Close-loop Interface Catalog

**Files:**
- Modify: `midplat-backend/src/main/java/com/yiwei/midplat/platform/PlatformApiCatalog.java`
- Modify: `midplat-frontend/src/pages/platforms/apiCatalog.ts`
- Create: `midplat-backend/src/test/java/com/yiwei/midplat/platform/CloseLoopApiCatalogTest.java`
- Create: `midplat-frontend/tests/closeLoopApiCatalog.test.cjs`

**Interfaces:**
- Produces catalog IDs: `cl-report`, `cl-report-stream`, `cl-project-query`, `cl-agent-context`, `cl-list`, `cl-items-get`, `cl-items-save`.

- [ ] **Step 1: Write failing backend and frontend catalog tests**

Assert each method/path from the spec and assert no generated runtime chat/embed/rerank entries appear for `plat-close-loop`.

- [ ] **Step 2: Run both tests and verify RED**

Run focused Maven test and `node --test tests/closeLoopApiCatalog.test.cjs`. Expected: close-loop catalog missing.

- [ ] **Step 3: Add matching catalogs on both sides**

Add one dedicated `closeLoopApis()` branch for `plat-close-loop`. Mark report generation endpoints externally exposable; mark project context/list/item management internal by default. Use zero fabricated metrics.

- [ ] **Step 4: Run both catalog tests**

Expected: both pass and paths match exactly.

- [ ] **Step 5: Commit catalog changes**

Commit message: `feat: register verified close-loop interfaces`.

---

### Task 4: Frontend Entry Client and Home Adapter

**Files:**
- Modify: `midplat-frontend/src/api/midplatApi.ts`
- Create: `midplat-frontend/src/pages/home/managedEntries.ts`
- Create: `midplat-frontend/tests/managedEntries.test.cjs`

**Interfaces:**
- Produces `PlatformEntryItem` and `PlatformEntryPayload`.
- Produces `midplatApi.listPlatformEntries`, `createPlatformEntry`, `updatePlatformEntry`, `deletePlatformEntry`.
- Produces `toHomeCapabilities(entries)` and `fallbackCloseLoopEntries`.

- [ ] **Step 1: Write failing adapter tests**

Test that only `ONLINE` entries render, ordering follows `sortOrder`, links remain URL links, API failure uses exactly the two remote fallbacks, and a successful empty response returns no cards.

- [ ] **Step 2: Run test and verify RED**

Run `node --test tests/managedEntries.test.cjs`. Expected: module missing.

- [ ] **Step 3: Implement API types and methods**

Keep the entry client under the existing `midplatApi` interface and return unwrapped data.

- [ ] **Step 4: Implement the pure adapter**

The adapter must not import React. It returns existing `HomeCapItem` shapes with `link: { type: 'url', url: entry.entryUrl }`.

- [ ] **Step 5: Run adapter and existing navigation tests**

Run all CJS tests. Expected: all pass.

- [ ] **Step 6: Commit client and adapter**

Commit message: `feat: load managed close-loop home entries`.

---

### Task 5: Project Detail Entry and Interface Views

**Files:**
- Modify: `midplat-frontend/src/pages/platforms/index.tsx`
- Create: `midplat-frontend/src/pages/platforms/PlatformEntriesConfig.tsx`
- Create: `midplat-frontend/src/pages/platforms/PlatformInterfacesConfig.tsx`
- Create: `midplat-frontend/tests/closeLoopProjectConfig.test.cjs`

**Interfaces:**
- `PlatformEntriesConfig({ platform })` owns entry CRUD/query cache.
- `PlatformInterfacesConfig({ platform })` owns `listApis` and `toggleApi`.

- [ ] **Step 1: Write a failing source-level navigation test**

Assert the close-loop project shows navigation labels `基本与模型`, `提示词`, `接口`, `应用入口`; assert the entries module opens `entryUrl` in a new tab; assert close-loop save copy contains `中台已记录，待目标项目同步` and does not contain a close-loop “已热更新” claim.

- [ ] **Step 2: Run test and verify RED**

Run `node --test tests/closeLoopProjectConfig.test.cjs`. Expected: entry/interface modules missing.

- [ ] **Step 3: Extract focused view modules**

Do not add more CRUD code to the already large `index.tsx`. Each module handles one query key and one set of mutations. Entry modal fields are name, description, icon, URL, status, and numeric order.

- [ ] **Step 4: Wire navigation and honest status copy**

Add `entries` and `interfaces` to `PlatformView`. Keep model and prompt views unchanged. For `plat-close-loop`, suppress “保存并热更新” wording and use “保存中台配置”.

- [ ] **Step 5: Run all frontend tests and type checking**

Run CJS tests and `npx tsc --noEmit` if supported by the Umi project; then run `npm run build`.

- [ ] **Step 6: Commit project configuration UI**

Stage only the two new modules, `platforms/index.tsx`, and its test. Commit message: `feat: configure close-loop project entries`.

---

### Task 6: Dynamic Home Rendering and Regression Verification

**Files:**
- Modify: `midplat-frontend/src/pages/home/index.tsx`
- Modify: `midplat-frontend/src/pages/home/portalData.ts`
- Modify: `midplat-frontend/tests/homeNavigation.test.cjs`
- Modify: only existing confirmed frontend files when a regression test proves a gap.

**Interfaces:**
- Home query key: `['platform-entries', 'plat-close-loop']`.

- [ ] **Step 1: Update home test to require managed data behavior**

Keep assertions for unchanged remote URLs, hidden featured section, and direct project configuration routes. Add assertions for loading/failure fallback and successful empty response semantics through the pure adapter.

- [ ] **Step 2: Run tests and verify RED for hardcoded-only behavior**

Expected: page does not yet query managed entries.

- [ ] **Step 3: Replace hardcoded rendering with managed query**

Keep fallback constants in `managedEntries.ts`, not duplicated in the page. Only replace the two close-loop items inside the existing insight section.

- [ ] **Step 4: Run complete verification**

Run:

```bash
node --test tests/*.test.cjs
npm run build
mvn test
```

Verify the existing confirmed menu, homepage, capability-center, theme, and route tests remain green.

- [ ] **Step 5: Commit home integration**

Commit message: `feat: drive close-loop home cards from management`.
