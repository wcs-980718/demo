import { FusionSnapshotNodes } from './FusionSnapshotNodes';
import { createUuid } from '../../createUuid';
import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Drawer, Empty, Input, Modal, Select, Spin, Tabs, Tag, message } from 'antd';
import { useNavigate } from '@umijs/max';
import { ArrowUpRight, GitBranch, RefreshCw, Settings2 } from 'lucide-react';
import { FusionDraftEditor } from './FusionDraftEditor';
import { fusionApi, sameConfiguration, type FusionRelease } from './fusionApi';
import { FusionExecutionPanel } from './FusionExecutionPanel';
import './workspace.css';

const auditLabels: Record<string, string> = { 'business.run.created': '业务任务执行', 'deployment.created': '创建项目部署', 'draft.saved': '保存草稿', 'publication.requested': '发起配置发布', 'configuration.published': '配置发布完成', 'publication.failed': '配置发布失败' };
const date = (value: string) => new Date(value).toLocaleString('zh-CN');
export function FusionProjectPanel({ platformId, environment = 'development', initialTab = 'config', compact = false }: { platformId: string; environment?: string; initialTab?: string; compact?: boolean }) {
  const navigate = useNavigate(); const cache = useQueryClient();
  const key = ['fusion-project', platformId, environment];
  const session = useQuery({ queryKey: ['fusion-session'], queryFn: fusionApi.session, retry: false });
  const binding = useQuery({ queryKey: [...key, 'binding'], queryFn: () => fusionApi.binding(platformId, environment), retry: false });
  const definitions = useQuery({ queryKey: [...key, 'definitions'], queryFn: () => fusionApi.definitions(platformId, environment), enabled: !binding.isPending && !binding.data, retry: false });
  const config = useQuery({ queryKey: [...key, 'config'], queryFn: () => fusionApi.config(platformId, environment), enabled: binding.data?.status === 'ready', refetchInterval: 5000, retry: false });
  const catalog = useQuery({ queryKey: [...key, 'catalog'], queryFn: () => fusionApi.catalog(platformId, environment), retry: false });
  const releases = useQuery({ queryKey: [...key, 'releases'], queryFn: () => fusionApi.releases(platformId, environment), enabled: binding.data?.status === 'ready', refetchInterval: 5000, retry: false });
  const audit = useQuery({ queryKey: [...key, 'audit'], queryFn: () => fusionApi.audit(platformId, environment), enabled: binding.data?.status === 'ready', retry: false });
  const [name, setName] = useState(''); const [definition, setDefinition] = useState(''); const [creating, setCreating] = useState(false);
  const [definitionOpen, setDefinitionOpen] = useState(false); const [definitionName, setDefinitionName] = useState(''); const [taskName, setTaskName] = useState(''); const [definitionSaving, setDefinitionSaving] = useState(false);
  const [editing, setEditing] = useState(false); const [editorTab, setEditorTab] = useState('role');
  const [publishing, setPublishing] = useState(false); const [note, setNote] = useState(''); const [publicationKey, setPublicationKey] = useState(''); const [sending, setSending] = useState(false);
  const [restoring, setRestoring] = useState<FusionRelease | null>(null); const [snapshot, setSnapshot] = useState<FusionRelease | null>(null);
  const [jobId, setJobId] = useState<string | null>(null); const [jobError, setJobError] = useState(''); const notified = useRef('');
  const pendingId = jobId || config.data?.pendingJobId;
  const job = useQuery({ queryKey: [...key, 'publication', pendingId], queryFn: () => fusionApi.publication(platformId, environment, pendingId!), enabled: !!pendingId, refetchInterval: query => ['queued', 'validating'].includes(query.state.data?.status || 'queued') ? 1000 : false, retry: false });
  const refresh = () => Promise.all([cache.invalidateQueries({ queryKey: key }), cache.invalidateQueries({ queryKey: ['fusion-preflight'] })]);
  const reconnect = async () => {
    try { cache.setQueryData(['fusion-session'], await fusionApi.reconnect()); await refresh(); }
    catch (error) { message.error((error as Error).message); }
  };
  useEffect(() => {
    const value = job.data;
    if (!value || !['ready', 'failed'].includes(value.status) || notified.current === value.id) return;
    notified.current = value.id; setJobId(null); void refresh();
    if (value.status === 'ready') { setJobError(''); message.success('配置版本已发布，可以上线并调试'); }
    else setJobError(value.error || '发布失败，已发布配置保持不变');
  }, [job.data]);
  const writable = !!session.data?.identity.writable;
  const admin = !!session.data?.identity.admin;
  const current = releases.data?.find(r => r.id === config.data?.publishedReleaseId);
  const changed = !current || !sameConfiguration(current.snapshot.draft, config.data?.draft);
  const createDefinition = async () => {
    setDefinitionSaving(true);
    try { const created = await fusionApi.createDefinition(platformId, environment, { name: definitionName, taskKey: 'main', taskName, idempotencyKey: createUuid() }); setDefinition(created.id); setDefinitionOpen(false); await definitions.refetch(); }
    catch (e) { message.error((e as Error).message); } finally { setDefinitionSaving(false); }
  };
  const create = async () => {
    setCreating(true);
    try { const b = binding.data; await fusionApi.bind(platformId, environment, { name: b?.name || name, definitionId: b?.definition_id || definition, idempotencyKey: b?.operation_id || createUuid() }); await refresh(); message.success('项目部署已创建'); }
    catch (e) { message.error((e as Error).message); void refresh(); } finally { setCreating(false); }
  };
  const openPublish = (release?: FusionRelease) => { setRestoring(release || null); setNote(release ? `恢复配置版本 R${release.sequence}` : ''); setPublicationKey(createUuid()); setPublishing(true); setJobError(''); };
  const publish = async () => {
    if (!config.data) return; setSending(true);
    try { const value = await fusionApi.publish(platformId, environment, { expectedRevision: config.data.revision, note, idempotencyKey: publicationKey, ...(restoring ? { restoreReleaseId: restoring.id } : {}) }); setJobId(value.id); setPublishing(false); void refresh(); }
    catch (e) { message.error((e as Error).message); } finally { setSending(false); }
  };
  if (binding.isPending || session.isPending) return <Spin tip="读取项目部署…"><div style={{ minHeight: 160 }} /></Spin>;
  const error = binding.error || session.error || config.error || catalog.error || releases.error;
  if (error) return <Alert type="error" showIcon title="无法读取真实项目配置" description={(error as Error).message} action={<Button onClick={() => void reconnect()}>重新连接</Button>} />;
  if (binding.data?.status === 'pending') return <Alert type="warning" showIcon title="项目绑定尚未完成" description="中台已保留绑定操作，可以继续完成同一次操作。" action={<Button loading={creating} disabled={!writable} onClick={create}>继续绑定</Button>} />;
  if (!binding.data) return <section className="fusion-project hub-form"><h2>为项目创建独立智能体部署</h2><p>模型从开发中心的模型目录选择，草稿与发布记录保存在智能体后端。每个项目独立管理。</p><label>部署名称<Input aria-label="部署名称" value={name} maxLength={128} onChange={e => setName(e.target.value)} placeholder="例如：根因报告分析" /></label><label>复用定义<Select aria-label="复用定义" placeholder="选择已有定义，或新建定义" value={definition || undefined} options={definitions.data?.map(d => ({ value: d.id, label: d.name, title: d.name }))} onChange={setDefinition} loading={definitions.isPending} /></label>{admin && <Button onClick={() => setDefinitionOpen(true)}>新建智能体定义</Button>}{definitions.error && <Alert type="error" title={(definitions.error as Error).message} />}<Button type="primary" loading={creating} disabled={!writable || !name.trim() || !definitions.data?.some(d => d.id === definition)} onClick={create}>创建项目部署</Button><Modal title="新建智能体定义" open={definitionOpen} onCancel={() => setDefinitionOpen(false)} onOk={createDefinition} confirmLoading={definitionSaving} okText="创建定义" okButtonProps={{ disabled: !definitionName.trim() || !taskName.trim() }}><div className="hub-form"><label>定义名称<Input aria-label="定义名称" maxLength={128} value={definitionName} onChange={e => setDefinitionName(e.target.value)} /></label><label>首个任务名称<Input aria-label="首个任务名称" maxLength={128} value={taskName} onChange={e => setTaskName(e.target.value)} /></label><p className="hub-help">创建后可在项目草稿中配置角色、任务指令及更多节点。</p></div></Modal></section>;
  if (!config.data || !catalog.data) return <Spin><div style={{ minHeight: 160 }} /></Spin>;
  const deployment = config.data;
  const roleBody = deployment.draft.role.kind === 'inline' ? deployment.draft.role.body : catalog.data.prompts.find(p => deployment.draft.role.kind === 'template' && p.id === deployment.draft.role.revisionId)?.content.body || '所引用的模板修订暂不可用';
  const modelLabel = (id: string) => catalog.data?.models.find(m => m.id === id)?.content.name || (id ? '修订不可用或模型已停用' : '尚未选择');
  return <section className="fusion-project hub-form">
    {!compact && <div className="fusion-project-heading"><div><span className="hub-overline">项目智能体配置</span><h2>{deployment.name}</h2><p>业务项目与智能体工作台读取同一份后端配置。</p></div><Button icon={<ArrowUpRight size={15} />} onClick={() => navigate(`/agent-hub/agents?project=${platformId}`)}>在智能体工作台查看</Button></div>}
    <Alert type="info" showIcon title="这里编辑智能体草稿绑定的模型与规则提示词。默认模型与规则提示词都是必填项，在任何项目里都直接选择，不再需要按项目授权。" />
    <div className="hub-actions"><Tag>草稿修订 {deployment.revision}</Tag><Tag color={current ? 'green' : 'default'}>{current ? `已发布配置 R${current.sequence}` : '配置尚未发布'}</Tag><Tag color={deployment.activeReleaseId ? 'green' : 'default'}>{deployment.activeReleaseId ? '已有运行版本' : '尚未上线'}</Tag><Button size="small" icon={<RefreshCw size={14} />} onClick={() => void refresh()}>刷新</Button></div>
    {pendingId && <Alert type={job.error ? 'warning' : 'info'} showIcon title={job.error ? '暂时无法确认发布结果，请重新连接后查询同一任务' : '正在校验配置并构建配置版本…'} description={job.error ? (job.error as Error).message : '发布任务已保存到后端，刷新页面不会丢失进度。'} action={job.error ? <Button onClick={() => void job.refetch()}>重查状态</Button> : undefined} />}
    {jobError && <Alert type="error" showIcon title="配置发布失败" description={jobError} />}
    <Tabs defaultActiveKey={initialTab} items={[
      { key: 'config', label: '模型与提示词', children: <div className="hub-form">
        <div className="fusion-compare"><article><h3>已发布配置</h3><Tag color="green">{current ? `R${current.sequence}` : '暂无配置版本'}</Tag><p>角色正文</p><pre className="hub-content-block">{current?.snapshot.effectiveRole || '完成草稿后发布，在这里查看固定的角色正文。'}</pre><Button disabled={!current} onClick={() => current && setSnapshot(current)}>查看完整配置快照</Button></article><article><h3>项目草稿</h3><Tag color={changed ? 'orange' : 'default'}>{changed ? '有待发布修改' : '与发布配置一致'}</Tag><p>默认模型：{modelLabel(deployment.draft.defaultModelRevisionId)}</p><pre className="hub-content-block">{roleBody || '尚未填写角色规则。'}</pre><p className="hub-help">来源：{deployment.draft.role.kind === 'template' ? '中台固定模板修订' : '项目专属正文'} · {deployment.draft.tasks.length} 个任务</p></article></div>
        <div className="hub-actions"><Button type="primary" disabled={!writable} icon={<Settings2 size={15} />} onClick={() => { setEditorTab('role'); setEditing(true); }}>编辑模型与提示词</Button><Button disabled={!writable} onClick={() => { setEditorTab('tasks'); setEditing(true); }}>编辑任务与节点</Button><Button disabled={!writable || !!pendingId} icon={<GitBranch size={15} />} onClick={() => openPublish()}>发布配置版本</Button></div>
      </div> },
      { key: 'releases', label: '配置版本', children: <div className="hub-form"><div className="hub-actions"><Button type="primary" disabled={!writable || !!pendingId} onClick={() => openPublish()}>发布配置版本</Button><p className="hub-help">历史版本保留模型和模板修订。恢复将创建新配置版本，保留当前草稿。</p></div>{releases.data?.length ? releases.data.map(release => <article key={release.id} className="fusion-release-row"><div><strong>R{release.sequence}</strong> {release.id === deployment.publishedReleaseId && <Tag color="green">当前配置</Tag>} {release.restoredFrom && <Tag>历史恢复</Tag>}<p>{release.note}</p><small>{date(release.createdAt)} · 摘要 {release.hash.slice(0, 12)}</small></div><div className="hub-actions"><Button onClick={() => setSnapshot(release)}>查看快照</Button><Button disabled={!writable || !!pendingId} onClick={() => openPublish(release)}>恢复此配置</Button></div></article>) : <Empty description="尚未创建配置版本" />}</div> },
      { key: 'runs', label: '上线与运行', children: <FusionExecutionPanel key={`${platformId}-${environment}`} project={platformId} environment={environment} deployment={deployment} releases={releases.data || []} editable={writable} onChanged={() => void refresh()} /> },
      { key: 'audit', label: '审计记录', children: <div className="hub-form">{audit.error && <Alert type="error" title={(audit.error as Error).message} />}{audit.data?.map((item, index) => <div className="fusion-release-row" key={`${item.created_at}-${index}`}><div><strong>{auditLabels[item.action] || item.action}</strong><p>{item.principal}</p></div><small>{date(item.created_at)}</small></div>)}{!audit.isPending && !audit.data?.length && <Empty description="暂无审计记录" />}</div> },
    ]} />
    {editing && <FusionDraftEditor project={platformId} environment={environment} deployment={deployment} catalog={catalog.data} initialTab={editorTab} onClose={() => setEditing(false)} onSaved={() => void refresh()} />}
    <Modal title={restoring ? `恢复 R${restoring.sequence} 的配置` : '发布配置版本'} open={publishing} okText="提交配置发布" confirmLoading={sending} onOk={publish} onCancel={() => setPublishing(false)} okButtonProps={{ disabled: !note.trim() || !!pendingId || !writable }}><div className="hub-form"><Alert type="info" title="这会生成不可变配置版本。随后可在“上线与运行”中选择版本上线。" /><label>发布说明<Input.TextArea aria-label="发布说明" maxLength={500} rows={3} value={note} onChange={e => setNote(e.target.value)} /></label></div></Modal>
    <Drawer open={!!snapshot} title={snapshot ? `配置快照 R${snapshot.sequence}` : '配置快照'} width={760} onClose={() => setSnapshot(null)}>{snapshot && <div className="hub-form"><Tag color="green">不可变配置</Tag><p>{snapshot.note}</p><p className="hub-help">内容摘要：{snapshot.hash}</p><h3>角色规则</h3><pre className="hub-content-block">{snapshot.snapshot.effectiveRole}</pre>{snapshot.snapshot.tasks.map(task => <section className="fusion-task-editor" key={task.key}><h3>{task.name}</h3><p>模型：{snapshot.snapshot.models[task.modelRevisionId]?.content.name} · {snapshot.snapshot.models[task.modelRevisionId]?.hash.slice(0, 8)}</p><pre className="hub-content-block">{task.systemInstructions.join('\n\n')}</pre><FusionSnapshotNodes task={task} models={snapshot.snapshot.models} /></section>)}</div>}</Drawer>
  </section>;
}
