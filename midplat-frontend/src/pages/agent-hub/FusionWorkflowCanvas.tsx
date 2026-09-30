import { useMemo, useState } from 'react';
import { Button, Tooltip } from 'antd';
import { Background, BackgroundVariant, Handle, MarkerType, MiniMap, Position, ReactFlow, ReactFlowProvider, useReactFlow, type Node, type NodeProps } from '@xyflow/react';
import { ArrowRight, GitBranch, GitFork, GitMerge, LayoutGrid, LogIn, Maximize, Sparkles } from 'lucide-react';
import type { FusionCatalog, FusionNodeKind, FusionTask, FusionWorkflow } from './fusionApi';
import { connectionIssue, END, orderedNodeIds, removeWorkflowNodes, START, taskGraph } from './workflowGraph';
import { nodeKindLabel } from './workflowTemplates';
import '@xyflow/react/dist/style.css';

export const NODE_DRAG_TYPE = 'application/agenthub-node';
/** 与 .workflow-node 的 CSS 尺寸保持一致：缩略图需要显式尺寸才能绘制节点 */
export const NODE_WIDTH = 220;
export const NODE_HEIGHT = 144;
export const NODE_ICONS = { llm: Sparkles, condition: GitBranch, parallel: GitFork, merge: GitMerge, start: LogIn, end: ArrowRight };
type CanvasData = { kind: FusionNodeKind | 'start' | 'end'; label: string; subtitle: string; configured: boolean };
function WorkflowNode({ data, selected }: NodeProps<Node<CanvasData>>) {
  const Icon = NODE_ICONS[data.kind];
  return <div className={`workflow-node ${selected ? 'is-selected' : ''}`} data-kind={data.kind}>
    {data.kind !== 'start' && <Handle type="target" position={Position.Left} aria-label={`${data.label} 输入连接点`} />}
    <div className="workflow-node-heading"><span className="workflow-node-icon"><Icon size={18} /></span><small>{data.kind === 'start' ? '开始' : data.kind === 'end' ? '输出' : nodeKindLabel[data.kind]}</small></div>
    <strong>{data.label}</strong><p className={data.configured ? '' : 'workflow-node-warning'}>{data.subtitle}</p>
    {data.kind === 'condition' ? <>
      <span className="workflow-port-label port-true">满足</span><Handle id="true" type="source" position={Position.Right} style={{ top: 48 }} aria-label={`${data.label} 满足出口`} />
      <span className="workflow-port-label port-false">不满足</span><Handle id="false" type="source" position={Position.Right} style={{ top: 106 }} aria-label={`${data.label} 不满足出口`} />
    </> : data.kind !== 'end' && <Handle type="source" position={Position.Right} aria-label={`${data.label} 输出连接点`} />}
  </div>;
}
const nodeTypes = { workflow: WorkflowNode };
export const edgeId = (edge: FusionWorkflow['edges'][number]) => `${edge.source}/${edge.sourceHandle || ''}/${edge.target}`;

