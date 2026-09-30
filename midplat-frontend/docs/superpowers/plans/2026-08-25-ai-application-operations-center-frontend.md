# AI Application Operations Center Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有数智医院智能应用中心前端升级为可供领导评审的 AI 应用运营管理中心原型。

**Architecture:** 保留现有 Umi Max、React、Ant Design、横向导航、魔方粒子背景和明暗主题。现有项目、模型、提示词、菜单继续读取后端接口；运行、告警、数据连接与交付信息集中放入纯前端原型数据模块，并通过统一“演示数据”标识与真实数据隔离。

**Tech Stack:** React 18、TypeScript 5、Umi Max 4、Ant Design 6、TanStack Query 5、Lucide React、Node test runner + tsx、现有 CSS 变量主题系统。

**Spec:** `docs/superpowers/specs/2026-08-25-ai-application-operations-center-frontend-design.md`

## Global Constraints

- 只修改 `midplat-frontend`，不修改后端、数据库和任何既有 AI 子项目。
- 保留顶部横向菜单布局、背景、魔方粒子效果、浅色主题和暗色主题。
- 普通医院业务用户不进入本系统；页面文案面向医院信息科、数据中心及公司实施运维人员。
- 权限与角色控制不在本轮实现。
- 患者业务数据不进入本系统；数据连接页只展示治理元数据。
- 所有非真实接口数据必须显示“演示数据”，并集中在原型数据模块中。
- 不回退 `src/pages/capabilities/index.tsx` 当前工作区已有的未提交修改。
- 每次提交只暂存当前任务列出的文件，不使用 `git add .` 或 `git commit -a`。
- 所有页面必须具有加载、错误、空数据和未接入状态。

---

### Task 1: Prototype Operations Domain

**Files:**
- Create: `src/prototype/operationsData.ts`
- Create: `src/prototype/operationsData.test.ts`
- Modify: `package.json`

**Interfaces:**
- Consumes: `PlatformItem`、`AiModel`、`PromptItem` from `src/api/midplatApi.ts`。
- Produces: `DEMO_CALL_TREND`、`DEMO_ALERTS`、`DEMO_DELIVERY_PROFILE`、`getPlatformPrototype(platformId)`、`getPlatformCompletion(platform)`、`getPlatformOperationalState(platform)`。

- [ ] **Step 1: Add the unit-test command**

在 `package.json` 的 `scripts` 中加入：

```json
"test:unit": "tsx --test src/prototype/operationsData.test.ts src/navigation/primaryNav.test.ts"
```

测试命令使用当前 Umi 依赖树中已安装的 `tsx`，不新增运行时依赖。

- [ ] **Step 2: Write failing domain tests**

创建 `src/prototype/operationsData.test.ts`：

```ts
import assert from 'node:assert/strict';
import test from 'node:test';
import type { PlatformItem } from '../api/midplatApi';
import {
  getPlatformCompletion,
  getPlatformOperationalState,
  getPlatformPrototype,
} from './operationsData';

const complete: PlatformItem = {
  id: 'plat-kb',
  name: '知识库',
  entryUrl: 'https://example.test/kb',
  icon: 'Library',
  consume: true,
  token: 'masked',
  llmModelId: 'llm-1',
  embeddingModelId: 'emb-1',
  rerankModelId: 'rr-1',
  promptId: 'prompt-1',
};

test('known application resolves its prototype metadata', () => {
  assert.equal(getPlatformPrototype('plat-kb').dataConnections[0].domain, '院内知识');
});

test('unknown application receives an explicit unmonitored fallback', () => {
  assert.equal(getPlatformPrototype('unknown').runtime.status, 'unmonitored');
});

test('configuration completion reflects real platform fields', () => {
  assert.equal(getPlatformCompletion(complete), 100);
  assert.ok(getPlatformCompletion({ ...complete, entryUrl: null, promptId: null }) < 100);
});

test('runtime state never upgrades missing monitoring to healthy', () => {
  assert.equal(getPlatformOperationalState({ ...complete, id: 'unknown' }).status, 'unmonitored');
});
```

- [ ] **Step 3: Run tests and verify the red state**

Run: `npm run test:unit`

Expected: FAIL because `operationsData.ts` and `primaryNav.test.ts` do not exist yet.

- [ ] **Step 4: Implement the centralized prototype domain**

创建 `src/prototype/operationsData.ts`，定义以下完整类型与稳定导出：

