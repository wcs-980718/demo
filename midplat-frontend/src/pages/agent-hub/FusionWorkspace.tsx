import { createUuid } from '../../createUuid';
import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Drawer, Input, Modal, Select, Spin, message } from 'antd';
import { useLocation, useNavigate } from '@umijs/max';
import { ArrowLeft, ChevronRight, GitBranch, Plus, Plug } from 'lucide-react';
import { midplatApi, type MenuItem } from '@/api/midplatApi';
import { FusionDraftEditor } from './FusionDraftEditor';
import { FusionProjectPanel } from './FusionProjectPanel';
import { FusionCreateAgent } from './FusionCreateAgent';
import { fusionApi, sameConfiguration, type FusionAgentDraft, type FusionAgentSummary, type FusionCatalog, type FusionDeployment, type FusionRelease } from './fusionApi';
import { workflowIssues } from './workflowGraph';
import { projectKey, useFusionWorkspaceData, type WorkspaceView } from './useFusionWorkspaceData';
import { latestResources, WorkspaceAgents, WorkspaceAssets, WorkspaceAudit, WorkspaceEmpty, WorkspaceOverview, WorkspacePanel, WorkspaceReleases, WorkspaceSettings, WorkspaceSnapshot, WorkspaceWorkflow } from './FusionWorkspaceViews';
import { FusionExecutionPanel } from './FusionExecutionPanel';
import { FusionUsagePanel } from './FusionUsagePanel';
import './workspace.css';

const META: Record<WorkspaceView, [string, string]> = {
  overview: ['工作台总览', '让每个智能体从想法走向可管理的应用。'],
  agents: ['智能体', '选择智能体卡片查看生命周期，进入编排工作台拖拽编辑，或在详情面板直接发布版本。'],
  releases: ['版本与发布', '每次发布保留完整内容快照，历史配置随时可追溯。'],
  workflows: ['工作流', '将任务拆成清晰步骤，为每一步配置需要的能力。'],
  assets: ['能力资产', '复用中台模型、提示词与项目自定义技能 / 工具 / 知识 / 数据。'],
  use: ['API 接入', '通过标准接口，将已上线的智能体接入业务系统。'],
  runs: ['运行记录', '查看每次任务的输入、结果和执行版本。'],
  settings: ['治理设置', '访问与发布策略、项目绑定概况。模型与提示词在智能体配置里直接选择。'],
  audit: ['审计日志', '追溯智能体创建、草稿修改与版本发布。'],
};
type Editor = { deployment: FusionDeployment; catalog: FusionCatalog; project: string; environment: string; tab: string };
type Publishing = { project: string; environment: string; revision: number; key: string; restore?: FusionRelease };

