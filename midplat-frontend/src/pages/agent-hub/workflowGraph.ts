import type { FusionAsset, FusionCatalog, FusionDraft, FusionTask, FusionWorkflow } from './fusionApi';

export const START = '__start__';
export const END = '__end__';
const identifier = /^[a-zA-Z0-9_-]{1,64}$/;

export function taskGraph(task: FusionTask): FusionWorkflow {
  if (task.workflow) return structuredClone(task.workflow);
  const ids = [START, ...task.nodes.map(node => node.id), END];
  return {
    version: 1,
    positions: ids.map((id, index) => ({ id, x: 60 + index * 300, y: 160 })),
    edges: ids.slice(1).map((target, index) => ({ source: ids[index], target })),
  };
}

export function connectionIssue(task: FusionTask, source: string, target: string, sourceHandle?: string | null): string | undefined {
  const ids = new Set([START, ...task.nodes.map(node => node.id), END]);
  const graph = taskGraph(task);
  if (!ids.has(source) || !ids.has(target)) return '连线引用了不存在的节点';
  if (source === target) return '不能连接节点自身';
  if (source === END || target === START) return '连线应从开始节点流向输出节点';
  const kind = task.nodes.find(node => node.id === source)?.kind || 'llm';
  if (kind === 'condition' && !['true', 'false'].includes(sourceHandle || '')) return '请选择满足或不满足出口';
  if (kind !== 'condition' && sourceHandle) return '只有条件节点可以选择分支出口';
  if (graph.edges.some(edge => edge.source === source && edge.target === target && (edge.sourceHandle || '') === (sourceHandle || ''))) return '这条连线已存在';
  if (kind !== 'parallel' && graph.edges.some(edge => edge.source === source && (edge.sourceHandle || '') === (sourceHandle || ''))) return '该出口已有连线，请先删除原有连线；多个分支请使用并行节点';
  if (kind === 'parallel' && graph.edges.filter(edge => edge.source === source).length >= 8) return '单个并行节点最多关联 8 个分支';
  const pending = [target]; const visited = new Set<string>();
  while (pending.length) {
    const id = pending.pop()!;
    if (id === source) return '不能形成循环连线';
    if (visited.has(id)) continue;
    visited.add(id);
    pending.push(...graph.edges.filter(edge => edge.source === id).map(edge => edge.target));
  }
}

// Stable topological order is only for serialization. Edges remain authoritative at runtime.
export function orderedNodeIds(task: FusionTask): string[] {
  const ids = [START, ...task.nodes.map(node => node.id), END];
  if (new Set(ids).size !== ids.length) throw new Error('节点标识重复或使用了保留标识');
  const graph = taskGraph(task);
  const outgoing = new Map<string, string[]>(); const incoming = new Map<string, string[]>();
  const unique = new Set<string>(); const ports = new Set<string>();
  for (const edge of graph.edges) {
    if (!ids.includes(edge.source) || !ids.includes(edge.target)) throw new Error('连线引用了不存在的节点');
    if (edge.source === END || edge.target === START || edge.source === edge.target) throw new Error('连线方向错误或连接了节点自身');
    const key = `${edge.source}/${edge.sourceHandle || ''}/${edge.target}`;
    if (unique.has(key)) throw new Error('存在重复连线'); unique.add(key);
    const kind = task.nodes.find(node => node.id === edge.source)?.kind || 'llm';
    if (kind === 'condition') {
      if (!['true', 'false'].includes(edge.sourceHandle || '') || ports.has(`${edge.source}/${edge.sourceHandle}`)) throw new Error('条件节点的两个出口各只能连接一次');
      ports.add(`${edge.source}/${edge.sourceHandle}`);
    } else if (edge.sourceHandle) throw new Error('只有条件连线可以指定分支出口');
    const next = [...(outgoing.get(edge.source) || []), edge.target];
    if (!['parallel', 'condition'].includes(kind) && next.length > 1) throw new Error('请使用并行节点连接多个分支');
    if (kind === 'parallel' && next.length > 8) throw new Error('单个并行节点最多关联 8 个分支');
    outgoing.set(edge.source, next); incoming.set(edge.target, [...(incoming.get(edge.target) || []), edge.source]);
  }
  const degree = new Map(ids.map(id => [id, incoming.get(id)?.length || 0]));
  const ready = ids.filter(id => !degree.get(id)); const order: string[] = [];
  while (ready.length) {
    const id = ready.shift()!; order.push(id);
    for (const target of outgoing.get(id) || []) {
      degree.set(target, degree.get(target)! - 1);
      if (!degree.get(target)) ready.push(target);
    }
  }
  if (order.length !== ids.length) throw new Error('流程不能包含循环');
  const reachable = (root: string, links: Map<string, string[]>) => {
    const visited = new Set<string>(); const pending = [root];
    while (pending.length) { const id = pending.pop()!; if (!visited.has(id)) { visited.add(id); pending.push(...(links.get(id) || [])); } }
    return visited;
  };
  if (reachable(START, outgoing).size !== ids.length || reachable(END, incoming).size !== ids.length) throw new Error('所有节点必须连通开始与输出，请连接或删除孤立节点');
  for (const node of task.nodes) {
    const count = outgoing.get(node.id)?.length || 0;
    if (node.kind === 'condition' && count !== 2) throw new Error(`${node.name}：请同时连接满足与不满足分支`);
    if (node.kind === 'parallel' && count < 2) throw new Error(`${node.name}：并行节点至少需要两个分支`);
    if (node.kind === 'merge' && (incoming.get(node.id)?.length || 0) < 2) throw new Error(`${node.name}：汇合节点至少需要两个前置分支`);
  }
  return order.filter(id => id !== START && id !== END);
}

