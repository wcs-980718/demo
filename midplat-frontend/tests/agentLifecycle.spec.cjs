const { test, expect } = require('@playwright/test');
test.use({ browserName: 'chromium', channel: process.env.WORKFLOW_BROWSER_CHANNEL || 'chrome', viewport: { width: 1680, height: 1040 } });
const base = process.env.WORKFLOW_TEST_URL || 'http://127.0.0.1:8000';

const installAgentFixture = async (page, { releaseState = 'none', includeNonDefault = false, allUnbound = false, foreignEnvReady = false } = {}) => {
  const state = { publishCalls: 0, activationCalls: 0, runCalls: 0, deleteCalls: 0, updateCalls: 0, unbindCalls: [] };
  const projects = [{ id: 'project-1', name: '演示项目' }, { id: 'project-2', name: '备用项目' }];
  const draft = { defaultModelRevisionId: 'model-1', temperature: 0.4, role: { kind: 'inline', body: '你是质检助手。' }, tasks: [{ key: 'main', name: '质量检查', instructions: '检查输出质量。', nodes: [] }] };
  const agent = {
    summary: { id: 'agent-1', name: '独立质检智能体', description: '', status: 'ready', draftRevision: 3, latestVersionId: 'av-1', latestVersionSequence: 1, sourceProjectId: null, portable: true },
    draft, expectedRevision: 3,
    versions: [{ id: 'av-1', agentId: 'agent-1', sequence: 1, hash: 'agenthash00001', note: '首个版本', createdAt: '2026-01-01T00:00:00Z', content: draft }],
  };
  const release = { id: 'rel-1', sequence: 1, note: '可上线版本', hash: 'releasehash0001', createdAt: '2026-01-02T00:00:00Z', snapshot: { draft, effectiveRole: '你是质检助手。', compilerVersion: 'fusion-config-v3', models: { 'model-1': { content: { name: '通用对话模型' } } }, tasks: [{ key: 'main', name: '质量检查', modelRevisionId: 'model-1', systemInstructions: ['本地验收'], nodes: [] }] } };
  const deployments = {
    'dep-1': { id: 'dep-1', projectId: 'project-1', environment: 'development', definitionId: 'agent-1', name: '独立质检智能体', revision: 1, draft, publishedReleaseId: releaseState !== 'none' ? 'rel-1' : null, activeReleaseId: releaseState === 'active' ? 'rel-1' : null, pendingJobId: null, activationRevision: 0 },
    'dep-2': { id: 'dep-2', projectId: 'project-1', environment: 'development', definitionId: 'agent-1', name: '独立质检智能体 · 备用', revision: 1, draft, publishedReleaseId: null, activeReleaseId: null, pendingJobId: null, activationRevision: 0 },
  };
  const bindings = [
    { binding_id: 'bnd-1', project_id: 'project-1', environment: 'development', deployment_id: 'dep-1', agent_id: 'agent-1', desired_agent_version_id: 'av-1', alias: 'default', status: 'ready', is_default: true, last_error: null, generation: 1, operation_id: null },
    { binding_id: 'bnd-2', project_id: 'project-1', environment: 'development', deployment_id: 'dep-2', agent_id: 'agent-1', desired_agent_version_id: 'av-1', alias: 'alternate', status: 'ready', is_default: false, last_error: null, generation: 1, operation_id: null },
  ];
  const agents = [agent.summary];
  if (includeNonDefault) {
    agents.push({ id: 'agent-2', name: '独立巡检智能体', description: '', status: 'ready', draftRevision: 1, latestVersionId: 'av-9', latestVersionSequence: 1, sourceProjectId: null, portable: true });
    bindings.push({ binding_id: 'bnd-3', project_id: 'project-1', environment: 'development', deployment_id: 'dep-3', agent_id: 'agent-2', desired_agent_version_id: 'av-9', alias: 'inspection', status: 'ready', is_default: false, last_error: null, generation: 1, operation_id: null });
  }
  if (allUnbound) bindings.forEach(binding => { binding.status = 'unbound'; binding.is_default = false; });
  // 非界面可选环境的生效绑定（历史 QA 数据等）：卡片与详情仍须可见、可解绑，并与删除校验同口径。
  if (foreignEnvReady) bindings.push({ binding_id: 'bnd-qa', project_id: 'project-2', environment: 'workflow_qa_20260910', deployment_id: 'dep-qa', agent_id: 'agent-1', desired_agent_version_id: 'av-1', alias: 'default', status: 'ready', is_default: true, last_error: null, generation: 1, operation_id: null });
  const run = { id: 'run-1', release_id: 'rel-1', session_id: 'ses-1', task_key: 'main', principal: '本地验收', input: '', output: 'E2E测试输出：质量检查通过', status: 'succeeded', error: null, created_at: '2026-01-02T00:00:00Z', started_at: '2026-01-02T00:00:01Z', finished_at: '2026-01-02T00:00:03Z', last_event: 0 };
  const catalog = { models: [{ id: 'model-1', resourceId: 'model', hash: 'a1234567890123', content: { name: '通用对话模型', model: 'local-test-model', kind: 'llm' } }], availableModels: [{ id: 'model-1', resourceId: 'model', hash: 'a1234567890123', content: { name: '通用对话模型', model: 'local-test-model', kind: 'llm' } }], prompts: [{ id: 'prompt-1', resourceId: 'prompt', hash: 'p12345678', content: { name: '企业助手模板', body: '你是企业业务助手。' } }], grants: [{ model_id: 'model', enabled: true }] };
  const assets = [{ id: 'skill', name: '结构化分析指南', kind: 'skill', enabled: true, revision: 1, revisionId: 'skill-1', hash: 'skillhash', content: { body: '先列事实，再给结论。' } }];
  const menus = [{ id: 'menu-1', parentId: null, name: '开发中心', routeName: 'capabilities', path: '/capabilities', filePath: 'src/pages/capabilities/index.tsx', icon: 'Code2', platformId: 'project-1', platformIds: ['project-1'], sortOrder: 1, visible: true, locked: false, children: [] }];
  const platforms = projects.map(project => ({ id: project.id, name: project.name, entryUrl: null, icon: '', consume: false, token: null, llmModelId: null, embeddingModelId: null, rerankModelId: null }));
  const defaultBinding = project => bindings.find(binding => binding.project_id === project && binding.is_default);
  await page.route('**/api/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname;
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    if (!path.includes('/fusion/')) {
      if (path.endsWith('/menus')) return respond({ success: true, data: menus, message: null });
      if (path.endsWith('/platforms')) return respond({ success: true, data: platforms, message: null });
      return respond({ success: true, data: [], message: null });
    }
    if (path.endsWith('/status')) return respond({ enabled: true, executionEnabled: true, runtimeEnvironment: 'development', localAuthentication: true });
    if (path.endsWith('/session')) return respond({ identity: { principal: '本地验收', projects: projects.map(project => project.id), writable: true, admin: true }, csrf: 'test-csrf', localAuthentication: true });
    if (path.endsWith('/projects')) return respond(projects);
    if (path.endsWith('/bindings')) return respond(bindings);
    if (path.endsWith('/agents') && request.method() === 'GET') return respond(agents);
    const project = path.match(/\/projects\/([^/]+)\//)?.[1];
    const setDefaultPath = path.match(/\/projects\/([^/]+)\/agent-bindings\/([^/]+)\/default$/);
    if (setDefaultPath && request.method() === 'POST') {
      bindings.forEach(binding => { if (binding.project_id === setDefaultPath[1]) binding.is_default = binding.binding_id === setDefaultPath[2]; });
      return respond(bindings.filter(binding => binding.project_id === setDefaultPath[1]));
    }
    const unbindPath = path.match(/\/projects\/([^/]+)\/agent-bindings\/([^/]+)$/);
    if (unbindPath && request.method() === 'DELETE') {
      state.unbindCalls.push({ project: unbindPath[1], env: url.searchParams.get('environment'), id: unbindPath[2] });
      const target = bindings.find(binding => binding.binding_id === unbindPath[2]);
      if (target) { target.status = 'unbound'; target.is_default = false; target.alias = `unbound-${target.binding_id}`; }
      return respond(bindings.filter(binding => binding.project_id === unbindPath[1]));
    }
    if (path.endsWith('/agent-bindings')) return respond(bindings.filter(binding => binding.project_id === project));
    if (/\/agents\/[^/]+\/versions$/.test(path)) {
      if (request.method() === 'POST') { state.publishCalls += 1; return respond({ ...agent.summary, draft: agent.draft, expectedRevision: agent.expectedRevision + 1 }); }
      return respond(agent.versions);
    }
    if (/\/agents\/[^/]+\/draft$/.test(path)) return respond({ ...agent.summary, draft: agent.draft, expectedRevision: agent.expectedRevision });
    if (/\/agents\/[^/]+$/.test(path) && request.method() === 'PUT') {
      state.updateCalls += 1;
      const agentId = decodeURIComponent(path.split('/').pop());
      const body = request.postDataJSON();
      const item = agents.find(entry => entry.id === agentId);
      if (!item) return respond({ detail: '智能体不存在' }, 404);
      item.name = body.name; item.description = body.description || '';
      return respond(item);
    }
    if (/\/agents\/[^/]+$/.test(path) && request.method() === 'DELETE') {
      state.deleteCalls += 1;
      const agentId = decodeURIComponent(path.split('/').pop());
      const index = agents.findIndex(item => item.id === agentId);
      if (index >= 0) agents.splice(index, 1);
      return respond({ deleted: agentId, deployments: 0 });
    }
    if (path.endsWith('/activations') && request.method() === 'POST') {
      const body = request.postDataJSON(); const deployment = deployments[defaultBinding(project)?.deployment_id];
      state.activationCalls += 1; deployment.activeReleaseId = body.releaseId; deployment.activationRevision += 1; return respond(deployment);
    }
    if (path.endsWith('/runs') && request.method() === 'POST') { state.runCalls += 1; const body = request.postDataJSON(); return respond({ ...run, input: body.input, task_key: body.taskKey }); }
    if (path.endsWith('/runs')) return respond([]);
    if (path.endsWith('/runs/run-1/events')) return respond([]);
    if (path.endsWith('/runs/run-1')) return respond(run);
    if (path.endsWith('/effective-config')) { const current = defaultBinding(project); return current ? respond(deployments[current.deployment_id]) : respond({ detail: '部署尚未绑定' }, 404); }
    if (path.endsWith('/releases')) { const current = defaultBinding(project); return respond(current?.deployment_id === 'dep-1' && releaseState !== 'none' ? [release] : []); }
    if (path.endsWith('/catalog')) return respond(catalog);
    if (path.endsWith('/assets')) return respond(assets);
    if (path.endsWith('/audit')) return respond([]);
    return respond([]);
  });
  return state;
};

