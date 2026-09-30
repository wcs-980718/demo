import { createUuid } from '../../createUuid';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { assetKindLabel } from './fusionApi';
import { agentConfigurationIssues } from './workflowGraph';
import { Alert, Button, Drawer, Input, InputNumber, Select, Tabs, message } from 'antd';
import { Plus, Trash2 } from 'lucide-react';
import { fusionApi, revisionLabel, type FusionCatalog, type FusionDeployment, type FusionDraft } from './fusionApi';

export function FusionDraftEditor({ project, environment, deployment, catalog, onClose, onSaved, initialTab = 'role' }: { project: string; environment: string; deployment: FusionDeployment; catalog: FusionCatalog; onClose: () => void; onSaved: () => void; initialTab?: string }) {
  const [draft, setDraft] = useState<FusionDraft>(() => structuredClone(deployment.draft));
  const [baseRevision] = useState(deployment.revision);
  const [saving, setSaving] = useState(false); const [error, setError] = useState(''); const [issues, setIssues] = useState<string[]>([]);
  const [tab, setTab] = useState(initialTab);
  const assets = useQuery({ queryKey: ['fusion-assets', project, environment], queryFn: () => fusionApi.assets(project, environment), retry: false });
  const models = catalog.models.map(r => { const label = revisionLabel(r); return { value: r.id, label, title: label }; });
  const update = (patch: Partial<FusionDraft>) => setDraft(value => ({ ...value, ...patch }));
  const role = draft.role;
  const templateReady = role.kind === 'template' && !!role.revisionId && catalog.prompts.some(r => r.id === role.revisionId && !!r.content.body?.trim());
  const save = async () => {
    // 项目草稿与独立智能体执行同一套必填校验：默认模型与规则提示词必填。
    const missing = agentConfigurationIssues(draft, catalog);
    if (missing.length) { setIssues(missing); setError(''); setTab('role'); return; }
    setSaving(true); setError(''); setIssues([]);
    try { await fusionApi.save(project, environment, baseRevision, draft); message.success('草稿已保存，已发布配置保持不变'); onSaved(); onClose(); }
    catch (e) { setError((e as Error).message); } finally { setSaving(false); }
  };
  const setTask = (index: number, patch: Partial<FusionDraft['tasks'][number]>) => update({ tasks: draft.tasks.map((task, i) => i === index ? { ...task, ...patch } : task) });
  return <Drawer open title={`编辑项目草稿 · 修订 ${baseRevision}`} width={820} onClose={onClose} extra={<Button type="primary" loading={saving} onClick={save}>保存草稿</Button>}>
    <div className="hub-form"><Alert type="info" showIcon title="保存只更新当前项目的草稿。配置发布后才形成新的不可变版本。" />{issues.length ? <Alert type="warning" showIcon title={`还有 ${issues.length} 项必填未完成`} description={<ul className="workflow-issues">{issues.map((issue, index) => <li key={index}>{issue}</li>)}</ul>} /> : null}{error && <Alert type="error" showIcon title={error} description="你的编辑内容仍保留在此窗口。若发生版本冲突，请复制需保留的内容，再关闭窗口刷新最新草稿。" />}
    <Tabs activeKey={tab} onChange={setTab} items={[
      { key: 'role', label: '模型与角色', children: <div className="hub-form">
        <label>默认模型修订<Select aria-label="默认模型修订" aria-required status={draft.defaultModelRevisionId ? undefined : 'error'} value={draft.defaultModelRevisionId || undefined} options={models} showSearch optionFilterProp="label" onChange={value => update({ defaultModelRevisionId: value })} placeholder="必填 · 从模型目录选择" /></label>
        {!models.length && <Alert type="info" title="暂无可用模型" description="请先前往开发中心的模型管理登记并启用模型。" />}
        <label>生成温度<InputNumber aria-label="生成温度" min={0} max={2} step={0.1} value={draft.temperature} onChange={value => update({ temperature: value ?? 0.4 })} /></label>
        <label>角色来源<Select aria-label="角色来源" value={draft.role.kind} options={[{ value: 'inline', label: '项目专属正文' }, { value: 'template', label: '引用固定模板修订' }]} onChange={kind => { const body = draft.role.kind === 'inline' ? draft.role.body : catalog.prompts.find(p => draft.role.kind === 'template' && p.id === draft.role.revisionId)?.content.body || ''; update({ role: kind === 'inline' ? { kind, body } : { kind, revisionId: '' } }); }} /></label>
        {draft.role.kind === 'inline' ? <label>规则提示词（必填）<Input.TextArea aria-label="角色规则" aria-required rows={10} maxLength={30000} showCount status={draft.role.body.trim() ? undefined : 'error'} value={draft.role.body} onChange={e => update({ role: { kind: 'inline', body: e.target.value } })} placeholder="必填 · 填写该项目智能体的角色、任务边界和输出要求" /></label> : <><label>提示词模板修订（必选）<Select aria-label="提示词模板修订" aria-required status={templateReady ? undefined : 'error'} value={draft.role.revisionId || undefined} options={catalog.prompts.map(r => { const label = revisionLabel(r); return { value: r.id, label, title: label }; })} showSearch optionFilterProp="label" onChange={revisionId => update({ role: { kind: 'template', revisionId } })} placeholder="必填 · 选择固定修订" /></label><pre className="hub-content-block">{catalog.prompts.find(r => draft.role.kind === 'template' && r.id === draft.role.revisionId)?.content.body || '选择模板后查看正文。'}</pre><p className="hub-help">模板后续修改会生成新修订，不会自动改变此处选择或已发布配置。</p></>}
      </div> },
      { key: 'tasks', label: `任务与节点 · ${draft.tasks.length}`, children: <div className="hub-form">
        <p className="hub-help">任务继承默认模型，节点继承所属任务模型；显式选择可覆盖继承值。上线后按节点顺序执行，每个节点使用自己的模型与指令。</p>
        {draft.tasks.map((task, index) => <section className="fusion-task-editor" key={index}>
          <div className="hub-actions"><h3>任务 {index + 1}</h3><Button danger size="small" disabled={draft.tasks.length <= 1} icon={<Trash2 size={14} />} onClick={() => update({ tasks: draft.tasks.filter((_, i) => i !== index) })}>移除任务</Button></div>
          <div className="fusion-compare"><label>任务标识<Input aria-label={`任务 ${index + 1} 标识`} value={task.key} maxLength={64} onChange={e => setTask(index, { key: e.target.value })} /></label><label>任务名称<Input aria-label={`任务 ${index + 1} 名称`} value={task.name} maxLength={128} onChange={e => setTask(index, { name: e.target.value })} /></label></div>
          <label>任务模型<Select aria-label={`任务 ${index + 1} 模型`} allowClear value={task.modelRevisionId || undefined} options={models} placeholder="继承默认模型" onChange={value => setTask(index, { modelRevisionId: value || '' })} /></label>
          {assets.error && <Alert type="error" title={assets.error.message} />}
          <label>引用能力资产<Select aria-label={`任务 ${index + 1} 能力资产`} mode="multiple" value={task.assetRevisionIds || []} onChange={assetRevisionIds => setTask(index, { assetRevisionIds })} options={assets.data?.filter(asset => asset.enabled).map(asset => { const label = `${assetKindLabel[asset.kind]} · ${asset.name} · 修订 ${asset.revision}`; return { value: asset.revisionId, label, title: label }; })} placeholder="选择项目技能、工具、知识或数据修订" /></label>
          <label>输出格式<Select aria-label={`任务 ${index + 1} 输出格式`} value={task.responseFormat || 'text'} onChange={responseFormat => setTask(index, { responseFormat })} options={[{ value: 'text', label: '文本' }, { value: 'json_object', label: 'JSON 对象（需模型支持）' }]} /></label>
          <label>任务指令<Input.TextArea aria-label={`任务 ${index + 1} 指令`} rows={4} maxLength={20000} value={task.instructions} onChange={e => setTask(index, { instructions: e.target.value })} /></label>
          {task.workflow && <Alert type="info" title="该任务已使用可视化编排" description="节点、条件和连线请在智能体开发的工作流画布中编辑。此处可继续修改任务模型、指令和能力资产。" />}
          {!task.workflow && task.nodes.map((node, ni) => <div className="fusion-node-editor" key={ni}>
            <div className="hub-actions"><strong>节点 {ni + 1}</strong><Button size="small" danger onClick={() => setTask(index, { nodes: task.nodes.filter((_, i) => i !== ni) })}>移除节点</Button></div>
            <label>节点名称<Input aria-label={`任务 ${index + 1} 节点 ${ni + 1} 名称`} value={node.name} onChange={e => setTask(index, { nodes: task.nodes.map((n, i) => i === ni ? { ...n, name: e.target.value } : n) })} /></label>
            <label>节点模型<Select aria-label={`任务 ${index + 1} 节点 ${ni + 1} 模型`} allowClear value={node.modelRevisionId || undefined} options={models} placeholder="继承任务模型" onChange={value => setTask(index, { nodes: task.nodes.map((n, i) => i === ni ? { ...n, modelRevisionId: value || '' } : n) })} /></label>
            <label>节点指令<Input.TextArea aria-label={`任务 ${index + 1} 节点 ${ni + 1} 指令`} rows={3} value={node.instructions} maxLength={10000} onChange={e => setTask(index, { nodes: task.nodes.map((n, i) => i === ni ? { ...n, instructions: e.target.value } : n) })} /></label>
          </div>)}
          <Button icon={<Plus size={14} />} disabled={!!task.workflow || task.nodes.length >= 30} onClick={() => setTask(index, { nodes: [...task.nodes, { id: createUuid(), name: `步骤 ${task.nodes.length + 1}`, instructions: '' }] })}>添加 LLM 节点配置</Button>
        </section>)}
        <Button disabled={draft.tasks.length >= 20} icon={<Plus size={14} />} onClick={() => update({ tasks: [...draft.tasks, { key: `task_${draft.tasks.length + 1}`, name: '新任务', instructions: '', nodes: [] }] })}>添加任务</Button>
      </div> },
    ]} />
    </div>
  </Drawer>;
}
