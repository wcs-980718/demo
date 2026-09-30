import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, ConfigProvider, Input, InputNumber, Modal, Select, Tabs, Tag, Tooltip, message } from 'antd';
import { ArrowRight, BookOpen, CheckCircle2, Download, Link2, Plus, Save, Settings2, Sparkles, Trash2, X } from 'lucide-react';
import { createUuid } from '../../createUuid';
import { assetKindLabel, fusionApi, revisionLabel, sameConfiguration, type FusionCatalog, type FusionDeployment, type FusionDraft, type FusionNode, type FusionNodeKind, type FusionTask } from './fusionApi';
import { edgeId, FusionWorkflowCanvas, NODE_DRAG_TYPE, NODE_ICONS } from './FusionWorkflowCanvas';
import { compileWorkflowDraft, agentConfigurationIssues, connectionIssue, END, removeWorkflowNodes, START, taskGraph, workflowIssues } from './workflowGraph';
import { newWorkflowNode, nodeKindHelp, nodeKindLabel, workflowTemplate } from './workflowTemplates';
import { FusionWorkflowGuide } from './FusionWorkflowGuide';
import './workflow.css';

export function FusionWorkflowEditor({ config, catalog, project, environment, editable, createMode, persist, onSaved, onPublish, onDirtyChange, onClose }: {
  config: FusionDeployment; catalog: FusionCatalog; project: string; environment: string; editable: boolean; createMode?: boolean;
  persist?: (expectedRevision: number, draft: FusionDraft, generate: boolean) => Promise<{ revision: number; draft: FusionDraft }>;
  onSaved: () => void; onPublish: () => void; onDirtyChange?: (dirty: boolean) => void; onClose?: () => void;
}) {
  const [draft, setDraft] = useState(() => structuredClone(config.draft));
  const [baseline, setBaseline] = useState(() => structuredClone(config.draft));
  const [revision, setRevision] = useState(config.revision);
  const [taskIndex, setTaskIndex] = useState(0); const [selected, setSelected] = useState(START);
  // 新建智能体缺必填项时先落到「智能体配置」页签，先补齐默认模型与规则提示词。
  const [panel, setPanel] = useState(() => agentConfigurationIssues(config.draft, catalog).length ? 'agent' : 'node');
  const [saving, setSaving] = useState(false); const [error, setError] = useState(''); const [issues, setIssues] = useState<string[]>([]);
  const [generating, setGenerating] = useState(false); const [generated, setGenerated] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [linkTarget, setLinkTarget] = useState(''); const [port, setPort] = useState<'true' | 'false'>('true');
  // 独立智能体可以不属于任何项目：此时没有项目资产目录，查询保持禁用并按空目录处理，
  // 不能让禁用查询的 isPending 挡住生成。
  const hasProject = !!project;
  const assets = useQuery({ queryKey: ['fusion-assets', project, environment], queryFn: () => fusionApi.assets(project, environment), enabled: hasProject, retry: false });
  const dirty = !sameConfiguration(draft, baseline); const canEdit = editable && !saving;
  useEffect(() => {
    if (config.revision > revision && !dirty) {
      setDraft(structuredClone(config.draft)); setBaseline(structuredClone(config.draft)); setRevision(config.revision); setTaskIndex(0); setSelected(START);
    }
  }, [config.revision, revision, dirty]);
  useEffect(() => { onDirtyChange?.(dirty); }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);
  useEffect(() => {
    if (!dirty) return;
    const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', prevent); return () => window.removeEventListener('beforeunload', prevent);
  }, [dirty]);
  const task = draft.tasks[taskIndex]; const node = task?.nodes.find(item => item.id === selected);
  const role = draft.role;
  const templateReady = role.kind === 'template' && !!role.revisionId && catalog.prompts.some(prompt => prompt.id === role.revisionId && !!prompt.content.body?.trim());
  const graph = task ? taskGraph(task) : undefined;
  const models = catalog.models.map(model => { const label = revisionLabel(model); return { value: model.id, label, title: label }; });
  const update = (next: FusionDraft) => { if (canEdit) { setDraft(next); setIssues([]); setGenerated(false); setError(''); } };
  const updateTask = (next: FusionTask) => update({ ...draft, tasks: draft.tasks.map((item, index) => index === taskIndex ? next : item) });
  const patchTask = (patch: Partial<FusionTask>) => updateTask({ ...task, ...patch });
  const patchNode = (patch: Partial<FusionNode>) => patchTask({ nodes: task.nodes.map(item => item.id === selected ? { ...item, ...patch } : item) });
  const selectNode = (id: string) => { setSelected(id); setPanel('node'); setLinkTarget(''); setPort('true'); };
  const add = (kind: FusionNodeKind, position?: { x: number; y: number }) => {
    if (!canEdit || !task) return;
    if (task.nodes.length >= 30) { message.warning('每个任务最多配置 30 个节点'); return; }
    const id = createUuid(); const next = newWorkflowNode(kind, id);
    updateTask({ ...task, nodes: [...task.nodes, next], workflow: { ...taskGraph(task), positions: [...taskGraph(task).positions, { id, ...(position || { x: 360, y: 380 + (task.nodes.length % 3) * 180 }) }] } });
    selectNode(id);
  };
  const applyTemplate = (kind: 'sequence' | 'condition' | 'parallel') => {
    const apply = () => { updateTask(workflowTemplate(task, kind)); selectNode(START); };
    if (task.nodes.length || task.workflow) Modal.confirm({ title: '替换当前任务的编排？', content: '将替换当前任务的节点和连线，保留模型、角色和能力资产配置。保存后才会更新草稿。', okText: '使用模板', cancelText: '取消', onOk: apply });
    else apply();
  };
  const save = async (generate = false) => {
    if (!canEdit) return;
    // 保存（含仅保存草稿）之前先校验智能体配置的必填项：默认模型与规则提示词。
    const missing = agentConfigurationIssues(draft, catalog);
    if (missing.length) {
      setIssues(missing); setError(''); setPanel('agent');
      message.warning('请先完善智能体配置的必填项');
      return;
    }
    setSaving(true); setError('');
    try {
      const value = generate ? compileWorkflowDraft(draft) : draft;
      const saved = persist ? await persist(revision, value, generate) : await (generate ? fusionApi.generate : fusionApi.save)(project, environment, revision, value);
      setDraft(structuredClone(saved.draft)); setBaseline(structuredClone(saved.draft)); setRevision(saved.revision);
      setGenerating(false); setGenerated(generate); setIssues([]); onSaved();
      message.success(generate ? '智能体配置已生成并保存，可以前往发布' : '编排草稿已保存');
    } catch (failure) { setGenerating(false); setError((failure as Error).message); }
    finally { setSaving(false); }
  };
  const prepare = () => {
    const findings = workflowIssues(draft, catalog, assets.data);
    if (hasProject && (assets.isPending || assets.isError)) findings.push('能力资产目录尚未读取成功，请重试后生成');
    setIssues(findings);
    if (findings.length) { if (agentConfigurationIssues(draft, catalog).length) setPanel('agent'); return; }
    setGenerating(true);
  };
  const exportDraft = () => {
    const url = URL.createObjectURL(new Blob([JSON.stringify({ name: config.name, project, environment, revision, draft }, null, 2)], { type: 'application/json' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `agent-workflow-${project}.json`; anchor.click(); URL.revokeObjectURL(url);
  };
  const allNodes = draft.tasks.flatMap(item => item.nodes);
  const choices = task ? [{ value: START, label: '开始 · 用户输入' }, ...task.nodes.map(item => { const label = `${nodeKindLabel[item.kind || 'llm']} · ${item.name}`; return { value: item.id, label, title: label }; }), { value: END, label: '输出 · 最终输出' }] : [];
  const titleOf = (id: string) => choices.find(item => item.value === id)?.label || id;
  const connect = () => {
    if (!task || !linkTarget || !graph) return;
    const handle = node?.kind === 'condition' ? port : undefined;
    const problem = connectionIssue(task, selected, linkTarget, handle);
    if (problem) { message.warning(problem); return; }
    patchTask({ workflow: { ...graph, edges: [...graph.edges, { source: selected, target: linkTarget, ...(handle ? { sourceHandle: handle } : {}) }] } }); setLinkTarget('');
  };
  return <div className="workflow-editor">
    <header className="workflow-editor-header"><div><span className="workflow-editor-symbol"><Sparkles size={20} /></span><div><div className="workflow-editor-heading"><h2>{createMode ? '新建智能体编排' : '可视化编排'}</h2>{createMode ? <Tag color="blue">新建智能体</Tag> : null}<Button className="workflow-guide-trigger" icon={<BookOpen size={14} />} aria-haspopup="dialog" onClick={() => setGuideOpen(true)}>图解教程</Button></div><p>配置能力，连接步骤，生成你的智能体</p></div></div><div className="hub-actions"><Tag color={dirty ? 'orange' : 'default'}>{dirty ? '有未保存修改' : `已保存 · 修订 ${revision}`}</Tag><div className="hub-icon-group"><Tooltip title="下载当前配置，也可用于保留未保存修改"><Button aria-label="下载当前编排" icon={<Download size={15} />} onClick={exportDraft} /></Tooltip>{onClose && <Tooltip title="关闭编排，返回上一页"><Button aria-label="关闭编排编辑器" icon={<X size={15} />} onClick={onClose} /></Tooltip>}</div></div></header>
    {config.revision > revision && dirty && <Alert type="warning" showIcon title="草稿已被其他操作更新" description="当前编辑已保留。可先下载当前编排，再重新打开最新草稿；保存时会检查版本冲突。" />}
    {error && <Alert type="error" showIcon title="操作未完成" description={`${error}；当前编辑仍保留。`} />}
    {!!issues.length && <Alert type="warning" showIcon title={`还有 ${issues.length} 项需要完善`} description={<ul className="workflow-issues">{issues.map((issue, index) => <li key={index}>{issue}</li>)}</ul>} />}
    {generated && <Alert type="success" showIcon title="智能体配置已生成" description="前往版本与发布创建版本，然后上线并调试。" action={<Button onClick={onPublish}>前往发布 <ArrowRight size={14} /></Button>} />}
    <div className="workflow-taskbar"><div className="hub-actions"><Select aria-label="编排任务" value={taskIndex} options={draft.tasks.map((item, index) => ({ value: index, label: item.name, title: item.name }))} onChange={index => { setTaskIndex(index); selectNode(START); }} />
      <div className="hub-icon-group"><Tooltip title="新增任务"><Button aria-label="新增编排任务" icon={<Plus size={15} />} disabled={!canEdit || draft.tasks.length >= 20} onClick={() => { const index = draft.tasks.length; update({ ...draft, tasks: [...draft.tasks, { key: `task_${createUuid().slice(0, 8)}`, name: `任务 ${index + 1}`, instructions: '', nodes: [] }] }); setTaskIndex(index); selectNode(START); }} /></Tooltip>
      <Tooltip title="删除当前任务"><Button aria-label="删除当前编排任务" icon={<Trash2 size={15} />} disabled={!canEdit || draft.tasks.length <= 1} onClick={() => Modal.confirm({ title: `删除“${task.name}”？`, content: '该任务的节点和连线会一起移除，保存后生效。', okText: '删除任务', cancelText: '取消', okButtonProps: { danger: true }, onOk: () => { update({ ...draft, tasks: draft.tasks.filter((_, index) => index !== taskIndex) }); setTaskIndex(0); selectNode(START); } })} /></Tooltip></div></div>
      <div className="hub-actions"><Button icon={<Settings2 size={15} />} onClick={() => setPanel('agent')}>智能体配置</Button><Button icon={<Save size={15} />} disabled={!canEdit || !dirty} loading={saving && !generating} onClick={() => void save()}>保存草稿</Button><Button type="primary" icon={<Sparkles size={15} />} disabled={!canEdit} onClick={prepare}>生成智能体</Button></div></div>
    {task && <div className="workflow-editor-body">
      <aside className="workflow-palette"><div className="workflow-pane-title"><h3>添加节点</h3><p>拖入画布或点击添加</p></div>
        {(Object.keys(nodeKindLabel) as FusionNodeKind[]).map(kind => { const Icon = NODE_ICONS[kind]; return <button key={kind} className="workflow-palette-node" data-kind={kind} disabled={!canEdit || task.nodes.length >= 30} draggable={canEdit && task.nodes.length < 30} aria-label={`添加${nodeKindLabel[kind]}节点`} onClick={() => add(kind)} onDragStart={event => { event.dataTransfer.setData(NODE_DRAG_TYPE, kind); event.dataTransfer.effectAllowed = 'copy'; }}><span className="workflow-node-icon"><Icon size={18} /></span><span><strong>{nodeKindLabel[kind]}</strong><small>{nodeKindHelp[kind]}</small></span><Plus size={13} /></button>; })}
        <div className="workflow-template-list"><h3>从模板开始</h3><p>保留任务配置，快速搭建流程</p>{([{ key: 'sequence', title: '顺序处理', sub: '理解问题 → 生成回答' }, { key: 'condition', title: '条件路由', sub: '按输入选择处理路径' }, { key: 'parallel', title: '并行分析', sub: '要点与风险分析后汇总' }] as const).map(template => <button key={template.key} disabled={!canEdit} onClick={() => applyTemplate(template.key)}><strong>{template.title}<ArrowRight size={13} /></strong><small>{template.sub}</small></button>)}</div>
        <p className="workflow-palette-note"><Link2 size={15} />连线决定执行关系。拖动位置不会改变流程。</p>
      </aside>
      <FusionWorkflowCanvas key={`${taskIndex}-${task.key}`} task={task} catalog={catalog} defaultModel={draft.defaultModelRevisionId} editable={canEdit} selected={selected} onSelect={selectNode} onChange={updateTask} onAdd={add} onError={value => message.warning(value)} />
      <aside className="workflow-inspector"><Tabs className="workflow-panel-tabs" activeKey={panel} onChange={setPanel} items={[
        { key: 'node', label: '节点配置', children: <ConfigProvider componentDisabled={!canEdit}><div className="hub-form">
          <label>当前节点<Select aria-label="选择编排节点" disabled={false} value={selected} options={choices} onChange={selectNode} /></label>
          {selected === START ? <>
            <fieldset className="workflow-field"><legend>任务设置</legend>
              <label>任务名称<Input aria-label="编排任务名称" maxLength={128} value={task.name} onChange={event => patchTask({ name: event.target.value })} /></label>
              <label>任务标识<Input aria-label="编排任务标识" maxLength={64} value={task.key} onChange={event => patchTask({ key: event.target.value })} /></label>
              <p className="hub-help">业务调用使用此标识，已有任务请谨慎修改。</p>
            </fieldset>
            <fieldset className="workflow-field"><legend>模型与指令</legend>
              <label>任务模型<Select aria-label="编排任务模型" allowClear value={task.modelRevisionId || undefined} options={models} placeholder="继承智能体默认模型" onChange={value => patchTask({ modelRevisionId: value || '' })} /></label>
              <label>任务指令<Input.TextArea aria-label="编排任务指令" rows={4} maxLength={20000} value={task.instructions} onChange={event => patchTask({ instructions: event.target.value })} placeholder="每个模型节点共同遵循的任务要求" /></label>
            </fieldset>
            <fieldset className="workflow-field"><legend>能力资产</legend>
              <label>关联能力资产<Select aria-label="编排任务能力资产" mode="multiple" disabled={!hasProject} loading={hasProject && assets.isPending} value={task.assetRevisionIds || []} maxCount={20} options={[
                ...(hasProject ? assets.data || [] : []).filter(asset => asset.enabled).map(asset => { const label = `${assetKindLabel[asset.kind]} · ${asset.name}`; return { value: asset.revisionId, label, title: label }; }),
                ...(task.assetRevisionIds || []).filter(id => !assets.data?.some(asset => asset.revisionId === id && asset.enabled)).map(id => ({ value: id, label: `已引用修订 · ${id.slice(0, 8)}` })),
              ]} onChange={assetRevisionIds => patchTask({ assetRevisionIds })} placeholder="选择技能、工具、知识或数据" /></label>
              {hasProject && assets.error && <Alert type="error" title="无法读取能力资产" action={<Button size="small" disabled={false} onClick={() => void assets.refetch()}>重试</Button>} />}
              {!hasProject && <p className="hub-help">独立智能体未绑定项目，暂无可引用的项目能力资产；绑定项目后即可引用。</p>}
              <p className="hub-help">供本任务的模型节点使用；未添加模型节点时，按任务配置调用一次模型。</p>
            </fieldset>
          </> : selected === END ? <><fieldset className="workflow-field"><legend>输出设置</legend><label>模型默认输出格式<Select aria-label="编排输出格式" value={task.responseFormat || 'text'} options={[{ value: 'text', label: '文本' }, { value: 'json_object', label: 'JSON 对象' }]} onChange={responseFormat => patchTask({ responseFormat })} /></label><p className="hub-help">只有已执行分支的结果会进入输出。多个分支直接连到这里时，结果按节点标识合并为 JSON 对象。</p><p className="hub-help">每个模型节点可覆盖此设置。生成 JSON 需要模型支持，发布时会再次校验。</p></fieldset></> : node ? <>
            <div className="workflow-inspector-heading"><Tag>{nodeKindLabel[node.kind || 'llm']}</Tag><Button danger size="small" icon={<Trash2 size={13} />} aria-label="删除选中节点" onClick={() => { updateTask(removeWorkflowNodes(task, [selected])); selectNode(START); }}>删除</Button></div>
            <fieldset className="workflow-field"><legend>节点设置</legend>
              <label>节点名称<Input aria-label="编排节点名称" maxLength={128} value={node.name} onChange={event => patchNode({ name: event.target.value })} /></label>
              {(!node.kind || node.kind === 'llm') && <>
                <label>使用模型<Select aria-label="编排节点模型" allowClear showSearch optionFilterProp="label" value={node.modelRevisionId || undefined} options={models} placeholder="继承任务或智能体模型" onChange={value => patchNode({ modelRevisionId: value || '' })} /></label>
                <label>节点指令<Input.TextArea aria-label="编排节点指令" rows={7} maxLength={10000} value={node.instructions} onChange={event => patchNode({ instructions: event.target.value })} placeholder="描述该步骤需要完成的工作" /></label>
                <label>输出格式<Select aria-label="编排节点输出格式" allowClear value={node.responseFormat} placeholder="继承任务输出格式" options={[{ value: 'text', label: '文本' }, { value: 'json_object', label: 'JSON 对象' }]} onChange={responseFormat => patchNode({ responseFormat })} /></label>
                <p className="hub-help">节点会收到用户输入和已执行前置节点的输出，并继承角色规则、任务指令与能力资产。</p>
              </>}
              {node.kind === 'parallel' && <p className="hub-help">从右侧连接点连出至少两个分支。同一次任务最多同时执行 4 个模型节点，其余就绪节点排队执行。</p>}
              {node.kind === 'merge' && <p className="hub-help">连接至少两个前置分支。等待它们执行完成或被条件跳过后，将已执行的结果合并；单个结果直接透传，多个结果按节点标识组成 JSON 对象。</p>}
            </fieldset>
            {node.kind === 'condition' && node.condition && <fieldset className="workflow-field"><legend>条件规则</legend>
              <label>判断内容<Select aria-label="条件判断来源" value={node.condition.source} options={[{ value: 'previous', label: '上游节点输出' }, { value: 'input', label: '用户原始输入' }]} onChange={source => patchNode({ condition: { ...node.condition!, source } })} /></label>
              <label>JSON 字段路径（可选）<Input aria-label="条件字段路径" maxLength={200} value={node.condition.field || ''} placeholder="如 risk.score；留空判断完整文本" onChange={event => patchNode({ condition: { ...node.condition!, field: event.target.value } })} /></label>
              <label>判断规则<Select aria-label="条件判断规则" value={node.condition.operator} options={[{ value: 'contains', label: '包含文本' }, { value: 'equals', label: '等于' }, { value: 'not_equals', label: '不等于' }, { value: 'gt', label: '数值大于' }, { value: 'lt', label: '数值小于' }, { value: 'is_empty', label: '为空' }, { value: 'not_empty', label: '不为空' }]} onChange={operator => patchNode({ condition: { ...node.condition!, operator } })} /></label>
              {!['is_empty', 'not_empty'].includes(node.condition.operator) && <label>比较值<Input aria-label="条件比较值" maxLength={2000} value={node.condition.value || ''} onChange={event => patchNode({ condition: { ...node.condition!, value: event.target.value } })} /></label>}
              <p className="hub-help">两个出口都需要连线。每次只执行满足或不满足中的一条分支，另一条标记为跳过。</p>
            </fieldset>}
          </> : null}
          {selected !== END && <fieldset className="workflow-field"><legend>关联后续节点</legend><div className="workflow-link-editor">{node?.kind === 'condition' && <Select aria-label="连线条件出口" value={port} onChange={setPort} options={[{ value: 'true', label: '满足条件' }, { value: 'false', label: '不满足条件' }]} />}
            <Select aria-label="连接到节点" value={linkTarget || undefined} options={choices.filter(item => item.value !== START && item.value !== selected)} onChange={setLinkTarget} placeholder="选择后续节点" /><Button icon={<Link2 size={14} />} disabled={!canEdit || !linkTarget} onClick={connect}>添加连线</Button>
          </div>
            <div className="workflow-link-list">{graph?.edges.filter(edge => edge.source === selected || edge.target === selected).map(edge => <div key={edgeId(edge)}><span>{edge.source === selected ? `→ ${titleOf(edge.target)}` : `← ${titleOf(edge.source)}`}{edge.sourceHandle && <small>{edge.sourceHandle === 'true' ? '满足' : '不满足'}</small>}</span><Button type="text" danger aria-label={`删除连线 ${titleOf(edge.source)} 到 ${titleOf(edge.target)}`} icon={<X size={14} />} onClick={() => patchTask({ workflow: { ...graph, edges: graph.edges.filter(item => edgeId(item) !== edgeId(edge)) } })} /></div>)}</div>
          </fieldset>}
        </div></ConfigProvider> },
        { key: 'agent', label: '智能体配置', children: <ConfigProvider componentDisabled={!canEdit}><div className="hub-form"><div><h3>{config.name}</h3><p className="hub-help">以下设置由该智能体的所有任务继承。</p></div>
          <fieldset className="workflow-field"><legend>模型与生成</legend>
            <label>默认模型（必选）<Select aria-label="编排默认模型" aria-required showSearch optionFilterProp="label" status={draft.defaultModelRevisionId ? undefined : 'error'} value={draft.defaultModelRevisionId || undefined} options={models} placeholder="必填 · 选择模型" onChange={defaultModelRevisionId => update({ ...draft, defaultModelRevisionId })} /></label>
            {!models.length && <Alert type="info" title="暂无可用模型" description="请先前往开发中心的模型管理登记并启用模型，再回到智能体配置选择。" />}
            <label>生成温度<InputNumber aria-label="编排生成温度" min={0} max={2} step={0.1} value={draft.temperature} onChange={temperature => update({ ...draft, temperature: temperature ?? 0.4 })} /></label>
          </fieldset>
          <fieldset className="workflow-field"><legend>角色设定</legend>
            <label>角色来源<Select aria-label="编排角色来源" value={draft.role.kind} options={[{ value: 'inline', label: '自定义角色' }, { value: 'template', label: '关联提示词模板' }]} onChange={kind => update({ ...draft, role: kind === 'inline' ? { kind, body: catalog.prompts.find(prompt => draft.role.kind === 'template' && prompt.id === draft.role.revisionId)?.content.body || '' } : { kind, revisionId: '' } })} /></label>
            {draft.role.kind === 'inline'
              ? <label>规则提示词（必填）<Input.TextArea aria-label="编排角色规则" aria-required rows={12} maxLength={30000} status={draft.role.body.trim() ? undefined : 'error'} value={draft.role.body} placeholder="必填 · 定义身份、工作范围和输出要求" onChange={event => update({ ...draft, role: { kind: 'inline', body: event.target.value } })} /></label>
              : <><label>提示词模板（必选）<Select aria-label="编排提示词模板" aria-required showSearch optionFilterProp="label" status={templateReady ? undefined : 'error'} value={role.kind === 'template' ? role.revisionId || undefined : undefined} options={catalog.prompts.map(prompt => { const label = revisionLabel(prompt); return { value: prompt.id, label, title: label }; })} placeholder="必填 · 选择规则提示词模板" onChange={revisionId => update({ ...draft, role: { kind: 'template', revisionId } })} /></label><pre className="hub-content-block">{catalog.prompts.find(prompt => draft.role.kind === 'template' && prompt.id === draft.role.revisionId)?.content.body || '选择模板后查看角色规则'}</pre></>}
          </fieldset>
        </div></ConfigProvider> },
      ]} /></aside>
    </div>}
    <footer className="workflow-editor-footer"><span><i />{draft.tasks.length} 个任务 · {allNodes.length} 个节点，支持条件与并行</span><span>保存草稿 › 生成配置 › 发布并运行</span></footer>
    <Modal open={generating} title="生成智能体配置" okText="生成并保存" cancelText="继续编辑" onCancel={() => { if (!saving) setGenerating(false); }} onOk={() => void save(true)} confirmLoading={saving} maskClosable={!saving} cancelButtonProps={{ disabled: saving }}>
      <div className="hub-form"><div className="workflow-generation-title"><CheckCircle2 size={28} /><div><strong>编排检查通过</strong><p>{config.name}</p></div></div><p>将生成并保存全部任务的节点、连线、条件规则及能力关联，供后续发布运行。</p><div className="workflow-generation-stats"><span><strong>{allNodes.filter(item => !item.kind || item.kind === 'llm').length}</strong>模型节点</span><span><strong>{allNodes.filter(item => item.kind === 'condition').length}</strong>条件节点</span><span><strong>{allNodes.filter(item => item.kind === 'parallel').length}</strong>并行节点</span></div>{draft.tasks.map(item => <div key={item.key}><strong>{item.name}</strong><div className="hub-tags">{item.nodes.map(node => <Tag key={node.id}>{node.name}</Tag>)}</div></div>)}<p className="hub-help">发布时还会核对模型与能力资产是否可用。</p></div>
    </Modal>
    <FusionWorkflowGuide open={guideOpen} onClose={() => setGuideOpen(false)} />
  </div>;
}