```ts
import type { PlatformItem } from '../api/midplatApi';

export type OperationalStatus = 'healthy' | 'warning' | 'offline' | 'unmonitored';

export type DataConnectionProfile = {
  id: string;
  name: string;
  domain: string;
  sourceType: string;
  syncMode: string;
  lastSyncAt: string;
  qualityLabel: string;
  status: OperationalStatus;
};

export type PlatformPrototype = {
  owner: string;
  version: string;
  deployedAt: string;
  runtime: {
    status: OperationalStatus;
    label: string;
    lastCheckedAt: string;
    successRate: number | null;
    p95LatencyMs: number | null;
  };
  dataConnections: DataConnectionProfile[];
  recentChanges: Array<{ at: string; title: string; description: string }>;
};

export const DEMO_CALL_TREND: Array<{ date: string; calls: number; failures: number }> = [
  { date: '08-19', calls: 1268, failures: 31 },
  { date: '08-20', calls: 1486, failures: 28 },
  { date: '08-21', calls: 1362, failures: 24 },
  { date: '08-22', calls: 1718, failures: 35 },
  { date: '08-23', calls: 1896, failures: 41 },
  { date: '08-24', calls: 1624, failures: 29 },
  { date: '08-25', calls: 2015, failures: 37 },
];
```

同一文件继续定义三项现有平台的完整演示档案、告警事件、交付环境、版本矩阵与七步验收项。`getPlatformPrototype` 对未知平台返回 `unmonitored`，不得返回健康；`getPlatformCompletion` 按入口、LLM、提示词、凭证四项计算 0/25/50/75/100。

- [ ] **Step 5: Run the available domain test only**

Run: `npx tsx --test src/prototype/operationsData.test.ts`

Expected: PASS for all four domain tests.

- [ ] **Step 6: Commit the domain layer only**

```bash
git add package.json src/prototype/operationsData.ts src/prototype/operationsData.test.ts
git commit -m "feat: add operations prototype domain"
```

---

### Task 2: Horizontal Navigation and Routes

**Files:**
- Create: `src/navigation/primaryNav.ts`
- Create: `src/navigation/primaryNav.test.ts`
- Modify: `.umirc.ts`
- Modify: `src/routeCatalog.ts`
- Modify: `src/layouts/index.tsx`

**Interfaces:**
- Consumes: `MenuItem`、`menuHref`、现有 `menu-entry` 动态分类菜单。
- Produces: `PRIMARY_NAV_LABELS`、`CONFIG_NAV_ITEMS`、`operations` 和 `delivery` 路由，以及六项横向一级菜单。

- [ ] **Step 1: Write the failing navigation contract test**

创建 `src/navigation/primaryNav.test.ts`：

```ts
import assert from 'node:assert/strict';
import test from 'node:test';
import { CONFIG_NAV_ITEMS, PRIMARY_NAV_LABELS } from './primaryNav';

test('primary navigation keeps the approved six labels and order', () => {
  assert.deepEqual(PRIMARY_NAV_LABELS, [
    '首页', 'AI 应用', '能力开放', '运行保障', '交付管理', '配置管理',
  ]);
});

test('configuration management groups the three existing admin pages', () => {
  assert.deepEqual(CONFIG_NAV_ITEMS.map((item) => item.path), [
    '/models', '/prompts', '/settings/menus',
  ]);
});
```

- [ ] **Step 2: Run tests and verify navigation is red**

Run: `npm run test:unit`

Expected: prototype-domain tests pass; navigation tests FAIL because `primaryNav.ts` does not exist.

- [ ] **Step 3: Implement pure navigation metadata**

创建 `src/navigation/primaryNav.ts`：

```ts
export const PRIMARY_NAV_LABELS = [
  '首页', 'AI 应用', '能力开放', '运行保障', '交付管理', '配置管理',
] as const;

export const CONFIG_NAV_ITEMS = [
  { key: '/models', path: '/models', label: '模型管理', icon: 'Cpu' },
  { key: '/prompts', path: '/prompts', label: '提示词管理', icon: 'FileText' },
  { key: '/settings/menus', path: '/settings/menus', label: '菜单设置', icon: 'Menu' },
] as const;
```

- [ ] **Step 4: Add routes and route catalog entries**

在 `.umirc.ts` 根路由 children 中加入：

```ts
{ path: '/operations', component: '@/pages/operations/index' },
{ path: '/delivery', component: '@/pages/delivery/index' },
```

在 `src/routeCatalog.ts` 中加入：

