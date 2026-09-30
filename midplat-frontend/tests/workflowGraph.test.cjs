const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');
const modules = {};
function load(name) {
  if (modules[name]) return modules[name];
  const file = path.resolve(__dirname, `../src/pages/agent-hub/${name}.ts`);
  const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  const exports = {}; vm.runInNewContext(code, { exports, structuredClone, require: relative => load(relative.replace('./', '')) });
  modules[name] = exports; return exports;
}
const graph = load('workflowGraph'); const templates = load('workflowTemplates');
const task = () => ({ key: 'main', name: '分析', instructions: '任务规则', assetRevisionIds: ['skill-v1'], nodes: [] });
const catalog = { models: [{ id: 'm1' }], prompts: [] };
const draft = task => ({ defaultModelRevisionId: 'm1', role: { kind: 'inline', body: '角色规则' }, temperature: 0.4, tasks: [task] });

for (const kind of ['sequence', 'condition', 'parallel']) test(`${kind} template forms a complete executable graph and preserves task configuration`, () => {
  const value = templates.workflowTemplate(task(), kind);
  const result = graph.compileWorkflowDraft(draft(value));
  assert.equal(result.tasks[0].nodes.length, value.nodes.length);
  assert.deepEqual(result.tasks[0].assetRevisionIds, ['skill-v1']);
  assert.equal(graph.workflowIssues(result, catalog).length, 0);
});
test('legacy node order converts to a graph without changing the original draft', () => {
  const value = task(); value.nodes = [{ id: 'b', name: 'second' }, { id: 'a', name: 'first' }];
  assert.equal(graph.orderedNodeIds(value).join(','), 'b,a');
  assert.equal(value.workflow, undefined);
});
test('connections, not canvas coordinates or node-array order, determine dependencies', () => {
  const value = templates.workflowTemplate(task(), 'sequence');
  value.nodes.reverse(); value.workflow.positions.reverse();
  assert.equal(graph.orderedNodeIds(value).join(','), 'understand,answer');
  const result = graph.compileWorkflowDraft(draft(value));
  assert.equal(result.tasks[0].nodes[0].id, 'understand');
  assert.equal(value.nodes[0].id, 'answer');
});
test('cycles, self connections and duplicate outlets are rejected during linking', () => {
  const value = templates.workflowTemplate(task(), 'sequence');
  assert.match(graph.connectionIssue(value, 'answer', 'answer'), /自身/);
  assert.match(graph.connectionIssue(value, 'understand', 'answer'), /已存在/);
  value.workflow.edges = [{ source: 'understand', target: 'answer' }];
  assert.match(graph.connectionIssue(value, 'answer', 'understand'), /循环/);
});
test('parallel has multiple outputs, condition needs distinct true and false ports, joins accept multiple parents', () => {
  const value = templates.workflowTemplate(task(), 'parallel');
  value.workflow.edges = value.workflow.edges.filter(edge => edge.target !== 'risk');
  assert.equal(graph.connectionIssue(value, 'fork', 'risk'), undefined);
  const condition = templates.workflowTemplate(task(), 'condition');
  condition.workflow.edges = condition.workflow.edges.filter(edge => edge.sourceHandle !== 'false');
  assert.equal(graph.connectionIssue(condition, 'route', 'answer', 'false'), undefined);
  assert.match(graph.connectionIssue(condition, 'route', 'answer', 'true'), /已有连线/);
  assert.throws(() => graph.orderedNodeIds(condition), /孤立|不满足/);
});
test('deleting a node removes attached edges and positions but never deletes start or output', () => {
  const value = templates.workflowTemplate(task(), 'sequence');
  const result = graph.removeWorkflowNodes(value, ['understand', graph.START, graph.END]);
  assert.equal(result.nodes.length, 1);
  assert.equal(result.workflow.edges.length, 1);
  assert.equal(result.workflow.positions.some(item => item.id === graph.START), true);
  assert.throws(() => graph.compileWorkflowDraft(draft(result)), /孤立/);
  assert.equal(value.nodes.length, 2);
});
test('generation checks unavailable models, invalid numeric conditions, and detached cyclic components', () => {
  const value = templates.workflowTemplate(task(), 'condition');
  value.nodes[0].condition = { source: 'input', operator: 'gt', value: 'x' };
  value.nodes[1].modelRevisionId = 'revoked';
  const errors = graph.workflowIssues(draft(value), catalog).join('\n');
  assert.match(errors, /有效数字/); assert.match(errors, /模型不可用/);
  value.workflow.edges = [{ source: graph.START, target: graph.END }, { source: 'report', target: 'answer' }, { source: 'answer', target: 'report' }];
  assert.throws(() => graph.orderedNodeIds(value), /循环/);
});

test('default model is mandatory even if every task overrides it', () => {
  const value = draft({ ...task(), modelRevisionId: 'm1' }); value.defaultModelRevisionId = '';
  assert.ok(graph.agentConfigurationIssues(value, catalog).includes('请选择默认模型'));
});
test('rules require nonblank inline text or an existing nonblank template revision', () => {
  const value = draft(task()); value.role = { kind: 'inline', body: ' \n ' };
  assert.ok(graph.agentConfigurationIssues(value, catalog).includes('请填写规则提示词'));
  const templates = { ...catalog, prompts: [{ id: 'p1', resourceId: 'prompt', content: { body: 'fixed rules' } }, { id: 'empty', content: { body: ' ' } }] };
  for (const id of ['', 'missing', 'prompt', 'empty']) {
    value.role = { kind: 'template', revisionId: id };
    assert.ok(graph.agentConfigurationIssues(value, templates).some(error => error.includes('规则提示词模板')));
  }
  value.role = { kind: 'template', revisionId: 'p1' };
  assert.equal(graph.agentConfigurationIssues(value, templates).length, 0);
});
test('different available models can override defaults without project grants', () => {
  const value = draft({ ...task(), modelRevisionId: 'm2', nodes: [{ id: 'node', name: 'node', modelRevisionId: 'm3' }] });
  const resources = { models: ['m1', 'm2', 'm3'].map(id => ({ id })), prompts: [] };
  assert.equal(graph.workflowIssues(value, resources).length, 0);
  assert.equal(Object.hasOwn(resources, 'grants'), false);
});