type Props = {
  task: FusionTask; catalog: FusionCatalog; defaultModel: string; editable: boolean; selected: string;
  onSelect: (id: string) => void; onChange: (task: FusionTask) => void;
  onAdd: (kind: FusionNodeKind, position?: { x: number; y: number }) => void; onError: (message: string) => void;
};
function Canvas({ task, catalog, defaultModel, editable, selected, onSelect, onChange, onAdd, onError }: Props) {
  const flow = useReactFlow(); const [selectedEdge, setSelectedEdge] = useState('');
  const graph = taskGraph(task);
  const modelName = (id: string) => catalog.models.find(model => model.id === id)?.content.name || '请选择模型';
  const nodes = useMemo<Node<CanvasData>[]>(() => [START, ...task.nodes.map(node => node.id), END].map((id, index) => {
    const node = task.nodes.find(node => node.id === id);
    const kind = id === START ? 'start' : id === END ? 'end' : node?.kind || 'llm';
    const model = node?.modelRevisionId || task.modelRevisionId || defaultModel;
    const subtitle = kind === 'start' ? task.name : kind === 'end' ? '返回本次执行结果' : kind === 'condition' ? (node?.condition?.source === 'input' ? '判断用户输入' : '判断上游结果') : kind === 'parallel' ? '各分支同时执行' : kind === 'merge' ? '等待分支，汇合结果' : modelName(model);
    const position = graph.positions.find(item => item.id === id);
    return { id, type: 'workflow', // 显式尺寸：缩略图靠它才能画出节点方块（nodeHasDimensions 取 measured ?? width ?? initialWidth）
      width: NODE_WIDTH, height: NODE_HEIGHT,
      position: position ? { x: position.x, y: position.y } : { x: 60 + index * 300, y: 160 }, selected: id === selected,
      deletable: id !== START && id !== END && editable, ariaLabel: `${kind === 'start' ? '开始' : kind === 'end' ? '输出' : nodeKindLabel[kind]}：${node?.name || task.name}`,
      data: { kind, label: node?.name || (id === START ? '用户输入' : '最终输出'), subtitle, configured: kind !== 'llm' || catalog.models.some(item => item.id === model) } };
  }), [task, catalog, defaultModel, selected, editable]);
  const edges = graph.edges.map(edge => ({ ...edge, id: edgeId(edge), type: 'smoothstep', selected: edgeId(edge) === selectedEdge,
    markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 }, label: edge.sourceHandle === 'true' ? '满足' : edge.sourceHandle === 'false' ? '不满足' : undefined,
    ariaLabel: `从 ${nodes.find(node => node.id === edge.source)?.data.label} 到 ${nodes.find(node => node.id === edge.target)?.data.label}`,
  }));
  const layout = () => {
    let ids: string[]; try { ids = [START, ...orderedNodeIds(task), END]; } catch { ids = [START, ...task.nodes.map(node => node.id), END]; }
    const depth = new Map<string, number>(); const counts = new Map<number, number>();
    const positions = ids.map(id => {
      const parents = graph.edges.filter(edge => edge.target === id).map(edge => edge.source);
      const column = id === START ? 0 : Math.max(0, ...parents.map(parent => depth.get(parent) || 0)) + 1;
      depth.set(id, column); const row = counts.get(column) || 0; counts.set(column, row + 1);
      return { id, x: 60 + column * 300, y: 80 + row * 210 };
    });
    onChange({ ...task, workflow: { ...graph, positions } });
    requestAnimationFrame(() => void flow.fitView({ padding: 0.2, maxZoom: 1 }));
  };
  return <section className="workflow-canvas" aria-label="智能体拖拽编排画布"
    onDragOver={event => { if (editable && event.dataTransfer.types.includes(NODE_DRAG_TYPE)) { event.preventDefault(); event.dataTransfer.dropEffect = 'copy'; } }}
    onDrop={event => {
      const kind = event.dataTransfer.getData(NODE_DRAG_TYPE) as FusionNodeKind;
      if (!editable || !['llm', 'condition', 'parallel', 'merge'].includes(kind)) return;
      event.preventDefault(); onAdd(kind, flow.screenToFlowPosition({ x: event.clientX, y: event.clientY }));
    }}>
    <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView fitViewOptions={{ padding: 0.22, maxZoom: 0.9 }} minZoom={0.15} maxZoom={1.5}
      nodesDraggable={editable} nodesConnectable={editable} edgesReconnectable={false} deleteKeyCode={editable ? ['Backspace', 'Delete'] : null}
      onNodeClick={(_, node) => { onSelect(node.id); setSelectedEdge(''); }} onEdgeClick={(_, edge) => setSelectedEdge(edge.id)}
      onPaneClick={() => setSelectedEdge('')}
      onNodesChange={changes => {
        if (!editable) return;
        const moves = changes.filter(change => change.type === 'position' && change.position);
        if (!moves.length) return;
        const positions = nodes.map(node => { const move = moves.find(change => change.type === 'position' && change.id === node.id); return { id: node.id, ...(move?.type === 'position' && move.position ? move.position : node.position) }; });
        onChange({ ...task, workflow: { ...graph, positions } });
      }}
      onNodesDelete={removed => { if (editable) { onChange(removeWorkflowNodes(task, removed.map(node => node.id))); onSelect(START); } }}
      onEdgesDelete={removed => { if (editable) onChange({ ...task, workflow: { ...graph, edges: graph.edges.filter(edge => !removed.some(item => item.id === edgeId(edge))) } }); }}
      isValidConnection={connection => !connectionIssue(task, connection.source, connection.target, connection.sourceHandle)}
      onConnect={connection => {
        if (!editable) return; const issue = connectionIssue(task, connection.source, connection.target, connection.sourceHandle);
        if (issue) { onError(issue); return; }
        onChange({ ...task, workflow: { ...graph, edges: [...graph.edges, { source: connection.source, target: connection.target, ...(connection.sourceHandle ? { sourceHandle: connection.sourceHandle as 'true' | 'false' } : {}) }] } });
      }}
      ariaLabelConfig={{ 'minimap.ariaLabel': '流程缩略图' }}>
      <Background variant={BackgroundVariant.Dots} gap={20} size={1} />
      <MiniMap className="workflow-minimap" pannable zoomable nodeBorderRadius={3} nodeStrokeWidth={0}
        nodeColor={node => ({ condition: '#f59e0b', parallel: '#8b5cf6', merge: '#14b8a6' }[node.data.kind as string] || '#3b82f6')} />
    </ReactFlow>
    <div className="workflow-canvas-toolbar"><Tooltip title="按依赖自动排列节点"><Button aria-label="自动布局" icon={<LayoutGrid size={15} />} disabled={!editable} onClick={layout}>自动布局</Button></Tooltip><Button aria-label="适应全部节点" icon={<Maximize size={15} />} onClick={() => void flow.fitView({ padding: 0.2, maxZoom: 1 })} /></div>
    <div className="workflow-canvas-hint">拖动卡片调整位置 · 从连接点拖出连线 · 选中连线后按 Delete 删除 · 滚轮缩放，右下缩略图可拖动定位</div>
  </section>;
}
export function FusionWorkflowCanvas(props: Props) { return <ReactFlowProvider><Canvas {...props} /></ReactFlowProvider>; }
