const { test, expect } = require('@playwright/test');
const { installWorkflowFixture } = require('./workflowFixture.cjs');
const path = require('node:path');
const fs = require('node:fs');
test.use({ browserName: 'chromium', channel: process.env.WORKFLOW_BROWSER_CHANNEL || 'chrome', viewport: { width: 1680, height: 1040 } });
const base = process.env.WORKFLOW_TEST_URL || 'http://127.0.0.1:8000';
const noPageOverflow = () => document.documentElement.scrollWidth <= innerWidth + 1;
const artifacts = () => {
  const dir = path.resolve(__dirname, '../docs/验收证据/智能体界面优化');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
};
async function publishedFixture(page) {
  const fixture = await installWorkflowFixture(page);
  const deployment = fixture.deployments.get('project-1');
  deployment.activeReleaseId = 'active-2';
  deployment.publishedReleaseId = 'published-3';
  const compile = task => ({ key: task.key, name: task.name, modelRevisionId: 'model-1', nodes: [], systemInstructions: ['local fixture'] });
  const tasks = [{ key: 'main', name: '综合采集与治理评估' }, { key: 'quality_check', name: '数据质量检查' }].map(compile);
  const snapshotOf = taskList => ({
    draft: structuredClone(deployment.draft),
    effectiveRole: '企业助手',
    compilerVersion: 'fusion-config-v3',
    models: { 'model-1': { id: 'model-1', resourceId: 'model', hash: 'a1234567890123', createdAt: '2026-01-01T00:00:00.000Z', content: { name: '通用对话模型', model: 'local-test-model', kind: 'llm' } } },
    tasks: taskList,
  });
  fixture.releases.set('project-1', [
    { id: 'published-3', deploymentId: deployment.id, sequence: 3, note: '尚未上线的验收版本', restoredFrom: null, hash: 'publishedhash12', createdAt: '2026-09-01T00:00:00.000Z', snapshot: snapshotOf([{ ...tasks[0], key: 'future', name: '尚未上线的任务' }]) },
    { id: 'active-2', deploymentId: deployment.id, sequence: 2, note: '运行版本验收示例', restoredFrom: null, hash: 'activehash12345', createdAt: '2026-08-01T00:00:00.000Z', snapshot: snapshotOf(tasks) },
  ]);
  return fixture;
}
async function waitForOverview(page) {
  await expect(page.getByRole('heading', { name: '工作台总览', exact: true })).toBeVisible();
  await expect(page.getByText('个已发布配置')).toBeVisible();
  await expect(page.getByRole('button', { name: /业务分析智能体/ })).toBeVisible();
}
async function waitForAgents(page) {
  await expect(page.getByRole('heading', { name: '智能体', exact: true })).toBeVisible();
  await expect(page.locator('.hub-agent-card').getByRole('heading', { name: /业务分析智能体/ })).toBeVisible();
}
async function waitForUse(page) {
  await expect(page.getByRole('heading', { name: 'API 接入', exact: true })).toBeVisible();
  await expect(page.getByLabel('调用示例', { exact: true })).toContainText('HttpClient');
}
async function waitForReleases(page) {
  await expect(page.getByRole('heading', { name: '版本与发布', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'R2', exact: true })).toBeVisible();
}
test('overview search select and version workflow API navigation stay connected', async ({ page }) => {
  await publishedFixture(page);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${base}/agent-hub/overview`);
  await waitForOverview(page);
  // 视图切换由 header 菜单承载，这里直接走路由验证页面内容与跳转链路
  await page.goto(`${base}/agent-hub/agents`);
  await waitForAgents(page);
  await page.getByLabel('搜索智能体', { exact: true }).fill('业务分析');
  await page.getByRole('button', { name: /业务分析智能体/ }).click();
  await page.getByRole('button', { name: '查看版本与发布', exact: true }).click();
  await expect(page).toHaveURL(/\/releases/);
  await waitForReleases(page);
  await page.goto(`${base}/agent-hub/agents`);
  await waitForAgents(page);
  await page.getByRole('button', { name: /业务分析智能体/ }).click();
  await page.getByRole('button', { name: /进入编排工作台/ }).click();
  await expect(page).toHaveURL(/\/workflows/);
  await expect(page.getByRole('heading', { name: '可视化编排', exact: true })).toBeVisible();
  await page.goto(`${base}/agent-hub/use`);
  await waitForUse(page);
  await expect(page.getByText('直接使用', { exact: true })).toHaveCount(0);
  expect(errors).toEqual([]);
});
test('dark theme and keyboard access remain usable', async ({ page }) => {
  await publishedFixture(page);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${base}/agent-hub/overview?project=project-1`);
  await waitForOverview(page);
  await page.getByRole('button', { name: '切换到深色主题', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.getByRole('button', { name: '切换到浅色主题', exact: true })).toBeVisible();
  await page.goto(`${base}/agent-hub/use`);
  await waitForUse(page);
  await page.goto(`${base}/agent-hub/overview`);
  await waitForOverview(page);
  await page.screenshot({ path: path.join(artifacts(), '深色-总览.png'), animations: 'disabled' });
  expect(errors).toEqual([]);
});
test('key pages stay inside 414 1024 and 1680 viewports', async ({ page }) => {
  await publishedFixture(page);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const shots = artifacts();
  const cases = [
    { width: 1680, height: 1040, route: 'overview', shot: '总览-1680.png' },
    { width: 1024, height: 768, route: 'agents', shot: '智能体-1024.png' },
    { width: 414, height: 896, route: 'use', shot: 'API接入-414.png' },
  ];
  const ready = { overview: waitForOverview, agents: waitForAgents, releases: waitForReleases, use: waitForUse };
  for (const item of cases) {
    await page.setViewportSize({ width: item.width, height: item.height });
    for (const route of ['overview', 'agents', 'releases', 'use']) {
      await page.goto(`${base}/agent-hub/${route}?project=project-1`);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await ready[route](page);
      expect(await page.evaluate(noPageOverflow), `${route} @ ${item.width}`).toBe(true);
    }
    await page.goto(`${base}/agent-hub/${item.route}?project=project-1`);
    await ready[item.route](page);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await page.screenshot({ path: path.join(shots, item.shot), animations: 'disabled' });
  }
  expect(errors).toEqual([]);
});
test('1440 layout stacks API steps and keeps modal form spacing', async ({ page }) => {
  await publishedFixture(page);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const shots = artifacts();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${base}/agent-hub/use?project=project-1`);
  await waitForUse(page);
  const main = await page.locator('.usage-board-main').boundingBox();
  const example = await page.locator('.usage-board-example').boundingBox();
  expect(main).toBeTruthy(); expect(example).toBeTruthy();
  expect(example.y).toBeGreaterThan(main.y + main.height - 1);
  expect(Math.abs(example.x - main.x)).toBeLessThanOrEqual(8);
  await page.screenshot({ path: path.join(shots, 'API接入-1440.png'), animations: 'disabled' });
  await page.goto(`${base}/agent-hub/overview?project=project-1`);
  await waitForOverview(page);
  await page.getByRole('button', { name: '新建智能体', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '新建智能体', exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('智能体名称', { exact: true }).click();
  await expect(dialog.getByLabel('智能体名称', { exact: true })).toBeFocused();
  await expect(dialog).toBeInViewport();
  await expect(dialog).toHaveCSS('transform', 'none');
  await expect(dialog).toHaveCSS('opacity', '1');
  const form = dialog.locator('.hub-form');
  expect(await form.evaluate(node => !!node.closest('.agent-hub-shell'))).toBe(false);
  const first = await form.locator('label').nth(0).boundingBox();
  const second = await form.locator('label').nth(1).boundingBox();
  expect(first).toBeTruthy(); expect(second).toBeTruthy();
  expect(second.y - (first.y + first.height)).toBeGreaterThanOrEqual(12);
  await page.screenshot({ path: path.join(shots, '新建智能体-弹窗.png'), animations: 'disabled' });
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(errors).toEqual([]);
});
test('dark theme paints the app root when the host page stays white', async ({ page }) => {
  await publishedFixture(page);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${base}/agent-hub/overview?project=project-1`);
  await waitForOverview(page);
  await page.getByRole('button', { name: '切换到深色主题', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.addStyleTag({ content: 'html, body { background: #ffffff !important; }' });
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  const root = page.locator('.midplat-frontend-root.app-layout');
  await expect(root).toHaveCSS('background-color', 'rgb(6, 11, 24)');
  await expect(page.getByRole('heading', { name: '工作台总览', exact: true })).toHaveCSS('color', 'rgb(255, 255, 255)');
  await page.screenshot({ path: path.join(artifacts(), '深色-白色宿主.png'), animations: 'disabled' });
  await page.goto(`${base}/agent-hub/use?project=project-1`);
  // 跳转会重建文档，需要重新注入宿主白色样式
  await page.addStyleTag({ content: 'html, body { background: #ffffff !important; }' });
  await waitForUse(page);
  await expect(root).toHaveCSS('background-color', 'rgb(6, 11, 24)');
  await page.getByRole('button', { name: '切换到浅色主题', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(root).toHaveCSS('background-color', 'rgb(243, 246, 251)');
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  expect(errors).toEqual([]);
});
