const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const projectRoot = path.resolve(__dirname, '..');

function read(relativePath) {
  return fs.readFileSync(path.join(projectRoot, relativePath), 'utf8');
}

test('原生智能体平台使用独立路由命名空间和八个管理页面', () => {
  const umiConfig = read('.umirc.ts');

  for (const route of ['overview', 'agents', 'assets', 'settings', 'releases', 'workflows', 'runs', 'audit']) {
    assert.match(umiConfig, new RegExp(`path:\\s*['\"]\\/agent-hub\\/${route}['\"]`));
  }
  assert.match(read('src/routeCatalog.ts'), /path:\s*'\/agent-hub\/overview'/);
});

test('融合样式支持窄屏和减少动态效果', () => {
  const css = read('src/pages/agent-hub/workspace.css');

  assert.match(css, /@media\s*\(max-width:\s*700px\)/);
  assert.match(css, /prefers-reduced-motion:\s*reduce/);
  assert.match(css, /:focus-visible/);
});

test('智能体平台复用全站粒子背景', () => {
  const source = read('src/layouts/index.tsx');
  assert.match(source, /<CubeField/);
  assert.doesNotMatch(source, /isAgentHubPreview/);
  assert.doesNotMatch(source, /pathname\.startsWith\('\/agent-hub'\)/);
});

test('工作台导航由 header 菜单承载，页面内不再渲染侧栏', () => {
  const source = read('src/pages/agent-hub/FusionWorkspace.tsx');
  // 侧栏已移除：不再有 AgentHub 品牌区、分组导航与侧栏容器
  assert.doesNotMatch(source, /AgentHub/);
  assert.doesNotMatch(source, /group: '构建'/);
  assert.doesNotMatch(source, /is-standalone/);
  assert.doesNotMatch(source, /agent-hub-sidebar/);
  assert.doesNotMatch(source, /hub-sidebar-bottom/);
  assert.doesNotMatch(source, /aria-label="智能体平台导航"/);
  // 导航分组与八个子菜单落在后端菜单迁移中，由 header 渲染
  const migration = read('../midplat-backend/src/main/resources/db/migration/V40__agent_platform_top_level_menu.sql');
  assert.match(migration, /menu-agent-hub-overview/);
  assert.match(migration, /menu-agent-hub-agents/);
  assert.match(migration, /menu-agent-hub-assets/);
  assert.match(migration, /menu-agent-hub-releases/);
  assert.match(migration, /menu-agent-hub-runs/);
  assert.match(migration, /menu-agent-hub-use/);
  assert.match(migration, /menu-agent-hub-settings/);
  assert.match(migration, /menu-agent-hub-audit/);
});

test('兜底 404 路由指向独立页面组件', () => {
  const umiConfig = read('.umirc.ts');
  assert.match(umiConfig, /path:\s*['"]\*['"]/);
  assert.match(umiConfig, /@\/pages\/404/);
  const page = read('src/pages/404.tsx');
  assert.match(page, /页面不存在/);
  assert.match(page, /返回首页/);
});

test('菜单设置展示原始路由标识而不是页面标题', () => {
  const source = read('src/pages/settings/menus/index.tsx');
  assert.match(source, /title:\s*'路由标识'/);
  assert.match(source, /menu-table-route/);
  assert.match(source, /页面：\{title\}/);
  assert.doesNotMatch(source, /路由 name/);
});
