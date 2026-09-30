import type { FusionNode, FusionNodeKind, FusionTask, FusionWorkflow } from './fusionApi';
import { START, END } from './workflowGraph';

export const nodeKindLabel = { llm: '模型处理', condition: '条件分支', parallel: '并行分支', merge: '结果汇合' };
export const nodeKindHelp = {
  llm: '使用模型、指令和能力资产处理内容', condition: '根据输入或上游结果选择一条路径',
  parallel: '同时启动多个互不依赖的分支', merge: '等待分支结束，合并已执行的结果',
};
export function newWorkflowNode(kind: FusionNodeKind, id: string): FusionNode {
  return { id, kind, name: nodeKindLabel[kind], instructions: '', ...(kind === 'condition' ? { condition: { source: 'previous' as const, operator: 'contains' as const, value: '' } } : {}) };
}
export function workflowTemplate(task: FusionTask, template: 'sequence' | 'condition' | 'parallel'): FusionTask {
  const nodes: FusionNode[] = [];
  const positions: FusionWorkflow['positions'] = [{ id: START, x: 40, y: 210 }];
  const add = (id: string, kind: FusionNodeKind, name: string, x: number, y: number, instructions = '') => {
    nodes.push({ ...newWorkflowNode(kind, id), name, instructions }); positions.push({ id, x, y });
  };
  let edges: FusionWorkflow['edges'];
  if (template === 'sequence') {
    add('understand', 'llm', '理解问题', 330, 210, '理解用户输入，提炼核心问题与必要背景。');
    add('answer', 'llm', '生成回答', 620, 210, '根据上一步分析与用户输入，给出清晰、准确的回答。');
    edges = [{ source: START, target: 'understand' }, { source: 'understand', target: 'answer' }, { source: 'answer', target: END }];
    positions.push({ id: END, x: 910, y: 210 });
  } else if (template === 'condition') {
    add('route', 'condition', '是否需要报告', 330, 210);
    nodes[0].condition = { source: 'input', operator: 'contains', value: '报告' };
    add('report', 'llm', '报告生成', 640, 80, '根据用户输入生成结构清晰的报告，信息不足时说明缺失项。');
    add('answer', 'llm', '问答处理', 640, 370, '直接回答用户问题，给出必要的依据。');
    add('join', 'merge', '接收分支结果', 950, 210);
    edges = [{ source: START, target: 'route' }, { source: 'route', target: 'report', sourceHandle: 'true' }, { source: 'route', target: 'answer', sourceHandle: 'false' }, { source: 'report', target: 'join' }, { source: 'answer', target: 'join' }, { source: 'join', target: END }];
    positions.push({ id: END, x: 1240, y: 210 });
  } else {
    add('fork', 'parallel', '并行分析', 330, 210);
    add('summary', 'llm', '要点分析', 640, 80, '分析用户输入，提炼主要事实、目标与关键信息。');
    add('risk', 'llm', '风险分析', 640, 370, '分析用户输入中的风险与不确定性，说明判断依据。');
    add('join', 'merge', '汇合分析结果', 950, 210);
    add('answer', 'llm', '汇总结果', 1240, 210, '整合上游各分支结果，去重后形成完整回答。上游 JSON 的每个字段是一条分支的输出文本。');
    edges = [{ source: START, target: 'fork' }, { source: 'fork', target: 'summary' }, { source: 'fork', target: 'risk' }, { source: 'summary', target: 'join' }, { source: 'risk', target: 'join' }, { source: 'join', target: 'answer' }, { source: 'answer', target: END }];
    positions.push({ id: END, x: 1530, y: 210 });
  }
  return { ...task, nodes, workflow: { version: 1, positions, edges } };
}