export function removeWorkflowNodes(task: FusionTask, ids: string[]): FusionTask {
  const removed = new Set(ids.filter(id => id !== START && id !== END));
  const graph = taskGraph(task);
  return { ...task, nodes: task.nodes.filter(node => !removed.has(node.id)), workflow: {
    ...graph, positions: graph.positions.filter(position => !removed.has(position.id)),
    edges: graph.edges.filter(edge => !removed.has(edge.source) && !removed.has(edge.target)),
  } };
}

/**
 * 智能体配置的必填校验：默认模型与规则提示词在智能体配置里直接选择，不再经过项目级授权。
 * 默认模型必须非空且存在于目录中——即使所有任务都覆盖了模型，也不会免除默认模型。
 */
export function agentConfigurationIssues(draft: FusionDraft, catalog: FusionCatalog): string[] {
  const issues: string[] = [];
  if (!draft.defaultModelRevisionId) issues.push('请选择默认模型');
  else if (!catalog.models.some(model => model.id === draft.defaultModelRevisionId)) issues.push('请选择有效的默认模型，该模型修订不可用或已停用');
  const role = draft.role;
  if (role.kind === 'inline') {
    if (!role.body.trim()) issues.push('请填写规则提示词');
  } else if (!role.revisionId) {
    issues.push('请选择有效的规则提示词模板');
  } else if (!catalog.prompts.some(prompt => prompt.id === role.revisionId && !!prompt.content.body?.trim())) {
    issues.push('请选择有效的规则提示词模板，该模板修订不可用或正文为空');
  }
  return issues;
}

export function workflowIssues(draft: FusionDraft, catalog: FusionCatalog, assets?: FusionAsset[]): string[] {
  const issues: string[] = [...agentConfigurationIssues(draft, catalog)];
  if (draft.tasks.length < 1 || draft.tasks.length > 20) issues.push('请配置 1 至 20 个任务');
  const keys = new Set<string>();
  for (const task of draft.tasks) {
    const prefix = task.name || '未命名任务';
    if (!identifier.test(task.key) || keys.has(task.key)) issues.push(`${prefix}：任务标识须唯一，使用字母、数字、下划线或短横线`);
    keys.add(task.key);
    if (!task.name.trim() || task.name.length > 128) issues.push(`${prefix}：请填写 128 字以内的任务名称`);
    const model = task.modelRevisionId || draft.defaultModelRevisionId;
    if (!catalog.models.some(item => item.id === model)) issues.push(`${prefix}：请选择有效的任务模型`);
    if (task.nodes.length > 30) issues.push(`${prefix}：每个任务最多 30 个模型节点`);
    for (const node of task.nodes) {
      if (!identifier.test(node.id) || !node.name.trim() || node.name.length > 128) issues.push(`${prefix}：节点标识或名称无效`);
      if ((!node.kind || node.kind === 'llm') && !catalog.models.some(item => item.id === (node.modelRevisionId || model))) issues.push(`${prefix} / ${node.name}：模型不可用，请重新选择`);
      if (node.kind === 'condition') {
        const condition = node.condition;
        if (!condition || !['input', 'previous'].includes(condition.source)) issues.push(`${node.name}：请选择条件判断来源`);
        else if (['gt', 'lt'].includes(condition.operator) && (!condition.value?.trim() || !Number.isFinite(Number(condition.value)))) issues.push(`${node.name}：比较值需要有效数字`);
        if (condition?.field && !/^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*$/.test(condition.field)) issues.push(`${node.name}：JSON 字段路径格式无效`);
      }
    }
    // Older immutable revisions remain valid; only known disabled revisions can be rejected here.
    if (assets && task.assetRevisionIds?.some(id => assets.some(asset => asset.revisionId === id && !asset.enabled))) issues.push(`${prefix}：引用的能力资产已停用，请重新选择`);
    try { orderedNodeIds(task); } catch (error) { issues.push(`${prefix}：${(error as Error).message}`); }
  }
  return issues;
}

export function compileWorkflowDraft(draft: FusionDraft): FusionDraft {
  return { ...structuredClone(draft), tasks: draft.tasks.map(task => ({
    ...structuredClone(task), workflow: taskGraph(task),
    nodes: orderedNodeIds(task).map(id => structuredClone(task.nodes.find(node => node.id === id)!)),
  })) };
}