```ts
{ name: 'operations', path: '/operations', title: '运行保障', filePath: 'src/pages/operations/index.tsx', component: '@/pages/operations/index' },
{ name: 'delivery', path: '/delivery', title: '交付管理', filePath: 'src/pages/delivery/index.tsx', component: '@/pages/delivery/index' },
```

- [ ] **Step 5: Rebuild the horizontal menu without changing the shell**

修改 `src/layouts/index.tsx`：

- 保留 `CubeField`、`topbar`、品牌区、主题切换按钮和 `Menu mode="horizontal"`；
- 读取 `menu-entry` 的动态子菜单作为“AI 应用”下拉项；
- 固定加入“能力开放” `/capabilities`、“运行保障” `/operations`、“交付管理” `/delivery`；
- 使用 `CONFIG_NAV_ITEMS` 生成“配置管理”下拉；
- `selectedKeys` 继续使用当前 pathname，`/models`、`/prompts`、`/settings/menus` 同时将“配置管理”父项标记为选中；
- 忽略后端返回的旧能力开放和旧系统设置根项，避免重复菜单；
- 动态应用分类为空时继续使用 `fallbackSceneMenus()`。

- [ ] **Step 6: Run unit and type checks**

Run: `npm run test:unit && npx tsc --noEmit`

Expected: PASS，且路由名称类型完整。

- [ ] **Step 7: Commit navigation files only**

```bash
git add .umirc.ts src/routeCatalog.ts src/layouts/index.tsx src/navigation/primaryNav.ts src/navigation/primaryNav.test.ts
git commit -m "feat: reorganize operations navigation"
```

---

### Task 3: Operations Overview Home

**Files:**
- Create: `src/components/DemoDataNotice.tsx`
- Modify: `src/pages/home/index.tsx`
- Modify: `src/global.css`

**Interfaces:**
- Consumes: `midplatApi.listPlatforms`、`listModels`、`listPrompts`、Task 1 原型数据与状态选择器。
- Produces: `/home` 运营总览与可复用 `DemoDataNotice`。

- [ ] **Step 1: Add a reusable demo-data notice**

创建 `src/components/DemoDataNotice.tsx`：

```tsx
import { FlaskConical } from 'lucide-react';

export function DemoDataNotice({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`demo-data-notice${compact ? ' compact' : ''}`} role="note">
      <FlaskConical size={15} aria-hidden="true" />
      <span><strong>演示数据</strong>：运行、告警和交付指标仅用于本次前端产品评审。</span>
    </div>
  );
}
```

- [ ] **Step 2: Replace the promotional home with an operations overview**

修改 `src/pages/home/index.tsx`，保留 `.home-page` 和现有视觉层，改为同时查询：

```ts
const platformsQuery = useQuery({ queryKey: ['platforms'], queryFn: midplatApi.listPlatforms });
const modelsQuery = useQuery({ queryKey: ['models'], queryFn: midplatApi.listModels });
const promptsQuery = useQuery({ queryKey: ['prompts'], queryFn: midplatApi.listPrompts });
```

页面必须包含：

- 标题“AI 应用运营总览”和面向信息科/实施运维的副标题；
- 真实指标：已纳管应用数、模型数、提示词数；
- 演示指标：待处理告警数，卡片上显示“演示”；
- 应用状态列表：真实名称和配置完整度，运行状态读取原型数据；
- “异常与待处理”列表；
- 纯 CSS 七日调用柱状趋势，不引入图表依赖；
- 快捷操作：`/entry`、`/capabilities`、`/operations`、`/delivery`；
- 查询失败时显示错误说明和刷新按钮；无应用时显示登记引导。

- [ ] **Step 3: Add scoped dashboard styles**

在 `src/global.css` 末尾新增 `.operations-home-*`、`.demo-data-notice`、`.metric-card-*`、`.trend-bars-*` 等作用域类。颜色必须使用现有 CSS 变量；深浅主题均通过变量工作，不修改主题基础定义和粒子层样式。

- [ ] **Step 4: Verify compile and production build**

Run: `npm run test:unit && npx tsc --noEmit && npm run build`

Expected: PASS，首页路由成功生成。

- [ ] **Step 5: Commit the overview only**

```bash
git add src/components/DemoDataNotice.tsx src/pages/home/index.tsx src/global.css
git commit -m "feat: build AI operations overview"
```

---

### Task 4: Operations and Delivery Pages

**Files:**
- Create: `src/pages/operations/index.tsx`
- Create: `src/pages/delivery/index.tsx`
- Modify: `src/global.css`

