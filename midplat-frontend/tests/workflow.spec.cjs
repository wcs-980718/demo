const { test, expect } = require('@playwright/test');
const { installWorkflowFixture } = require('./workflowFixture.cjs');
const path = require('node:path');
const fs = require('node:fs');
test.use({ browserName: 'chromium', channel: process.env.WORKFLOW_BROWSER_CHANNEL || 'chrome', viewport: { width: 1680, height: 1040 } });
const base = process.env.WORKFLOW_TEST_URL || 'http://127.0.0.1:8000';
const url = `${base}/agent-hub/workflows?project=project-1&environment=development`;
const choose = async (page, label, option) => { await page.getByLabel(label, { exact: true }).click(); await page.locator('.ant-select-dropdown:visible,.mp-ant-select-dropdown:visible').getByText(option, { exact: true }).click(); };
const replaceTemplate = async (page, name) => { await page.locator('.workflow-template-list').getByRole('button', { name: new RegExp(`^${name}`) }).click(); const confirm = page.getByRole('button', { name: '使用模板', exact: true }); if (await confirm.isVisible()) await confirm.click(); };
test('parallel graph: add, drag, connect, configure, generate, and reload', async ({ page }) => {
  const fixture = await installWorkflowFixture(page); const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(url); await replaceTemplate(page, '并行分析');
  await page.getByRole('button', { name: '适应全部节点' }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(7);
  await page.locator('.react-flow__node[data-id="summary"]').click();
  await page.getByLabel('编排节点指令', { exact: true }).fill('提炼业务要点，保留可核验依据。');
  const card = page.locator('.react-flow__node[data-id="summary"]'); const before = await card.boundingBox();
  await page.mouse.move(before.x + 45, before.y + 25); await page.mouse.down(); await page.mouse.move(before.x + 85, before.y + 65, { steps: 12 }); await page.mouse.up();
  const after = await card.boundingBox(); expect(Math.abs(after.x - before.x)).toBeGreaterThan(20);
  // Delete and reconnect an actual edge with a pointer drag.
  await page.locator('.react-flow__node[data-id="fork"]').click();
  await page.getByRole('button', { name: '删除连线 并行分支 · 并行分析 到 模型处理 · 风险分析' }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(6);
  const source = await page.locator('.react-flow__node[data-id="fork"] .source').boundingBox();
  const target = await page.locator('.react-flow__node[data-id="risk"] .target').boundingBox();
  await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2); await page.mouse.down(); await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2, { steps: 15 }); await page.mouse.up();
  await expect(page.locator('.react-flow__edge')).toHaveCount(7);
  // Drag a new card from the palette, check disconnected-node validation, then remove it.
  await page.getByRole('button', { name: '添加模型处理节点', exact: true }).dragTo(page.getByRole('region', { name: '智能体拖拽编排画布' }), { targetPosition: { x: 220, y: 200 } });
  await expect(page.locator('.react-flow__node')).toHaveCount(8);
  await page.getByRole('button', { name: '生成智能体', exact: true }).click(); await expect(page.getByText(/所有节点必须连通开始与输出/)).toBeVisible();
  await page.getByRole('button', { name: '删除选中节点' }).click(); await expect(page.locator('.react-flow__node')).toHaveCount(7);
  await page.getByRole('button', { name: '生成智能体', exact: true }).click(); await expect(page.getByText('编排检查通过')).toBeVisible();
  await page.getByRole('button', { name: '生成并保存', exact: true }).click(); await expect(page.getByText('智能体配置已生成', { exact: true })).toBeVisible();
  const saved = fixture.deployments.get('project-1'); expect(saved.draft.tasks[0].workflow.edges).toHaveLength(7); expect(saved.draft.tasks[0].nodes.find(node => node.id === 'summary').instructions).toContain('可核验');
  expect(saved.draft.tasks[0].workflow.positions.find(node => node.id === 'summary').x).not.toBe(640);
  expect(fixture.mutations.some(item => item.path.endsWith('/draft/generate'))).toBe(true);
  await page.reload(); await page.getByRole('button', { name: '适应全部节点' }).click();
  await page.locator('.react-flow__node[data-id="summary"]').click(); await expect(page.getByLabel('编排节点指令', { exact: true })).toHaveValue('提炼业务要点，保留可核验依据。');
  const artifact = path.resolve(__dirname, '../docs/验收证据/智能体编排'); fs.mkdirSync(artifact, { recursive: true });
  await page.screenshot({ path: path.join(artifact, '并行编排.png') });
  expect(errors).toEqual([]);
});
test('condition settings, model and prompt associations persist after generation', async ({ page }) => {
  const fixture = await installWorkflowFixture(page); await page.goto(url); await replaceTemplate(page, '条件路由');
  await page.getByRole('button', { name: '智能体配置', exact: true }).click();
  await choose(page, '编排角色来源', '关联提示词模板'); await choose(page, '编排提示词模板', '企业助手模板 · 修订 · p1234567');
  await page.getByRole('tab', { name: '节点配置', exact: true }).click(); await choose(page, '选择编排节点', '条件分支 · 是否需要报告');
  await page.getByLabel('条件比较值', { exact: true }).fill('紧急');
  await page.getByRole('button', { name: '生成智能体', exact: true }).click(); await page.getByRole('button', { name: '生成并保存', exact: true }).click(); await expect(page.getByText('智能体配置已生成', { exact: true })).toBeVisible();
  const draft = fixture.deployments.get('project-1').draft; expect(draft.role).toEqual({ kind: 'template', revisionId: 'prompt-1' });
  expect(draft.tasks[0].nodes.find(node => node.kind === 'condition').condition.value).toBe('紧急');
  expect(draft.tasks[0].workflow.edges.filter(edge => edge.sourceHandle).map(edge => edge.sourceHandle).sort()).toEqual(['false', 'true']);
  fixture.releases.set('project-1', [{ id: 'release-1', sequence: 1, note: '条件编排快照验收', hash: 'snapshot-hash', createdAt: new Date().toISOString(), snapshot: {
    draft: structuredClone(draft), effectiveRole: '企业助手', compilerVersion: 'fusion-config-v3', models: { 'model-1': { content: { name: '通用对话模型' } } },
    tasks: draft.tasks.map(task => ({ ...task, modelRevisionId: 'model-1', systemInstructions: ['task rules'], nodes: task.nodes.map(node => node.kind === 'llm' ? { ...node, modelRevisionId: 'model-1', systemInstructions: ['node rules'] } : { id: node.id, name: node.name, kind: node.kind, condition: node.condition }) })),
  } }]);
  await page.getByRole('button', { name: '前往发布', exact: true }).click(); await page.reload();
  await page.getByRole('button', { name: '查看快照', exact: true }).click(); await expect(page.getByRole('heading', { name: '流程关联', exact: true })).toBeVisible();
  await expect(page.getByText('用户输入 包含 紧急', { exact: true })).toBeVisible();
});
test('conflicts preserve local edits and leaving an unsaved workflow is guarded', async ({ page }) => {
  const fixture = await installWorkflowFixture(page); await page.goto(url); await replaceTemplate(page, '顺序处理');
  await page.getByLabel('编排任务名称', { exact: true }).fill('保留这份修改'); fixture.conflict = true;
  await page.getByRole('button', { name: '保存草稿', exact: true }).click(); await expect(page.getByText('操作未完成', { exact: true })).toBeVisible();
  await expect(page.getByLabel('编排任务名称', { exact: true })).toHaveValue('保留这份修改');
  await page.getByRole('button', { name: '关闭编排编辑器', exact: true }).click(); await expect(page.getByRole('button', { name: '继续编辑', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page).toHaveURL(/workflows/);
});
test('new agents go straight to the workflow editor after creation', async ({ page }) => {
  const fixture = await installWorkflowFixture(page); await page.goto(`${base}/agent-hub/agents`);
  await page.getByRole('button', { name: '新建智能体', exact: true }).click(); await page.getByLabel('智能体名称', { exact: true }).fill('新增编排智能体');
  await page.getByLabel('首个任务名称', { exact: true }).fill('业务分析'); await page.getByRole('button', { name: '创建并开始编排', exact: true }).click();
  await expect(page).toHaveURL(/workflows\?agent=[^&]+&mode=create/); await expect(page.getByRole('heading', { name: '新建智能体编排', exact: true })).toBeVisible();
  expect([...fixture.agents.values()][0].summary.name).toBe('新增编排智能体');
});
test('read-only identity cannot edit and narrow layouts remain inside the viewport', async ({ page }) => {
  const fixture = await installWorkflowFixture(page, { writable: false }); await page.setViewportSize({ width: 414, height: 896 }); await page.goto(url);
  await expect(page.getByRole('button', { name: '生成智能体', exact: true })).toBeDisabled(); await expect(page.getByRole('button', { name: '添加条件分支节点', exact: true })).toBeDisabled();
  await expect(page.getByLabel('编排任务名称', { exact: true })).toBeDisabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true); expect(fixture.mutations).toEqual([]);
});

test('illustrated guide walks through all steps and preserves an unsaved draft', async ({ page }) => {
  const fixture = await installWorkflowFixture(page); const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(url); await page.getByLabel('编排任务名称', { exact: true }).fill('教程打开前的未保存任务');
  const trigger = page.getByRole('button', { name: '图解教程', exact: true }); await trigger.click();
  const guide = page.getByRole('dialog', { name: '可视化编排 · 图解教程', exact: true });
  await expect(guide).toBeVisible(); await expect(guide.getByRole('button', { name: '上一步', exact: true })).toBeDisabled();
  for (let step = 1; step <= 6; step++) {
    await expect(guide.getByRole('img', { name: new RegExp(`^第 ${step} 步示意图`) })).toBeVisible();
    await expect(guide.getByText(`第 ${step} 步 / 共 6 步`, { exact: true })).toBeVisible();
    if (step < 6) await guide.getByRole('button', { name: '下一步', exact: true }).click();
  }
  await guide.getByRole('tab', { name: '节点功能', exact: true }).click();
  await expect(guide.getByRole('heading', { name: '结果汇合', exact: true })).toBeVisible();
  await expect(guide.getByRole('img', { name: /并行分析：两项分析同时执行/ })).toBeAttached();
  await guide.getByRole('tab', { name: '常见问题', exact: true }).click();
  await expect(guide.getByRole('heading', { name: '上线此版本', exact: true })).toBeVisible();
  await page.keyboard.press('Escape'); await expect(guide).not.toBeVisible(); await expect(trigger).toBeFocused();
  await expect(page.getByLabel('编排任务名称', { exact: true })).toHaveValue('教程打开前的未保存任务');
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled(); expect(fixture.mutations).toEqual([]);
  // The handbook must also sit above the full-page editor and close back into it.
  await trigger.click();
  await expect(guide).toBeVisible(); await expect(guide.getByText('第 1 步 / 共 6 步', { exact: true })).toBeVisible();
  const artifact = path.resolve(__dirname, '../docs/验收证据/智能体编排'); fs.mkdirSync(artifact, { recursive: true });
  // rc-motion mounts the dialog at opacity 0 before creating its animation.
  // Wait for its painted state; disabling animations alone can capture that prepare frame.
  await expect(guide).toHaveCSS('opacity', '1'); await expect(guide).toHaveCSS('transform', 'none');
  await page.screenshot({ path: path.join(artifact, '图解教程-桌面.png'), animations: 'disabled' });
  await guide.getByRole('button', { name: '查看大图', exact: true }).click();
  const zoom = page.getByRole('dialog', { name: '第 1 步 · 配置模型和角色', exact: true }); await expect(zoom).toBeVisible();
  await zoom.getByRole('button', { name: '返回教程', exact: true }).click(); await expect(zoom).not.toBeVisible(); await expect(guide.getByRole('button', { name: '查看大图', exact: true })).toBeFocused();
  await page.keyboard.press('Escape'); await expect(guide).not.toBeVisible(); await expect(page.locator('.workflow-editor')).toBeVisible();
  expect(errors).toEqual([]); expect(fixture.mutations).toEqual([]);
});

test('workflow page renders only the editor and closes back to the agent list', async ({ page }) => {
  await installWorkflowFixture(page); await page.goto(url);
  await expect(page.locator('.workflow-editor')).toBeVisible();
  // 简化：工作流页不再渲染页面级面包屑与标题横幅，也没有展开/收起按钮
  await expect(page.locator('.hub-page-header')).toHaveCount(0);
  await expect(page.locator('.hub-breadcrumb')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '展开编排编辑器', exact: true })).toHaveCount(0);
  await expect(page.locator('.workflow-editor-footer')).toContainText('支持条件与并行');
  await page.getByRole('button', { name: '关闭编排编辑器', exact: true }).click();
  await expect(page).toHaveURL(/\/agent-hub\/agents/);
});

