import { Tag } from 'antd';
import type { CatalogRevision, FusionCompiledTask } from './fusionApi';
import { END, START } from './workflowGraph';
import { nodeKindHelp, nodeKindLabel } from './workflowTemplates';

export function FusionSnapshotNodes({ task, models }: { task: FusionCompiledTask; models: Record<string, CatalogRevision> }) {
  const name = (id: string) => id === START ? '开始' : id === END ? '输出' : task.nodes.find(node => node.id === id)?.name || id;
  const operators = { contains: '包含', equals: '等于', not_equals: '不等于', gt: '数值大于', lt: '数值小于', is_empty: '为空', not_empty: '不为空' };
  return <>{task.nodes.map(node => <article className="fusion-node-editor" key={node.id}>
    <div><Tag>{nodeKindLabel[node.kind || 'llm']}</Tag><strong>{node.name}</strong></div>
    {!node.kind || node.kind === 'llm' ? <><p>模型：{models[node.modelRevisionId || '']?.content.name || '修订不可用'}</p><pre className="hub-content-block">{node.systemInstructions?.join('\n\n') || '无附加指令'}</pre></> : <p className="hub-help">{nodeKindHelp[node.kind]}</p>}
    {node.condition && <p>{node.condition.source === 'input' ? '用户输入' : '上游输出'}{node.condition.field ? ` · 字段 ${node.condition.field}` : ''} {operators[node.condition.operator]} {node.condition.value || ''}</p>}
  </article>)}{task.workflow && <div><h3>流程关联</h3>{task.workflow.edges.map((edge, index) => <p key={index} className="hub-help">{name(edge.source)} {edge.sourceHandle === 'true' ? '— 满足 →' : edge.sourceHandle === 'false' ? '— 不满足 →' : '→'} {name(edge.target)}</p>)}</div>}</>;
}