**Interfaces:**
- Consumes: `DemoDataNotice`、Task 1 中的应用运行档案、告警、调用趋势、交付环境、版本矩阵和验收项。
- Produces: `/operations`、`/delivery` 两个完整演示页面。

- [ ] **Step 1: Implement the operations page**

创建 `src/pages/operations/index.tsx`，使用 Ant Design `Tabs`，固定四个标签：

```ts
const tabs = [
  { key: 'health', label: '健康监测' },
  { key: 'calls', label: '调用监测' },
  { key: 'alerts', label: '告警事件' },
  { key: 'changes', label: '变更记录' },
];
```

页面要求：

- 页头标题“运行保障”并显示 `DemoDataNotice`；
- 健康监测按应用展示入口、模型、数据连接和能力接口状态；
- 调用监测提供七日趋势、成功率、P95 延迟和应用维度列表；
- 告警事件显示级别、来源、时间、状态和处理建议；
- 变更记录按时间展示变更标题与摘要；
- 每个标签均有空状态；状态文字与图标并存。

- [ ] **Step 2: Implement the delivery page**

创建 `src/pages/delivery/index.tsx`，页面包含：

- 环境概览卡：环境名称、部署模式、数据库类型、更新时间；
- 七阶段接入进度条：建档、模型、提示词、数据连接、能力、监控、验收；
- 应用版本矩阵表；
- 验收清单，按完成、待确认、未开始展示文字状态；
- 升级记录时间线；
- 顶部 `DemoDataNotice`，所有内容只读，不出现可提交或审批的误导按钮。

- [ ] **Step 3: Add page-scoped styles**

在 `src/global.css` 添加 `.operations-page-*` 与 `.delivery-page-*`。桌面宽度使用 12 列网格，低于 900px 变为单列；横向表格容器提供 `overflow-x: auto`。

- [ ] **Step 4: Verify routes and build**

Run: `npm run test:unit && npx tsc --noEmit && npm run build`

Expected: PASS，构建产物包含 operations 和 delivery 异步 chunk。

- [ ] **Step 5: Commit both pages only**

```bash
git add src/pages/operations/index.tsx src/pages/delivery/index.tsx src/global.css
git commit -m "feat: add operations and delivery workspaces"
```

---

### Task 5: AI Application Management Experience

**Files:**
- Create: `src/pages/platforms/ProjectOverview.tsx`
- Create: `src/pages/platforms/DataConnectionsPanel.tsx`
- Create: `src/pages/platforms/OperationsPanel.tsx`
- Create: `src/pages/platforms/VersionPanel.tsx`
- Modify: `src/pages/entry/index.tsx`
- Modify: `src/pages/entry/scene.tsx`
- Modify: `src/pages/entry/category.tsx`
- Modify: `src/pages/platforms/index.tsx`
- Modify: `src/global.css`

**Interfaces:**
- Consumes: 现有项目 CRUD、模型/提示词/运行参数/凭证能力，`getPlatformPrototype`、`getPlatformCompletion`、`midplatApi.listApis`。
- Produces: “AI 应用”列表语言、以应用为中心的概览/配置/数据/能力/运行/凭证/版本详情导航。

- [ ] **Step 1: Build focused read-only panels**

四个新组件使用以下 props：

```ts
type ProjectPanelProps = {
  platform: PlatformItem;
  models: AiModel[];
  prompts: PromptItem[];
};
```

- `ProjectOverview`：应用档案、入口、配置完整度、模型和提示词摘要、运行摘要；
- `DataConnectionsPanel`：原型数据源、数据域、同步方式、最近同步、质量状态，并显示 `DemoDataNotice compact`；
- `OperationsPanel`：健康、成功率、延迟、近期变更，并显示演示标识；
- `VersionPanel`：当前版本、部署时间和变更摘要，并显示演示标识。

- [ ] **Step 2: Extend the existing project detail navigation**

修改 `src/pages/platforms/index.tsx`：

```ts
type PlatformView =
  | 'table'
  | 'overview'
  | 'models'
  | 'prompts'
  | 'runtime'
  | 'data'
  | 'capabilities'
  | 'operations'
  | 'access'
  | 'versions';
```

具体要求：