test('bound agent detail shows default route state and prepares release entry', async ({ page }) => {
  await installAgentFixture(page);
  await page.goto(`${base}/agent-hub/agents?agent=agent-1`);
  const mainRow = page.locator('.hub-binding-row', { hasText: '别名 default' });
  const altRow = page.locator('.hub-binding-row', { hasText: '别名 alternate' });
  await expect(mainRow.getByText('默认路由', { exact: true })).toBeVisible();
  await expect(mainRow.getByText('待生成运行版本', { exact: true })).toBeVisible();
  await expect(altRow.locator('.hub-binding-state')).toHaveText('设为默认后可管理');
  await expect(altRow.getByRole('button', { name: '设为默认', exact: true })).toBeVisible();
  await expect(altRow.getByRole('button', { name: '准备上线' })).toHaveCount(0);
  await expect(altRow.getByRole('button', { name: '运行调试' })).toHaveCount(0);
  await altRow.getByRole('button', { name: '设为默认', exact: true }).click();
  await expect(altRow.getByText('默认路由', { exact: true })).toBeVisible();
  await altRow.getByRole('button', { name: '准备上线', exact: true }).click();
  await expect(page).toHaveURL(/\/agent-hub\/releases\?project=project-1/);
  await expect(page.getByText('绑定只创建项目草稿；发布运行版本后才能上线。', { exact: true })).toBeVisible();
  await page.getByLabel('当前智能体', { exact: true }).click();
  await expect(page.locator('.ant-select-item-option:visible, .mp-ant-select-item-option:visible').filter({ hasText: '质检' })).toHaveCount(1);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: '发布运行版本', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '发布新版本', exact: true })).toBeVisible();
});