export default function FusionWorkspace({ environment }: { environment: string }) {
  const location = useLocation(); const navigate = useNavigate(); const cache = useQueryClient();
  const params = new URLSearchParams(location.search);
  const route = location.pathname.split('/').at(-1) || 'overview';
  const view = (route in META ? route : 'overview') as WorkspaceView;
  const agentParam = params.get('agent');
  const data = useFusionWorkspaceData(environment, params.get('project'), agentParam, view);
  const { selected, selectedProject, catalog, agents, cards, selectedCard, selectedAgent } = data;
  const writable = !!data.session.data?.identity.writable;
  const admin = !!data.session.data?.identity.admin;
  const [workflowDirty, setWorkflowDirty] = useState(false);
  const [creating, setCreating] = useState(false); const [editor, setEditor] = useState<Editor | null>(null);
  const [resuming, setResuming] = useState(false); const [snapshot, setSnapshot] = useState<FusionRelease | null>(null);
  const [publishing, setPublishing] = useState<Publishing | null>(null); const [note, setNote] = useState(''); const [sending, setSending] = useState(false); const [publicationError, setPublicationError] = useState('');
  const [agentPublishing, setAgentPublishing] = useState<{ agent: FusionAgentSummary; key: string } | null>(null);
  const [submittedJob, setSubmittedJob] = useState<{ project: string; environment: string; id: string } | null>(null);
  const notified = useRef('');
  const jobContext = submittedJob || (selected?.config?.pendingJobId ? { project: selected.project.id, environment, id: selected.config.pendingJobId } : null);
  const job = useQuery({ queryKey: [...projectKey(jobContext?.project || '', jobContext?.environment || environment), 'publication', jobContext?.id], queryFn: () => fusionApi.publication(jobContext!.project, jobContext!.environment, jobContext!.id), enabled: !!jobContext, retry: false, refetchInterval: query => ['queued', 'validating'].includes(query.state.data?.status || 'queued') ? 1000 : false });
  const menus = useQuery({ queryKey: ['menus'], queryFn: midplatApi.listMenus, enabled: admin, retry: false });
  const agentDraft = useQuery({ queryKey: ['fusion-agent-draft', agentParam], queryFn: () => fusionApi.agentDraft(agentParam!), enabled: view === 'workflows' && !!agentParam, retry: false });
  const agentAssets = useQuery({ queryKey: ['fusion-assets', 'agent', agentParam], queryFn: () => fusionApi.assets(selectedProject!.id, environment), enabled: !!agentParam && !!selectedProject, retry: false });
  const refreshProject = (project = selectedProject?.id, env = environment) => {
    if (project) void cache.invalidateQueries({ queryKey: projectKey(project, env) });
    void cache.invalidateQueries({ queryKey: ['fusion-bindings'] });
    void cache.invalidateQueries({ queryKey: ['fusion-agent-list'] });
    void cache.invalidateQueries({ queryKey: ['fusion-agent-draft'] });
    void cache.invalidateQueries({ queryKey: ['fusion-preflight'] });
    void cache.invalidateQueries({ queryKey: ['fusion-agent-catalog'] });
  };
  useEffect(() => {
    if (!params.has('environment')) return;
    const next = new URLSearchParams(params);
    next.delete('environment');
    const search = next.toString();
    navigate(`${location.pathname}${search ? `?${search}` : ''}${location.hash}`, { replace: true });
  }, [location.hash, location.pathname, location.search, navigate]);
  useEffect(() => {
    if (!job.data || !jobContext || !['ready', 'failed'].includes(job.data.status) || notified.current === job.data.id) return;
    notified.current = job.data.id;
    refreshProject(jobContext.project, jobContext.environment); setSubmittedJob(null);
    if (job.data.status === 'ready') { setPublicationError(''); message.success('配置版本已发布'); }
    else setPublicationError(job.data.error || '发布失败，已发布配置保持不变');
  }, [job.data]);
  const from = (location.state as { from?: { view: WorkspaceView; project?: string; agent?: string } } | null)?.from;
  const navigateTo = (next: WorkspaceView, sel?: { project?: string; agent?: string }, back?: { view: WorkspaceView; project?: string; agent?: string }) => {
    const search = new URLSearchParams({ ...(sel?.project ? { project: sel.project } : {}), ...(sel?.agent ? { agent: sel.agent } : {}) }).toString();
    setPublicationError(''); setWorkflowDirty(false);
    navigate(`/agent-hub/${next}${search ? `?${search}` : ''}`, back ? { state: { from: back } } : undefined);
  };
  const confirmLeave = (proceed: () => void) => {
    if (!workflowDirty) { proceed(); return; }
    Modal.confirm({ title: '编排尚未保存', content: '离开将丢失本次未保存的修改。可以先留在画布保存草稿。', okText: '离开页面', cancelText: '继续编辑', onOk: proceed });
  };
  const currentSel = () => ({ project: selectedProject?.id, agent: agentParam || undefined });
  const go = (next: WorkspaceView, project = selectedProject?.id) => {
    const changed = view !== next || project !== selectedProject?.id;
    const back = view !== next ? { view, ...currentSel() } : from;
    if (!changed) { navigateTo(next, { project }, back); return; }
    confirmLeave(() => navigateTo(next, { project }, back));
  };
  const goAgent = (next: WorkspaceView, agent: string) => {
    const back = view !== next ? { view, ...currentSel() } : from;
    confirmLeave(() => navigateTo(next, { agent }, back));
  };
  const backTarget = from || (view === 'workflows' ? { view: 'agents' as WorkspaceView } : null);
  const goBack = () => { if (backTarget) confirmLeave(() => navigateTo(backTarget.view, { project: backTarget.project, agent: backTarget.agent })); };
  const openEditor = (tab = 'role') => {
    if (selected?.config && catalog.data) setEditor({ deployment: selected.config, catalog: catalog.data, project: selected.project.id, environment, tab });
  };
  const openPublish = (restore?: FusionRelease) => {
    if (!selected?.config) return;
    setNote(restore ? `恢复配置版本 R${restore.sequence}` : ''); setPublicationError('');
    setPublishing({ project: selected.project.id, environment, revision: selected.config.revision, key: createUuid(), restore });
  };
  const publish = async () => {
    if (!publishing) return; setSending(true); setPublicationError('');
    try {
      const value = await fusionApi.publish(publishing.project, publishing.environment, { expectedRevision: publishing.revision, note, idempotencyKey: publishing.key, ...(publishing.restore ? { restoreReleaseId: publishing.restore.id } : {}) });
      setSubmittedJob({ project: publishing.project, environment: publishing.environment, id: value.id });
      refreshProject(publishing.project, publishing.environment); setPublishing(null);
    } catch (error) { setPublicationError((error as Error).message); }
    finally { setSending(false); }
  };
  const openPublishAgent = (agent: FusionAgentSummary) => { setNote(''); setPublicationError(''); setAgentPublishing({ agent, key: createUuid() }); };
  const publishAgent = async (confirmed = false) => {
    if (!agentPublishing) return; setSending(true); setPublicationError('');
    try {
      const [fresh, versions] = await Promise.all([fusionApi.agentDraft(agentPublishing.agent.id), fusionApi.agentVersions(agentPublishing.agent.id)]);
      // 独立智能体也可以不经编排器直接发布：这里读取最新草稿后同样校验必填项与资源可用性，错误留在发布弹窗内。
      const findings = workflowIssues(fresh.draft, catalog.data || { models: [], availableModels: [], prompts: [] }, agentAssets.data);
      if (findings.length) { setPublicationError(`请先在智能体配置中完善：${findings.join('；')}`); return; }
      const latest = versions.length ? versions.reduce((a, b) => (b.sequence > a.sequence ? b : a)) : null;
      if (latest && !confirmed && sameConfiguration(fresh.draft, latest.content)) {
        Modal.confirm({ title: '内容与最新版本一致', content: `当前草稿与最新版本 V${latest.sequence} 的内容相同，发布会再生成一个内容一致的新版本。`, okText: '仍然发布', cancelText: '返回修改', onOk: () => publishAgent(true) });
        return;
      }
      await fusionApi.publishAgentVersion(agentPublishing.agent.id, { expectedRevision: fresh.expectedRevision, note, idempotencyKey: agentPublishing.key });
      message.success(`智能体版本 V${(latest?.sequence || 0) + 1} 已发布`); setAgentPublishing(null);
      void cache.invalidateQueries({ queryKey: ['fusion-agent-list'] });
      void cache.invalidateQueries({ queryKey: ['fusion-agent-versions', agentPublishing.agent.id] });
      void cache.invalidateQueries({ queryKey: ['fusion-agent-draft', agentPublishing.agent.id] });
    } catch (error) { setPublicationError((error as Error).message); }
    finally { setSending(false); }
  };
  const agentEditorConfig: FusionDeployment | null = agentParam && agentDraft.data ? {
    id: agentParam, projectId: '', environment, definitionId: agentParam,
    name: agentDraft.data.name, revision: agentDraft.data.expectedRevision, draft: agentDraft.data.draft,
    publishedReleaseId: null, activeReleaseId: null, pendingJobId: null, activationRevision: 0,
  } : null;
  const llmCatalog = (revisions: FusionCatalog['models']) => revisions.filter(item => (item.content.kind || 'llm') === 'llm');
  const agentEditorCatalog: FusionCatalog | undefined = catalog.data ? { ...catalog.data, models: llmCatalog(catalog.data.availableModels).length ? llmCatalog(catalog.data.availableModels) : catalog.data.models } : undefined;
  const persistAgentDraft = async (expectedRevision: number, draft: FusionAgentDraft['draft']) => {
    const saved = await fusionApi.saveAgentDraft(agentParam!, { expectedRevision, draft });
    return { revision: saved.expectedRevision, draft: saved.draft };
  };
  const projectMenu = (items: MenuItem[], project?: string): MenuItem | undefined => {
    if (!project) return undefined;
    for (const item of items) {
      if (item.platformId === project || item.platformIds?.includes(project)) return item;
      const child = projectMenu(item.children || [], project); if (child) return child;
    }
  };
  const businessMenu = projectMenu(menus.data || [], selectedProject?.id);
  const availableProjects = (data.projects.data || []).filter(project => !agents.some(agent => agent.project.id === project.id));
  const deploymentView = ['releases', 'workflows', 'runs', 'use'].includes(view);
  const scopedView = ['assets', 'settings'].includes(view);
  // 工作流页就是编排器本身：进入画布后不再渲染页面级面包屑、标题横幅与信息 Alert，画布按视口铺满
  const workflowFocus = view === 'workflows' && (!!agentParam || !!(selected?.config && catalog.data));
  const options = deploymentView ? agents.filter(agent => agent.config).map(agent => { const label = agent.config?.name || agent.project.name; return { value: agent.project.id, label, title: label }; }) : (data.projects.data || []).map(project => ({ value: project.id, label: project.name, title: project.name }));
  const activePending = !!selected?.config?.pendingJobId || !!(submittedJob && submittedJob.project === selectedProject?.id && submittedJob.environment === environment);
  const assetCount = catalog.data ? latestResources(catalog.data.availableModels).length + latestResources(catalog.data.prompts).length : undefined;
  const publishedRelease = selected?.releases.find(release => release.id === selected.config?.publishedReleaseId);
  const draftDirty = !!(selected?.config && publishedRelease && !sameConfiguration(selected.config.draft, publishedRelease.snapshot.draft));

  return <div className="agent-hub-shell">
    <main className="hub-main">
      {!workflowFocus && <div className="hub-breadcrumb">{backTarget && <Button className="hub-back" type="text" size="small" icon={<ArrowLeft size={13} />} onClick={goBack}>返回{META[backTarget.view][0]}</Button>}智能体开发<ChevronRight size={12} /><span>{META[view][0]}</span></div>}
      {!workflowFocus && <header className="hub-page-header"><div><h1>{META[view][0]}</h1><p>{META[view][1]}</p></div><div className="hub-actions">
        {(deploymentView || scopedView) && !(view === 'workflows' && agentParam) && <Select aria-label={deploymentView ? '当前智能体' : '当前项目'} className="hub-agent-select" value={options.some(option => option.value === selectedProject?.id) ? selectedProject?.id : undefined} placeholder={deploymentView ? '暂无智能体' : '选择项目'} options={options} onChange={project => go(view, project)} disabled={!options.length} />}
        {['releases', 'workflows'].includes(view) && !(view === 'workflows' && agentParam) && <Button icon={<Plug size={15} />} disabled={!selected?.config} onClick={() => go('use')}>API 接入</Button>}
        {view === 'releases' ? <Button type="primary" disabled={!writable || !selected?.config || activePending} icon={<GitBranch size={16} />} onClick={() => openPublish()}>发布新版本</Button> : view === 'workflows' ? (agentParam ? <Button type="primary" disabled={!writable || !agentDraft.data} icon={<GitBranch size={16} />} onClick={() => agentDraft.data && openPublishAgent(agentDraft.data)}>发布版本</Button> : <Button type="primary" disabled={!writable || !selected?.config} onClick={() => go('releases')}>版本与发布</Button>) : ['overview', 'agents'].includes(view) ? <Button type="primary" disabled={!writable} icon={<Plus size={16} />} onClick={() => setCreating(true)}>新建智能体</Button> : null}
      </div></header>}
      {data.error ? <Alert type="error" showIcon title="无法读取智能体工作台" description={data.error.message} action={<Button onClick={async () => {
        try {
          cache.setQueryData(['fusion-session'], await fusionApi.reconnect());
          await Promise.all([data.projects.refetch(), data.bindings.refetch(), cache.invalidateQueries({ queryKey: ['fusion-project'] })]);
        } catch (error) { message.error((error as Error).message); }
      }}>重新连接</Button>} /> : data.loading ? <Spin tip="读取真实工作台数据…"><div style={{ minHeight: 240 }} /></Spin> : <>
        {!writable && <Alert type="info" showIcon title="当前为只读权限，可以查看配置与版本。" />}
        {data.detailError && <Alert type="error" showIcon title="部分工作台数据读取失败" description={data.detailError.message} action={<Button onClick={() => { void cache.invalidateQueries({ queryKey: ['fusion-project'] }); }}>重新读取</Button>} />}
        {selected && ['releases', 'workflows'].includes(view) && !workflowFocus && <Alert type="info" showIcon title={`${selected.project.name} · 独立项目部署 · 与项目页共用草稿`} action={businessMenu ? <Button size="small" onClick={() => navigate(`/entry/category/${encodeURIComponent(businessMenu.id)}?platformId=${selected.project.id}`)}>查看业务项目</Button> : undefined} />}
        {jobContext && <Alert type={job.error ? 'warning' : 'info'} showIcon title={job.error ? '暂时无法确认发布结果' : '正在校验配置并构建配置版本…'} description={job.error ? job.error.message : '任务已保存，刷新页面后仍可继续查询发布状态。'} action={job.error ? <Button onClick={() => void job.refetch()}>重查状态</Button> : undefined} />}
        {publicationError && !publishing && <Alert type="error" showIcon title="配置发布失败" description={publicationError} />}
        <div className="hub-view">
          {view === 'overview' && <WorkspaceOverview cards={cards} agents={agents} assetCount={assetCount} detailsLoading={data.detailsLoading || !!data.detailError} auditLoading={data.auditLoading} onCreate={() => setCreating(true)} go={go} goAgent={id => goAgent('agents', id)} editable={writable} />}
          {view === 'agents' && <WorkspaceAgents cards={cards} selectedCard={selectedCard} selected={selected} catalog={catalog.data} projects={data.projects.data || []} bindableProjects={availableProjects} environment={environment} onSelect={project => go('agents', project)} onSelectAgent={id => goAgent('agents', id)} onOpenBinding={(project, next) => go(next, project)} onConfig={() => openEditor()} onCreate={() => setCreating(true)} onReleases={() => go('releases')} onRun={() => go('runs')} onUse={() => go('use')} editable={writable} onResume={() => setResuming(true)} onWorkflow={() => go('workflows')} onWorkflowAgent={id => goAgent('workflows', id)} onSettings={() => go('settings')} onAudit={() => go('audit')} onPublish={() => openPublish()} onPublishAgent={openPublishAgent} publishing={activePending} auditLoading={data.auditLoading} onChanged={() => refreshProject()} />}
          {view === 'releases' && <WorkspaceReleases agent={selected} onSnapshot={setSnapshot} onRestore={release => openPublish(release)} onConfig={() => openEditor()} onCreate={() => setCreating(true)} editable={writable} onRun={() => go('runs')} onPublish={() => openPublish()} />}
          {view === 'workflows' && (agentParam ? (
            agentDraft.error ? <Alert type="error" showIcon title="无法读取智能体草稿" description={agentDraft.error.message} /> :
            agentEditorConfig && agentEditorCatalog ? <WorkspaceWorkflow key={`agent-${agentParam}-${environment}`} config={agentEditorConfig} catalog={agentEditorCatalog} project={selectedProject?.id || ''} environment={environment} editable={writable} createMode={params.get('mode') === 'create'} persist={persistAgentDraft} onSaved={() => { void cache.invalidateQueries({ queryKey: ['fusion-agent-draft', agentParam] }); void cache.invalidateQueries({ queryKey: ['fusion-agent-list'] }); }} onPublish={() => agentDraft.data && openPublishAgent(agentDraft.data)} onDirtyChange={setWorkflowDirty} onClose={goBack} /> :
            <Spin tip="读取智能体草稿…"><div style={{ minHeight: 240 }} /></Spin>
          ) : selected?.config && catalog.data ? <WorkspaceWorkflow key={`${selected.id}-${environment}`} config={selected.config} catalog={catalog.data} project={selected.project.id} environment={environment} editable={writable} createMode={params.get('mode') === 'create'} onSaved={() => refreshProject()} onPublish={() => go('releases')} onDirtyChange={setWorkflowDirty} onClose={goBack} /> : <div className="hub-workflow-layout"><section className="hub-canvas" aria-label="工作流画布"><div className="hub-canvas-caption">工作流画布<small>创建智能体后，在这里查看真实任务与节点。</small></div><WorkspaceEmpty action={<Button disabled={!writable} onClick={() => setCreating(true)}>新建智能体</Button>}>暂无工作流配置。</WorkspaceEmpty></section><WorkspacePanel title="节点配置" subtitle="选择画布中的节点后查看模型与指令。"><WorkspaceEmpty>还没有可查看的节点。</WorkspaceEmpty></WorkspacePanel></div>)}
          {view === 'assets' && <WorkspaceAssets catalog={catalog.data} agents={agents} project={selectedProject?.id} environment={environment} onModels={() => navigate('/models')} onPrompts={() => navigate('/prompts')} onCenter={() => navigate('/capabilities/assets')} />}
          {view === 'use' && (selected?.config ? <FusionUsagePanel key={`${selected.id}-${environment}`} project={selected.project.id} projectName={selected.project.name} deployment={selected.config} releases={selected.releases} editable={writable} admin={admin} onPublish={() => go('releases')} onManage={() => go('runs')} /> : <WorkspaceEmpty action={<Button disabled={!writable} onClick={() => setCreating(true)}>新建智能体</Button>}>还没有绑定智能体。创建并上线后，即可查看 API 接入方式。</WorkspaceEmpty>)}
          {view === 'runs' && (selected?.config ? (selected.releases.length ? <FusionExecutionPanel key={`${selected.id}-${environment}`} project={selected.project.id} environment={environment} deployment={selected.config} releases={selected.releases} editable={writable} onChanged={() => refreshProject()} /> : <WorkspaceEmpty action={<Button type="primary" onClick={() => go('releases')}>生成运行版本</Button>}>当前项目部署还没有可运行版本。先生成运行版本，再上线并调试。</WorkspaceEmpty>) : <WorkspaceEmpty>先创建智能体，再发布并上线版本。</WorkspaceEmpty>)}
          {view === 'settings' && <WorkspaceSettings projects={data.projects.data || []} agents={agents} selectedProject={selectedProject} session={data.session.data} onCreate={() => setCreating(true)} />}
          {view === 'audit' && <WorkspaceAudit agents={agents} loading={data.auditLoading} />}
        </div>
      </>}
    </main>
    {creating && <FusionCreateAgent admin={admin} onClose={() => setCreating(false)} onCreated={agentId => { setCreating(false); refreshProject(); navigate(`/agent-hub/workflows?agent=${encodeURIComponent(agentId)}&mode=create`, { state: { from: { view: 'agents' } } }); }} />}
    {editor && <FusionDraftEditor key={`${editor.project}-${editor.environment}-${editor.deployment.revision}`} project={editor.project} environment={editor.environment} deployment={editor.deployment} catalog={editor.catalog} initialTab={editor.tab} onClose={() => setEditor(null)} onSaved={() => refreshProject(editor.project, editor.environment)} />}
    <Drawer open={resuming} title="完成项目绑定" width={760} onClose={() => { setResuming(false); refreshProject(); }}>{selectedProject && resuming && <FusionProjectPanel platformId={selectedProject.id} environment={environment} compact />}</Drawer>
    <WorkspaceSnapshot release={snapshot} onClose={() => setSnapshot(null)} />
    <Modal title={publishing?.restore ? `恢复 R${publishing.restore.sequence} 的配置` : '发布新版本'} open={!!publishing} okText={publishing?.restore ? '创建恢复版本' : '确认发布'} cancelText="取消" onCancel={() => setPublishing(null)} onOk={publish} confirmLoading={sending} okButtonProps={{ disabled: !note.trim() || !writable }}><div className="hub-form"><Alert type="info" showIcon title={publishing?.restore ? '恢复会生成新配置版本，保留全部历史与当前草稿。' : '发布会生成不可变配置快照，随后可在运行页面上线并调试。'} />{publicationError && <Alert type="error" title={publicationError} />}<label>发布说明<Input.TextArea aria-label="发布说明" rows={3} maxLength={500} value={note} onChange={event => setNote(event.target.value)} placeholder="说明本次更新了什么" /></label><p className="hub-help">保存和发布均使用真实后端。</p></div></Modal>
    <Modal title={`发布智能体版本 · ${agentPublishing?.agent.name || ''}`} open={!!agentPublishing} okText="确认发布" cancelText="取消" onCancel={() => setAgentPublishing(null)} onOk={() => void publishAgent()} confirmLoading={sending} okButtonProps={{ disabled: !note.trim() || !writable }}><div className="hub-form"><Alert type="info" showIcon title="发布会生成不可变智能体版本，随后可绑定到业务项目投入使用。" />{publicationError && <Alert type="error" title={publicationError} />}<label>发布说明<Input.TextArea aria-label="发布说明" rows={3} maxLength={500} value={note} onChange={event => setNote(event.target.value)} placeholder="说明本次更新了什么" /></label></div></Modal>
  </div>;
}