- 项目卡主按钮改为“管理应用”，打开 `overview`；
- 项目卡展示配置完整度和“演示/未接入监控”运行状态；
- 详情导航顺序为：概览、模型与参数、提示词、数据连接、开放能力、运行状态、接入凭证、版本记录；
- 知识库的真实运行参数入口保留在“模型与参数”之后；
- 开放能力面板通过 `midplatApi.listApis(platform.id)` 读取真实 API 列表，加载失败显示重试；
- 所有现有保存、热更新、复制凭证和删除能力保持原行为；
- 不修改创建接口 payload，不向后端发送演示字段。

- [ ] **Step 3: Update AI application language**

修改 `src/pages/entry/index.tsx`、`scene.tsx`、`category.tsx`：

- “门户”“AI 工作台”统一改为“AI 应用”；
- 描述改为“统一纳管和运营已接入的 AI 应用”；
- 普通用户导向的“按场景找能力”文案改为管理人员导向；
- 仍保留现有分类和搜索能力。

- [ ] **Step 4: Add application-management styles**

在 `src/global.css` 添加 `.project-overview-*`、`.data-connection-*`、`.project-health-*`、`.project-version-*`；复用现有 `.settings-nav`、`.form-layout` 和状态样式，不修改粒子及主题基础规则。

- [ ] **Step 5: Run verification**

Run: `npm run test:unit && npx tsc --noEmit && npm run build`

Expected: PASS；现有项目配置、提示词、运行参数和凭证仍编译通过。

- [ ] **Step 6: Commit only the application-management files**

```bash
git add src/pages/entry/index.tsx src/pages/entry/scene.tsx src/pages/entry/category.tsx src/pages/platforms/index.tsx src/pages/platforms/ProjectOverview.tsx src/pages/platforms/DataConnectionsPanel.tsx src/pages/platforms/OperationsPanel.tsx src/pages/platforms/VersionPanel.tsx src/global.css
git commit -m "feat: upgrade AI application management"
```

---

### Task 6: Capability Integrity and Visual Acceptance

**Files:**
- Modify only if necessary: `src/pages/capabilities/index.tsx`
- Modify only if necessary: `src/global.css`

**Interfaces:**
- Consumes: 当前工作区能力开放中心实现、所有前述页面与导航。
- Produces: 完整可演示前端，构建通过且主要页面完成视觉验收。

- [ ] **Step 1: Inspect the existing capability diff before editing**

Run: `git diff -- src/pages/capabilities/index.tsx`

Expected: 能看到用户已有未提交内容。记录其结构，只做以下必要修正：

- 0 调用量或 0% 成功率若无真实接口来源，改为“监控未接入”；
- 原型调用方或统计区域增加 `DemoDataNotice compact`；
- 不删除完整请求参数、响应参数、示例和错误码详情。

- [ ] **Step 2: Run all automated verification**

Run: `npm run test:unit`

Expected: PASS。

Run: `npx tsc --noEmit`

Expected: PASS。

Run: `npm run build`

Expected: PASS，生成首页、AI 应用、能力开放、运行保障、交付管理和配置页面 chunk。

Run: `git diff --check`

Expected: 无空白错误。

- [ ] **Step 3: Start the local frontend for browser acceptance**

Run: `npm run dev -- --port 8000`

Expected: Umi 开发服务在 `http://127.0.0.1:8000` 可访问。若 8000 已占用，复用当前服务，不终止无关进程。

- [ ] **Step 4: Verify the main review path in both themes**

浏览器依次检查：

1. `/home`：横向菜单、运营指标、应用状态、演示数据标识、调用趋势；
2. `/entry`：AI 应用文案、搜索与分类；
3. 任一应用分类：管理应用 → 概览 → 数据连接 → 开放能力 → 运行状态 → 版本记录；
4. `/capabilities`：完整调用详情仍可查看；
5. `/operations`：四个标签均可切换；
6. `/delivery`：环境、进度、版本、验收和升级记录完整；
7. 配置管理下拉：模型、提示词、菜单均可进入；
8. 深色与浅色主题切换后背景和粒子效果保持，文字和状态可读。

- [ ] **Step 5: Fix only issues found by verification**

对浏览器验收发现的问题做最小修复，修改范围限定在本计划文件；每次修复后重新运行 `npx tsc --noEmit && npm run build`。

- [ ] **Step 6: Final working-tree audit**

Run: `git status --short && git diff --stat && git log -6 --oneline`

Expected: 所有计划新增文件存在；若 `src/pages/capabilities/index.tsx` 仍含原有未提交内容，保持为工作区修改并在交付说明中单独列出，不把来源不明的改动伪装成新提交。