test('republishing an unchanged draft asks for confirmation before posting', async ({ page }) => {
  const state = await installAgentFixture(page);
  await page.goto(`${base}/agent-hub/agents?agent=agent-1`);
  await page.getByRole('button', { name: '发布版本', exact: true }).click();
  const modal = page.getByRole('dialog', { name: /发布智能体版本/ });
  await expect(modal).toBeVisible();
  await modal.getByLabel('发布说明', { exact: true }).fill('重复发布验收');
  await modal.getByRole('button', { name: '确认发布', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '内容与最新版本一致', exact: true })).toBeVisible();
  expect(state.publishCalls).toBe(0);
  await page.getByRole('button', { name: '仍然发布', exact: true }).click();
  await expect.poll(() => state.publishCalls).toBe(1);
  await expect(modal).not.toBeVisible();
});

test('unknown routes render the 404 page and the back-home action', async ({ page }) => {
  await installAgentFixture(page);
  await page.goto(`${base}/model-management`);
  await expect(page.getByText('页面不存在', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '返回首页', exact: true }).click();
  await expect(page).toHaveURL(/\/home/);
});

test('asset type filters only list kinds that exist for the project', async ({ page }) => {
  await installAgentFixture(page);
  await page.goto(`${base}/agent-hub/assets?project=project-1`);
  const tabs = page.getByRole('group', { name: '资产类型' });
  await expect(tabs.getByRole('button', { name: /全部/ })).toHaveCount(1);
  await expect(tabs.getByRole('button', { name: /模型/ })).toHaveCount(1);
  await expect(tabs.getByRole('button', { name: /提示词/ })).toHaveCount(1);
  await expect(tabs.getByRole('button', { name: /技能/ })).toHaveCount(1);
  await expect(tabs.getByRole('button', { name: /^工具/ })).toHaveCount(0);
  await expect(tabs.getByRole('button', { name: /知识库/ })).toHaveCount(0);
  await expect(tabs.getByRole('button', { name: /数据源/ })).toHaveCount(0);
});

