const { test, expect } = require('@playwright/test');
const { installWorkflowFixture } = require('./workflowFixture.cjs');
const path = require('node:path'); const fs = require('node:fs');
test.use({ browserName: 'chromium', channel: process.env.WORKFLOW_BROWSER_CHANNEL || 'chrome', viewport: { width: 1680, height: 1040 } });
const base = process.env.WORKFLOW_TEST_URL || 'http://127.0.0.1:8000';
const url = `${base}/agent-hub/use?project=project-1&environment=development`;
const runsUrl = `${base}/agent-hub/runs?project=project-1&environment=development`;
const choose = async (page, label, option) => { await page.getByLabel(label, { exact: true }).click(); await page.locator('.ant-select-dropdown:visible,.mp-ant-select-dropdown:visible').getByText(option, { exact: true }).click(); };
const languageGroup = page => page.getByRole('group', { name: '示例语言', exact: true });
const chooseLanguage = async (page, option) => { await languageGroup(page).getByText(option, { exact: true }).click(); };
async function fixtureFor(page, { active = true, writable = true, runtimeEnvironment = 'development', executionEnabled = true, admin = true } = {}) {
  const fixture = await installWorkflowFixture(page, { writable }); fixture.credentialReads = 0; fixture.calls = []; fixture.statusReads = 0;
  const deployment = fixture.deployments.get('project-1');
  if (active) {
    deployment.activeReleaseId = 'active-2'; deployment.publishedReleaseId = 'published-3';
    const tasks = [{ key: 'main', name: '综合采集与治理评估' }, { key: 'quality_check', name: '数据质量检查' }].map(task => ({ ...task, modelRevisionId: 'model-1', nodes: [], systemInstructions: ['local fixture'] }));
    fixture.releases.set('project-1', [{ id: 'published-3', sequence: 3, note: '尚未上线的验收版本', snapshot: { tasks: [{ ...tasks[0], key: 'future', name: '尚未上线的任务' }] } }, { id: 'active-2', sequence: 2, note: '运行版本验收示例', snapshot: { tasks } }]);
  }
  let current;
  await page.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    if (path.endsWith('/fusion/session')) return respond({ identity: { principal: '本地验收', projects: ['project-1', 'project-2'], writable, admin }, csrf: 'test-csrf', localAuthentication: true });
    if (path.endsWith('/fusion/status')) { fixture.statusReads++; return respond({ enabled: true, executionEnabled, runtimeEnvironment, localAuthentication: true }); }
    if (path.endsWith('/platforms/project-1')) { fixture.credentialReads++; return respond({ code: 200, data: { id: 'project-1', name: '编排验收示例', token: null } }); }
    if (path.includes('/fusion/projects/project-1/') && (/\/runs(?:\/|$)/.test(path) || path.includes('/sessions/'))) {
      if (request.method() === 'POST') {
        if (!writable || request.headers()['x-fusion-csrf'] !== 'test-csrf') return respond({ detail: 'Forbidden' }, 403);
        const body = request.postData() ? request.postDataJSON() : null; fixture.calls.push({ path, body });
        if (path.endsWith('/close')) return respond({ status: 'closed' });
        current = { id: `run-${fixture.calls.length}`, release_id: 'active-2', task_key: body.taskKey, session_id: body.sessionId || 'session-1', status: 'queued', input: body.input, output: '', last_event: 2, created_at: new Date().toISOString() }; return respond(current, 202);
      }
      if (path.endsWith('/events')) return respond(new URL(request.url()).searchParams.get('after') === '0' ? [{ sequence: 1, type: 'started', data: {} }, { sequence: 2, type: 'completed', data: { output: '合成验收：已完成质量检查。' } }] : []);
      if (/\/runs\/run-/.test(path)) return respond({ ...current, status: 'succeeded', output: '合成验收：已完成质量检查。' });
      if (path.endsWith('/runs')) return respond(current ? [{ ...current, status: 'succeeded', origin: 'agenthub' }] : []);
    }
    return route.fallback();
  });
  return fixture;
}
test('API page is visible without direct use and does not submit runs', async ({ page }) => {
  const fixture = await fixtureFor(page); const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(url); await expect(page.getByText('已上线 R2', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'API 接入', exact: true })).toBeVisible();
  await expect(page.getByText('直接使用', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('tab', { name: '直接使用', exact: true })).toHaveCount(0);
  await expect(page.getByRole('tab', { name: 'API 接入', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '运行任务', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '新会话', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '取消运行', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('任务输入', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('调用示例', { exact: true })).toContainText('HttpClient');
  await page.getByRole('button', { name: '上线版本与运行记录', exact: true }).click(); await expect(page).toHaveURL(/\/runs\?/);
  expect(fixture.calls).toEqual([]); expect(fixture.mutations).toEqual([]); expect(errors).toEqual([]); expect(fixture.credentialReads).toBe(0);
});
test('run records keep task execution and session controls', async ({ page }) => {
  const fixture = await fixtureFor(page); const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(runsUrl); await expect(page.getByText('运行中 R2', { exact: true })).toBeVisible();
  await choose(page, '执行任务', '数据质量检查'); await page.getByLabel('任务输入', { exact: true }).fill('仅为本地验收的合成数据。');
  await page.getByRole('button', { name: '运行任务', exact: true }).click(); await expect(page.getByText('合成验收：已完成质量检查。', { exact: true })).toBeVisible();
  expect(fixture.calls[0].body.taskKey).toBe('quality_check'); expect(fixture.calls[0].body.idempotencyKey).toBeTruthy();
  await page.getByLabel('任务输入', { exact: true }).fill('继续本地合成分析。'); await page.getByRole('button', { name: '运行任务', exact: true }).click();
  await expect.poll(() => fixture.calls.length).toBe(2); expect(fixture.calls[1].body.sessionId).toBe('session-1');
  await expect(page.getByRole('button', { name: '新会话', exact: true })).toBeEnabled(); await page.getByRole('button', { name: '新会话', exact: true }).click();
  await expect.poll(() => fixture.calls.length).toBe(3); expect(fixture.calls[2].path).toMatch(/sessions\/session-1\/close$/);
  await expect(page.getByText('调试', { exact: true })).toBeVisible(); expect(errors).toEqual([]); expect(fixture.credentialReads).toBe(0);
});
test('API examples use the active task and fetch credentials only on explicit request', async ({ page }) => {
  const fixture = await fixtureFor(page);
  await page.addInitScript(() => { Object.defineProperty(navigator, 'clipboard', { value: { writeText: async value => { window.__copiedText = value; } }, configurable: true }); });
  await page.goto(url);
  await expect(page.getByRole('heading', { name: /接入配置/ })).toBeVisible();
  await expect(page.getByLabel('调用示例', { exact: true })).toContainText('HttpClient');
  await expect(languageGroup(page)).toContainText('Java');
  await expect(languageGroup(page)).toContainText('React');
  await expect(languageGroup(page)).not.toContainText('Python');
  await expect(languageGroup(page)).not.toContainText('cURL');
  expect(fixture.credentialReads).toBe(0);
  await choose(page, '选择调用任务', '数据质量检查'); await expect(page.getByLabel('调用示例', { exact: true })).toContainText('quality_check');
  await expect(page.getByLabel('调用示例', { exact: true })).not.toContainText('future'); await expect(page.getByText('尚未上线的任务', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '复制示例', exact: true }).click(); const copied = await page.evaluate(() => window.__copiedText);
  expect(copied).toContain('/api/runtime/agent'); expect(copied).toContain('MIDPLAT_TOKEN'); expect(copied).toContain('HttpClient'); expect(copied).not.toContain('LOCAL-FIXTURE-SECRET');
  // W1/W5 语义：项目不再回传明文凭证，入口直接跳转「客户与凭证」。
  const artifacts = path.resolve(__dirname, '../docs/验收证据/智能体使用'); fs.mkdirSync(artifacts, { recursive: true });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(artifacts, '使用与接入-桌面.png'), animations: 'disabled' });
  await page.getByRole('button', { name: '前往签发凭证', exact: true }).click();
  await expect(page).toHaveURL(/\/access$/);
  expect(fixture.credentialReads).toBe(0);
  expect(fixture.calls).toEqual([]);
});
test('unpublished agents explain the next step and have no executable example', async ({ page }) => {
  await fixtureFor(page, { active: false }); await page.goto(url);
  await expect(page.getByRole('button', { name: '运行任务', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '前往发布', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '复制示例', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('调用示例', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '前往发布', exact: true }).click(); await expect(page).toHaveURL(/releases/);
});
test('legacy environment query does not change the service data source', async ({ page }) => {
  await fixtureFor(page); await page.goto(`${base}/agent-hub/use?project=project-1&environment=staging`);
  await expect(page.getByText('已上线 R2', { exact: true })).toBeVisible();
  await expect(page.getByLabel('当前环境')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '查看接口环境', exact: true })).toHaveCount(0);
  await expect(page).not.toHaveURL(/environment=/);
  await expect(page).toHaveURL(/project=project-1/);
  await expect(page.getByRole('button', { name: '复制示例', exact: true })).toBeVisible();
  await choose(page, '选择调用任务', '数据质量检查');
  await expect(page.getByLabel('调用示例', { exact: true })).toContainText('quality_check');
});
test('disabled execution stays read-only and recheck only rereads status', async ({ page }) => {
  const fixture = await fixtureFor(page, { runtimeEnvironment: 'development', executionEnabled: false }); await page.goto(url);
  await expect(page.getByRole('button', { name: '运行任务', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '复制示例', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '前往发布', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '前往上线', exact: true })).toHaveCount(0);
  const statusReads = fixture.statusReads;
  await page.getByRole('button', { name: '重新检查', exact: true }).click();
  await expect.poll(() => fixture.statusReads).toBeGreaterThan(statusReads);
  expect(fixture.calls).toEqual([]); expect(fixture.mutations).toEqual([]); await expect(page).toHaveURL(/\/use\?/);
});
test('missing runtime environment does not generate executable examples', async ({ page }) => {
  const fixture = await fixtureFor(page, { runtimeEnvironment: null }); await page.goto(url);
  await expect(page.getByText('工作台服务配置尚未就绪', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '重试', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '运行任务', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '复制示例', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('调用示例', { exact: true })).toHaveCount(0);
  expect(fixture.calls).toEqual([]);
});
test('writable non-admin can select tasks without opening project credentials', async ({ page }) => {
  const fixture = await fixtureFor(page, { admin: false }); await page.goto(url);
  await choose(page, '选择调用任务', '数据质量检查');
  await expect(page.getByLabel('调用示例', { exact: true })).toBeVisible();
  await expect(page.getByLabel('调用示例', { exact: true })).toContainText('MIDPLAT_TOKEN');
  await expect(page.getByLabel('调用示例', { exact: true })).not.toContainText('LOCAL-FIXTURE-SECRET');
  await expect(page.getByRole('button', { name: '前往签发凭证', exact: true })).toHaveCount(0);
  expect(fixture.credentialReads).toBe(0);
});
test('read-only and narrow layouts preserve boundaries without exposing credentials', async ({ page }) => {
  const fixture = await fixtureFor(page, { writable: false }); await page.setViewportSize({ width: 414, height: 896 }); await page.goto(url);
  await expect(page.getByRole('button', { name: '运行任务', exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await expect(page.getByRole('button', { name: '前往签发凭证', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('调用示例', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  const artifacts = path.resolve(__dirname, '../docs/验收证据/智能体使用'); fs.mkdirSync(artifacts, { recursive: true });
  await page.screenshot({ path: path.join(artifacts, '使用与接入-窄屏.png'), animations: 'disabled' });
  expect(fixture.credentialReads).toBe(0); expect(fixture.calls).toEqual([]);
});