test('长模型名在检查器内用省略号收尾，不撑破面板', async ({ page }) => {
  const fixture = await installWorkflowFixture(page);
  fixture.models.push({ id: 'model-long', resourceId: 'model', hash: 'b1234567890123', content: { name: '超长模型名称用于验证省略号收尾且不撑破检查器面板', model: 'long-test-model-0123456789', kind: 'llm' } });
  fixture.deployments.get('project-1').draft.defaultModelRevisionId = 'model-long';
  await page.goto(url);
  await page.getByRole('tab', { name: '智能体配置', exact: true }).click();
  const value = page.locator('.workflow-inspector .mp-ant-select-content:visible, .workflow-inspector .ant-select-content:visible').first();
  await expect(value).toBeVisible();
  await expect(value).toHaveCSS('text-overflow', 'ellipsis');
  // 文本确实被截断（scrollWidth 大于可见宽度），且没有溢出检查器面板
  expect(await value.evaluate(node => node.scrollWidth > node.clientWidth)).toBe(true);
  const panel = await page.locator('.workflow-inspector').boundingBox();
  const box = await value.boundingBox();
  expect(box.x + box.width).toBeLessThanOrEqual(panel.x + panel.width + 1);
  expect(await page.locator('.workflow-inspector').evaluate(node => node.scrollWidth > node.clientWidth + 1)).toBe(false);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  // 任务栏里的长任务名同样省略号收尾，不撑开任务行
  fixture.deployments.get('project-1').draft.tasks[0].name = '超长任务名称用于验证任务栏下拉省略号收尾且不撑破布局';
  await page.reload();
  const task = page.locator('.workflow-taskbar .mp-ant-select-content:visible, .workflow-taskbar .ant-select-content:visible').first();
  await expect(task).toHaveCSS('text-overflow', 'ellipsis');
  expect(await task.evaluate(node => node.scrollWidth > node.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});

test('read-only users can use the guide on a narrow screen and enlarge each picture', async ({ page }) => {
  const fixture = await installWorkflowFixture(page, { writable: false }); await page.setViewportSize({ width: 414, height: 896 }); await page.goto(url);
  await page.getByRole('button', { name: '图解教程', exact: true }).click();
  const guide = page.getByRole('dialog', { name: '可视化编排 · 图解教程', exact: true }); await expect(guide).toBeVisible();
  await guide.getByRole('button', { name: /4 连接执行流程/ }).click();
  await expect(guide.getByRole('heading', { name: '用连线决定执行顺序', exact: true })).toBeVisible();
  let box = await guide.boundingBox(); expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(414);
  const artifact = path.resolve(__dirname, '../docs/验收证据/智能体编排'); fs.mkdirSync(artifact, { recursive: true });
  await expect(guide).toHaveCSS('opacity', '1'); await expect(guide).toHaveCSS('transform', 'none');
  const footer = await guide.locator('.workflow-guide-footer').boundingBox(); expect(footer.y + footer.height).toBeLessThanOrEqual(896);
  await page.screenshot({ path: path.join(artifact, '图解教程-窄屏.png'), animations: 'disabled' });
  await guide.getByRole('button', { name: '查看大图', exact: true }).click();
  const zoom = page.getByRole('dialog', { name: '第 4 步 · 用连线决定执行顺序', exact: true }); await expect(zoom).toBeVisible();
  const picture = zoom.getByRole('region', { name: '放大后的步骤示意图，可横向滚动' });
  expect(await picture.evaluate(node => node.scrollWidth > node.clientWidth)).toBe(true);
  await picture.focus(); await page.keyboard.press('ArrowRight'); await expect.poll(() => picture.evaluate(node => node.scrollLeft)).toBeGreaterThan(0);
  await page.keyboard.press('Escape'); await expect(zoom).not.toBeVisible(); await expect(guide).toBeVisible();
  await page.keyboard.press('Escape'); await expect(guide).not.toBeVisible();
  await expect(page.getByRole('button', { name: '生成智能体', exact: true })).toBeDisabled(); expect(fixture.mutations).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});