test('prompt creation surfaces a role=alert summary on empty submit', async ({ page }) => {
  await installAgentFixture(page);
  await page.goto(`${base}/prompts`);
  await page.getByRole('button', { name: '新增提示词', exact: true }).click();
  const modal = page.getByRole('dialog', { name: '新增提示词', exact: true });
  await expect(modal).toBeVisible();
  await modal.getByRole('button', { name: '创建提示词', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('请完善必填信息');
});

test('published binding brings its release online from the runs view', async ({ page }) => {
  const state = await installAgentFixture(page, { releaseState: 'published' });
  await page.goto(`${base}/agent-hub/agents?agent=agent-1`);
  const mainRow = page.locator('.hub-binding-row', { hasText: '别名 default' });
  await expect(mainRow.getByText('待上线 R1', { exact: true })).toBeVisible();
  await mainRow.getByRole('button', { name: '上线版本', exact: true }).click();
  await expect(page).toHaveURL(/\/agent-hub\/runs\?project=project-1/);
  await page.getByRole('button', { name: '上线此版本', exact: true }).click();
  await expect.poll(() => state.activationCalls).toBe(1);
  await expect(page.getByText('运行中 R1', { exact: true })).toBeVisible();
});

test('active binding runs a task end to end from the debug entry', async ({ page }) => {
  const state = await installAgentFixture(page, { releaseState: 'active' });
  await page.goto(`${base}/agent-hub/agents?agent=agent-1`);
  const mainRow = page.locator('.hub-binding-row', { hasText: '别名 default' });
  await expect(mainRow.getByText('运行中 R1', { exact: true })).toBeVisible();
  await mainRow.getByRole('button', { name: '运行调试', exact: true }).click();
  await expect(page).toHaveURL(/\/agent-hub\/runs\?project=project-1/);
  const runButton = page.getByRole('button', { name: '运行任务', exact: true });
  await page.getByLabel('任务输入', { exact: true }).fill('E2E测试：独立智能体运行入口');
  await expect(runButton).toBeEnabled();
  await runButton.click();
  await expect.poll(() => state.runCalls).toBe(1);
  await expect(page.getByText('E2E测试输出：质量检查通过', { exact: true })).toBeVisible();
});

test('menu settings shows raw route identifier and bound platforms', async ({ page }) => {
  await installAgentFixture(page);
  await page.goto(`${base}/settings/menus`);
  const row = page.locator('tr', { hasText: '开发中心' });
  await expect(row.getByText('capabilities', { exact: true })).toBeVisible();
  await expect(row.locator('.menu-table-route')).toHaveText('页面：对外接口总览');
  const badge = row.locator('.chip-emb');
  await expect(badge).toHaveText('已绑 1 个平台');
  await badge.hover();
  await expect(page.getByRole('tooltip')).toContainText('演示项目');
});

test('agent with active bindings cannot be deleted', async ({ page }) => {
  const state = await installAgentFixture(page);
  await page.goto(`${base}/agent-hub/agents?agent=agent-1`);
  const detail = page.locator('.hub-agent-detail');
  const deleteButton = detail.getByRole('button', { name: '删除智能体' });
  await expect(deleteButton).toBeDisabled();
  await expect(deleteButton).toHaveAttribute('title', /请先解绑全部项目/);
  expect(state.deleteCalls).toBe(0);
});

test('agent with only unbound bindings can be permanently deleted', async ({ page }) => {
  const state = await installAgentFixture(page, { allUnbound: true });
  await page.goto(`${base}/agent-hub/agents?agent=agent-1`);
  const detail = page.locator('.hub-agent-detail');
  const deleteButton = detail.getByRole('button', { name: '删除智能体' });
  await expect(deleteButton).toBeEnabled();
  await deleteButton.click();
  const modal = page.getByRole('dialog', { name: /永久删除/ });
  await expect(modal).toBeVisible();
  await modal.getByRole('button', { name: '永久删除', exact: true }).click();
  await expect.poll(() => state.deleteCalls).toBe(1);
  await expect(page.getByText('智能体已删除')).toBeVisible();
  await expect(detail.getByText('选择一个智能体后，这里展示它的生命周期、模型、任务与可执行操作。')).toBeVisible();
  await expect(page.locator('.hub-agent-card', { hasText: '独立质检智能体' })).toHaveCount(0);
});

test('agent basic info can be renamed from the detail panel', async ({ page }) => {
  const state = await installAgentFixture(page);
  await page.goto(`${base}/agent-hub/agents?agent=agent-1`);
  const detail = page.locator('.hub-agent-detail');
  await detail.getByRole('button', { name: '编辑基础信息' }).click();
  const modal = page.getByRole('dialog', { name: '编辑基础信息' });
  await expect(modal).toBeVisible();
  await modal.getByLabel('智能体名称').fill('改名后质检智能体');
  await modal.getByLabel('智能体描述').fill('验收用新描述');
  await modal.getByRole('button', { name: '保存', exact: true }).click();
  await expect.poll(() => state.updateCalls).toBe(1);
  await expect(detail.locator('h2')).toHaveText('改名后质检智能体');
  await expect(detail.getByText('验收用新描述', { exact: false })).toBeVisible();
  await expect(page.locator('.hub-agent-card', { hasText: '改名后质检智能体' })).toHaveCount(1);
});

test('non-default binding never inherits the default deployment state', async ({ page }) => {
  await installAgentFixture(page, { releaseState: 'active', includeNonDefault: true });
  await page.goto(`${base}/agent-hub/agents`);
  const mainCard = page.locator('.hub-agent-card', { hasText: '独立质检智能体' });
  const otherCard = page.locator('.hub-agent-card', { hasText: '独立巡检智能体' });
  await expect(mainCard.locator('.hub-st')).toHaveText('已上线');
  await expect(otherCard.locator('.hub-st')).toHaveText('已绑定');
  await otherCard.click();
  const detail = page.locator('.hub-agent-detail');
  const boundRow = detail.locator('.hub-binding-row', { hasText: '别名 inspection' });
  await expect(boundRow.getByRole('button', { name: '设为默认', exact: true })).toBeVisible();
  await expect(detail.getByRole('button', { name: '运行调试' })).toHaveCount(0);
  await expect(detail.getByRole('button', { name: '上线版本' })).toHaveCount(0);
  await expect(detail.getByRole('button', { name: '准备上线' })).toHaveCount(0);
});

test('foreign-environment binding stays visible, uses its own env to unbind, and unblocks delete', async ({ page }) => {
  const state = await installAgentFixture(page, { allUnbound: true, foreignEnvReady: true });
  await page.goto(`${base}/agent-hub/agents?agent=agent-1`);
  // 卡片按全环境口径显示已绑定，并标注绑定所在环境
  const card = page.locator('.hub-agent-card', { hasText: '独立质检智能体' });
  await expect(card.locator('.hub-st')).toHaveText('已绑定');
  await expect(card).toContainText('备用项目（workflow_qa_20260910）');
  // 详情面板列出该绑定（带环境标签），删除被禁用
  const detail = page.locator('.hub-agent-detail');
  const row = detail.locator('.hub-binding-row', { hasText: '环境 workflow_qa_20260910' });
  await expect(row).toBeVisible();
  await expect(detail.getByRole('button', { name: '删除智能体', exact: true })).toBeDisabled();
  // 解绑用绑定自身所在环境发请求
  await row.getByRole('button', { name: '解绑', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '解除绑定', exact: true }).click();
  await expect.poll(() => state.unbindCalls.length).toBe(1);
  expect(state.unbindCalls[0]).toMatchObject({ project: 'project-2', env: 'workflow_qa_20260910', id: 'bnd-qa' });
  // 解绑后删除恢复可用
  await expect(detail.getByRole('button', { name: '删除智能体', exact: true })).toBeEnabled();
});
