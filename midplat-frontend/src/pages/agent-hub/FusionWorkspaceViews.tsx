import { FusionSnapshotNodes } from './FusionSnapshotNodes';
import { useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Drawer, Input, Tag } from 'antd';
import { Activity, AlertTriangle, ArrowRight, Bot, Boxes, Check, ChevronRight, GitBranch, LayoutGrid, List, LockKeyhole, Play, RotateCcw, Search, Settings2 } from 'lucide-react';
import { AgentRuntimePromptButton } from './AgentRuntimePromptEditor';
import { isAgentManagedProject } from '@/pages/platforms/apiCatalog';
import { assetKindLabel, fusionApi, sameConfiguration, type CatalogRevision, type FusionAgentSummary, type FusionAsset, type FusionCatalog, type FusionProject, type FusionRelease, type FusionSession } from './fusionApi';
import { FusionWorkflowEditor } from './FusionWorkflowEditor';
import { FusionAgentDetail } from './FusionAgentDetail';
import { auditLabel, dateLabel, type AgentCardItem, type WorkspaceAgent, type WorkspaceView } from './useFusionWorkspaceData';

export type NavigateWorkspace = (view: WorkspaceView, project?: string) => void;
export function WorkspacePanel({ title, subtitle, action, children }: { title: string; subtitle?: string; action?: ReactNode; children: ReactNode }) {
  return <section className="hub-panel"><div className="hub-panel-head"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{action}</div>{children}</section>;
}
export function WorkspaceEmpty({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return <div className="hub-empty"><span className="hub-empty-icon"><Bot size={25} /></span><p>{children}</p>{action}</div>;
}
export function latestResources(revisions: CatalogRevision[]) {
  const seen = new Set<string>();
  return revisions.filter(revision => !seen.has(revision.resourceId) && !!seen.add(revision.resourceId));
}
export function publishedRelease(agent: WorkspaceAgent) {
  return agent.releases.find(release => release.id === agent.config?.publishedReleaseId);
}
export function liveRelease(agent: WorkspaceAgent) {
  return agent.releases.find(release => release.id === agent.config?.activeReleaseId);
}
export function isDraftDirty(agent: WorkspaceAgent) {
  const current = publishedRelease(agent);
  return !!(agent.config && current && !sameConfiguration(agent.config.draft, current.snapshot.draft));
}
export function agentStage(agent: WorkspaceAgent): 'live' | 'pub' | 'draft' {
  if (agent.config?.activeReleaseId) return 'live';
  if (agent.config?.publishedReleaseId) return 'pub';
  return 'draft';
}
function relativeTime(value: string) {
  const time = new Date(value).getTime();
  if (Number.isNaN(time)) return dateLabel(value);
  const minutes = Math.round((Date.now() - time) / 60000);
  if (Math.abs(minutes) < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.round(hours / 24);
  if (days === 1) return '昨天';
  if (days < 7) return `${days} 天前`;
  return dateLabel(value);
}
function lastRun(agent: WorkspaceAgent, loading?: boolean) {
  const entry = agent.audit.find(item => item.action === 'run.created' || item.action === 'business.run.created');
  if (entry) return { ok: true, text: relativeTime(entry.created_at) };
  return { ok: false, text: loading ? '读取中' : '从未运行' };
}
function agentModelName(agent: WorkspaceAgent, catalog?: FusionCatalog) {
  const id = agent.config?.draft.defaultModelRevisionId;
  if (!id) return '尚未选择';
  const fromSnapshot = agent.releases.map(release => release.snapshot.models[id]).find(Boolean);
  const fromCatalog = catalog?.models.find(item => item.id === id) || catalog?.availableModels.find(item => item.id === id);
  return fromSnapshot?.content.name || fromCatalog?.content.name || '已配置模型';
}
export function stageBadge(agent: WorkspaceAgent) {
  if (agent.status === 'pending') return { cls: 'draft', label: '绑定待完成' };
  if (!agent.config) return { cls: 'draft', label: '读取中' };
  const live = liveRelease(agent);
  const published = publishedRelease(agent);
  if (agentStage(agent) === 'live') return { cls: 'live', label: `已上线 R${live?.sequence ?? published?.sequence}` };
  if (agentStage(agent) === 'pub') return { cls: 'pub', label: `待上线 R${published?.sequence}` };
  return { cls: 'draft', label: '草稿' };
}
export function AgentStatus({ agent }: { agent: WorkspaceAgent }) {
  const badge = stageBadge(agent);
  return <span className={`hub-st hub-st-${badge.cls}`}>{badge.label}</span>;
}
function bindingStatus(agent?: WorkspaceAgent) {
  if (!agent) return { cls: 'draft', label: '未创建' };
  if (isDraftDirty(agent)) return { cls: 'dirty', label: '有未发布修改' };
  return stageBadge(agent);
}
function lifecycleOf(agent: WorkspaceAgent, auditLoading?: boolean) {
  const config = agent.config;
  const published = publishedRelease(agent);
  const live = liveRelease(agent);
  const dirty = isDraftDirty(agent);
  const run = lastRun(agent, auditLoading);
  const furthest = run.ok ? 3 : live ? 2 : published ? 1 : 0;
  const mark = (index: number, reached: boolean) => {
    if (!reached) return '';
    return index < furthest ? 'done' : 'cur';
  };
  return {
    progress: furthest,
    nodes: [
      { cls: !config ? 'cur' : dirty ? 'warn' : mark(0, true), label: '草稿', val: config ? `修订 ${config.revision}` : '—', tip: dirty ? '有未发布修改' : config ? '已保存' : '尚未配置' },
      { cls: mark(1, !!published), label: '已发布', val: published ? `R${published.sequence}` : '—', tip: '不可变快照' },
      { cls: mark(2, !!live), label: '已上线', val: live ? '当前' : '—', tip: '新会话使用此版本' },
      { cls: mark(3, run.ok), label: '最近运行', val: run.ok ? run.text : '—', tip: '最近一次执行' },
    ],
  };
}
const auditRows = (agents: WorkspaceAgent[]) => agents.flatMap(agent => agent.audit.map(entry => ({ ...entry, agent }))).sort((a, b) => b.created_at.localeCompare(a.created_at));

export function WorkspaceOverview({ cards, agents, assetCount, detailsLoading, auditLoading, onCreate, go, goAgent, editable }: {
  cards: AgentCardItem[]; agents: WorkspaceAgent[]; assetCount?: number; detailsLoading: boolean; auditLoading: boolean;
  onCreate: () => void; go: NavigateWorkspace; goAgent: (agentId: string) => void; editable: boolean;
}) {
  const published = agents.filter(agent => agent.config?.publishedReleaseId).length + cards.filter(card => card.kind === 'agent' && card.agent.latestVersionId).length;
  const metrics = [
    { title: '智能体', value: cards.length, unit: '个', note: detailsLoading ? '正在读取发布状态' : `${published} 个已发布配置`, icon: Bot },
    { title: '能力资产', value: assetCount ?? '—', unit: '项', note: '中台模型与提示词目录', icon: Boxes },
    { title: '发布版本', value: detailsLoading ? '—' : agents.reduce((sum, agent) => sum + agent.releases.length, 0) + cards.reduce((sum, card) => sum + (card.kind === 'agent' ? card.agent.latestVersionSequence || 0 : 0), 0), unit: '个', note: '完整保留历史快照', icon: GitBranch },
    { title: '运行中版本', value: agents.filter(agent => agent.config?.activeReleaseId).length, unit: '个', note: '通过检查并已上线的版本', icon: Activity },
  ];
  return <>
    <section className="hub-welcome"><div><span className="hub-overline">从配置到运行，每一步都清晰</span><h2>搭建你的下一个智能体</h2><p>复用中台模型与能力资产，让草稿配置、版本发布和运行追踪衔接起来。</p><Button onClick={() => cards.length ? go('agents') : onCreate()} disabled={!cards.length && !editable} type="primary">开始配置<ArrowRight size={16} /></Button></div><div className="hub-journey">{[{ label: '配置', icon: Settings2 }, { label: '装配', icon: Boxes }, { label: '调试', icon: Play }, { label: '发布', icon: GitBranch }].map((step, index) => <div key={step.label}><span><step.icon size={23} /></span><strong>{step.label}</strong><small>{`0${index + 1}`}</small></div>)}</div></section>
    <div className="hub-metrics">{metrics.map(metric => <article className="hub-metric" key={metric.title}><div><span>{metric.title}</span><metric.icon size={19} /></div><strong>{metric.value}<small>{metric.unit}</small></strong><p>{metric.note}</p></article>)}</div>
    <div className="hub-overview-columns"><WorkspacePanel title="我的智能体" subtitle="继续完善草稿，或查看已发布的应用。" action={<Button type="link" onClick={() => go('agents')}>查看全部<ArrowRight size={14} /></Button>}>
      <div className="hub-agent-rows">{cards.slice(0, 4).map(card => card.kind === 'agent' ? <button className="hub-agent-row" key={card.key} onClick={() => goAgent(card.agent.id)}><span className="hub-bot-icon"><Bot size={19} /></span><span><strong>{card.agent.name}</strong><small>独立智能体 · 修订 {card.agent.draftRevision}{card.bindings.some(binding => binding.status !== 'unbound') ? ` · 已绑定 ${card.bindings.filter(binding => binding.status !== 'unbound').length} 项目` : ' · 未绑定'}</small></span><span className={`hub-st hub-st-${agentCardBadge(card).cls}`}>{agentCardBadge(card).label}</span><ChevronRight size={16} /></button> : <button className="hub-agent-row" key={card.key} onClick={() => go('agents', card.agent.project.id)}><span className="hub-bot-icon"><Bot size={19} /></span><span><strong>{card.agent.config?.name || card.agent.project.name}</strong><small>{card.agent.project.name} · {card.agent.config ? `${card.agent.config.draft.tasks.length} 个任务` : '正在读取配置'}</small></span><AgentStatus agent={card.agent} /><ChevronRight size={16} /></button>)}</div>
      {!cards.length && <WorkspaceEmpty action={<Button disabled={!editable} onClick={onCreate}>新建智能体</Button>}>还没有智能体，创建后会显示在这里。</WorkspaceEmpty>}
    </WorkspacePanel><WorkspacePanel title="完成一次配置发布" subtitle="从创建独立智能体开始，逐步完善并绑定到项目。"><ol className="hub-guide"><li><span>1</span><div><strong>创建智能体，编排任务流程</strong><p>独立草稿不绑定项目，手动拖拽编排节点。</p></div></li><li><span>2</span><div><strong>发布智能体版本</strong><p>不可变版本快照，可在项目间复用。</p></div></li><li><span>3</span><div><strong>绑定到业务项目投入使用</strong><p>随时绑定或解除，数据完整保留。</p></div></li></ol><Button block disabled={!editable} onClick={() => cards.length ? go('agents') : onCreate()}>开始配置<ArrowRight size={14} /></Button></WorkspacePanel></div>
    <WorkspacePanel title="近期动态" action={<Button type="link" onClick={() => go('audit')}>审计日志<ArrowRight size={14} /></Button>}><div className="hub-activity">{auditRows(agents).slice(0, 3).map((entry, index) => <div key={`${entry.created_at}-${index}`}><span className="hub-activity-dot" /><span><strong>{entry.agent.config?.name || entry.agent.project.name}</strong><small>{auditLabel(entry.action)} · {entry.principal}</small></span><time>{dateLabel(entry.created_at)}</time></div>)}</div>{!agents.some(agent => agent.audit.length) && <div className="hub-empty">{auditLoading ? '正在读取操作记录…' : '暂无操作记录，创建和发布后会显示真实动态。'}</div>}</WorkspacePanel>
  </>;
}

function agentCardBadge(card: Extract<AgentCardItem, { kind: 'agent' }>) {
  if (card.deployments.some(deployment => deployment.config?.activeReleaseId)) return { cls: 'live', label: '已上线' };
  if (card.deployments.some(deployment => deployment.config?.publishedReleaseId)) return { cls: 'pub', label: '待上线' };
  if (card.bindings.some(binding => binding.status !== 'unbound')) return { cls: 'pub', label: '已绑定' };
  if (card.agent.latestVersionId) return { cls: 'pub', label: `已发布 V${card.agent.latestVersionSequence ?? ''}` };
  return { cls: 'draft', label: '草稿' };
}

export function WorkspaceAgents({ cards, selectedCard, selected, catalog, projects, bindableProjects, environment, onSelect, onSelectAgent, onOpenBinding, onConfig, onCreate, onReleases, onRun, onUse, editable, onResume, onWorkflow, onWorkflowAgent, onSettings, onAudit, onPublish, onPublishAgent, publishing, auditLoading, onChanged }: {
  cards: AgentCardItem[]; selectedCard?: AgentCardItem; selected?: WorkspaceAgent; catalog?: FusionCatalog;
  projects: FusionProject[]; bindableProjects: FusionProject[]; environment: string;
  onSelect: (project: string) => void; onSelectAgent: (agentId: string) => void; onOpenBinding: (project: string, view: 'releases' | 'runs') => void; onConfig: () => void; onCreate: () => void; onReleases: () => void;
  onResume: () => void; onRun: () => void; onUse: () => void; onWorkflow: () => void; onWorkflowAgent: (agentId: string) => void; onSettings: () => void; onAudit: () => void;
  onPublish: () => void; onPublishAgent: (agent: FusionAgentSummary) => void; publishing?: boolean;
  editable: boolean; auditLoading?: boolean; onChanged: () => void;
}) {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  const [listView, setListView] = useState(false);
  const hayLegacy = (agent: WorkspaceAgent) => `${agent.config?.name || ''}${agent.project.name}${agentModelName(agent, catalog)}${agent.config?.draft.tasks.map(task => task.name).join(' ') || ''}`.toLowerCase();
  const hay = (card: AgentCardItem) => card.kind === 'agent'
    ? `${card.agent.name}${card.bindings.map(binding => projects.find(project => project.id === binding.project_id)?.name || binding.project_id).join('')}${card.deployments.map(item => item.config?.name || '').join('')}`.toLowerCase()
    : hayLegacy(card.agent);
  const matches = (card: AgentCardItem) => {
    if (!hay(card).includes(search.trim().toLowerCase())) return false;
    if (filter === 'all') return true;
    if (card.kind === 'legacy') return (filter === 'live' && agentStage(card.agent) === 'live') || (filter === 'pub' && agentStage(card.agent) === 'pub') || (filter === 'dirty' && isDraftDirty(card.agent));
    const badge = agentCardBadge(card);
    return (filter === 'live' && badge.cls === 'live') || (filter === 'pub' && badge.cls === 'pub') || (filter === 'dirty' && badge.cls === 'draft');
  };
  const stageOf = (card: AgentCardItem) => card.kind === 'legacy' ? agentStage(card.agent) : agentCardBadge(card).cls === 'live' ? 'live' : agentCardBadge(card).cls === 'pub' ? 'pub' : 'draft';
  const filtered = cards.filter(matches);
  const counts = { all: cards.length, live: cards.filter(card => stageOf(card) === 'live').length, pub: cards.filter(card => stageOf(card) === 'pub').length, dirty: cards.filter(card => stageOf(card) === 'draft').length };
  const config = selected?.config;
  const tasks = config?.draft.tasks || [];
  const shown = tasks.slice(0, 3);
  const lifecycle = selected ? lifecycleOf(selected, auditLoading) : { progress: 0, nodes: [] };
  const boundProjectIds = new Set(cards.flatMap(card => card.kind === 'agent'
    ? card.bindings.filter(binding => binding.status !== 'unbound' && binding.environment === environment).map(binding => binding.project_id)
    : []));
  const externalProjects = projects.filter(project => isAgentManagedProject(project) && !boundProjectIds.has(project.id));
  return <>
    <div className="hub-toolbar hub-agents-toolbar">
      <Input aria-label="搜索智能体" allowClear prefix={<Search size={15} />} placeholder="搜索智能体名称、项目或任务" value={search} onChange={event => setSearch(event.target.value)} />
      <div className="hub-seg" role="group" aria-label="按状态筛选">
        {([{ id: 'all', label: '全部' }, { id: 'live', label: '已上线' }, { id: 'pub', label: '已发布或绑定' }, { id: 'dirty', label: '草稿' }] as const).map(item => (
          <button key={item.id} type="button" className={filter === item.id ? 'on' : ''} aria-pressed={filter === item.id} onClick={() => setFilter(item.id)}>{item.label} <b>{counts[item.id]}</b></button>
        ))}
      </div>
      <Button aria-pressed={listView} onClick={() => setListView(value => !value)} icon={listView ? <LayoutGrid size={15} /> : <List size={15} />}>{listView ? '卡片视图' : '列表视图'}</Button>
      <span className="hub-count">共 {filtered.length} 个智能体</span>
    </div>
    <div className="hub-agents-layout">
      <div className={`hub-agent-grid${listView ? ' is-list' : ''}`}>
        {filtered.map(card => {
          if (card.kind === 'agent') {
            const badge = agentCardBadge(card);
            const boundNames = card.bindings.filter(binding => binding.status !== 'unbound').map(binding => {
              const name = projects.find(project => project.id === binding.project_id)?.name || binding.project_id;
              return binding.environment === environment ? name : `${name}（${binding.environment}）`;
            });
            return <button key={card.key} className={`hub-agent-card ${selectedCard?.key === card.key ? 'selected' : ''}`} aria-pressed={selectedCard?.key === card.key} onClick={() => onSelectAgent(card.agent.id)}>
              <div className="hub-ac-top">
                <span className="hub-ac-icon"><Bot size={19} /></span>
                <span className="hub-ac-title"><h3>{card.agent.name}</h3><span className="hub-ac-proj">独立智能体 · 修订 {card.agent.draftRevision}</span></span>
                <span className={`hub-st hub-st-${badge.cls}`}>{badge.label}</span>
              </div>
              <div className="hub-ac-tasks"><span className="hub-ac-t1">{boundNames.length ? `已绑定：${boundNames.join('、')}` : '尚未绑定项目'}</span>{boundNames.length > 1 ? <span className="hub-ac-more">{boundNames.length}</span> : null}</div>
              <div className="hub-ac-foot">
                <span className="hub-ac-model">{card.deployments.length ? `${card.deployments.length} 个项目部署` : '尚未部署到项目'}{card.agent.updatedAt ? ` · 更新于 ${dateLabel(card.agent.updatedAt)}` : ''}</span>
              </div>
            </button>;
          }
          const agent = card.agent;
          const badge = stageBadge(agent);
          const extra = (agent.config?.draft.tasks.length || 0) - 1;
          const firstTask = agent.config?.draft.tasks[0]?.name || (agent.status === 'pending' ? '正在完成项目绑定与配置读取' : '待配置任务');
          const runInfo = lastRun(agent, auditLoading);
          return <button key={card.key} className={`hub-agent-card ${selectedCard?.key === card.key ? 'selected' : ''}`} aria-pressed={selectedCard?.key === card.key} onClick={() => onSelect(agent.project.id)}>
            <div className="hub-ac-top">
              <span className="hub-ac-icon"><Bot size={19} /></span>
              <span className="hub-ac-title"><h3>{agent.config?.name || agent.project.name}</h3><span className="hub-ac-proj">{agent.project.name} · 独立项目部署</span></span>
              <span className={`hub-st hub-st-${badge.cls}`}>{badge.label}</span>
            </div>
            <div className="hub-ac-tasks"><span className="hub-ac-t1">{firstTask}</span>{extra > 0 ? <span className="hub-ac-more">+{extra}</span> : null}</div>
            <div className="hub-ac-foot">
              <span className="hub-ac-model">{agentModelName(agent, catalog)} · {agent.config ? `${agent.config.draft.tasks.length} 任务` : '待配置'}</span>
              {isDraftDirty(agent) ? <span className="hub-dirty">未发布修改</span> : null}
              <span className={`hub-ac-run ${runInfo.ok ? 'ok' : 'none'}`}>{runInfo.ok ? <Check size={12} /> : <AlertTriangle size={12} />}{runInfo.text}</span>
            </div>
          </button>;
        })}
        {!filtered.length && <WorkspaceEmpty action={cards.length ? <Button onClick={() => { setSearch(''); setFilter('all'); }}>清除筛选</Button> : <Button disabled={!editable} onClick={onCreate}>新建智能体</Button>}>{cards.length ? '没有匹配的智能体，请调整搜索条件。' : '还没有智能体，创建第一个智能体并进入编排。'}</WorkspaceEmpty>}
      </div>
      <aside className="hub-agent-detail" aria-label="智能体详情">
        {selectedCard?.kind === 'agent' ? <FusionAgentDetail agent={selectedCard.agent} bindings={selectedCard.bindings} deployments={selectedCard.deployments} projects={projects} bindableProjects={bindableProjects} environment={environment} editable={editable} onWorkflow={() => onWorkflowAgent(selectedCard.agent.id)} onPublish={() => onPublishAgent(selectedCard.agent)} onOpenProject={onOpenBinding} onChanged={onChanged} onDeleted={() => onSelectAgent('')} /> : selected ? <>
          <div className="hub-d-head">
            <div className="hub-d-row"><h2>{config?.name || selected.project.name}</h2><AgentStatus agent={selected} /></div>
            <div className="hub-d-meta">{selected.project.name} · 独立项目部署 · 模型与提示词在智能体配置中维护</div>
          </div>
          <div className="hub-lifecycle">
            <h3>生命周期</h3>
            <div className="hub-lc-track" data-progress={lifecycle.progress} role="list">{lifecycle.nodes.map(node => <div key={node.label} className={`hub-lc-node ${node.cls}`} role="listitem" title={node.tip}><span className="hub-lc-dot">{node.cls === 'done' || node.cls === 'cur' ? <Check size={11} /> : node.cls === 'warn' ? '!' : null}</span><span className="hub-lc-lb">{node.label}</span><span className="hub-lc-val">{node.val}</span></div>)}</div>
          </div>
          <dl className="hub-d-facts">
            <div><dt>模型</dt><dd title={agentModelName(selected, catalog)}>{agentModelName(selected, catalog)}</dd></div>
            <div><dt>草稿修订</dt><dd>{config ? config.revision : '—'}</dd></div>
            <div><dt>任务数</dt><dd>{config ? `${config.draft.tasks.length} 项` : '—'}</dd></div>
            <div><dt>归属项目</dt><dd title={selected.project.name}>{selected.project.name}</dd></div>
          </dl>
          <div className="hub-d-tasks">
            <h3>已配置任务</h3>
            <div className="hub-tags">{shown.map(task => <span className="hub-chip" key={task.key}>{task.name}</span>)}{tasks.length > shown.length ? <span className="hub-chip">+{tasks.length - shown.length}</span> : null}</div>
          </div>
          <div className="hub-d-actions">
            {selected.status === 'pending'
              ? <Button type="primary" block disabled={!editable} onClick={onResume}>继续完成项目绑定</Button>
              : <Button type="primary" block disabled={!editable || !config || !catalog} onClick={onWorkflow}>进入编排工作台 <ChevronRight size={15} /></Button>}
            <div className="hub-d-row-btns">
              <Button disabled={!editable || !config || publishing} onClick={onPublish}>发布新版本</Button>
              <Button disabled={!config} aria-label="查看版本与发布" onClick={onReleases}>版本历史</Button>
              <Button disabled={!config} onClick={onRun}>运行调试</Button>
              <Button disabled={!config} onClick={onUse}>API 接入</Button>
            </div>
            <div className="hub-d-links">
              <Button type="link" disabled={!editable || !config || !catalog} onClick={onConfig}>智能体配置</Button>
              {isAgentManagedProject(selected.project) ? <AgentRuntimePromptButton platformId={selected.project.id} platformName={selected.project.name} disabled={!editable} /> : null}
              <Button type="link" onClick={onSettings}>治理设置</Button>
              <Button type="link" onClick={onAudit}>审计记录</Button>
            </div>
          </div>
        </> : <WorkspaceEmpty>选择一个智能体后，这里展示它的生命周期、模型、任务与可执行操作。</WorkspaceEmpty>}
      </aside>
    </div>
    {/* 外部运行时提示词入口：它属于外部运行时，不是中台草稿，因此不随治理设置面板一起下线。 */}
    {externalProjects.length ? <section className="hub-panel hub-runtime-config" aria-label="外部智能体配置">
      <div className="hub-panel-head"><div><h2>外部智能体配置</h2><p>外部运行时的 SOUL 与任务技能在这里编辑，不受项目是否已绑定智能体影响。</p></div></div>
      <div className="hub-runtime-list">{externalProjects.map(project => <div className="hub-runtime-row" key={project.id}>
        <div><strong>{project.name}</strong><small>外部运行时 · 提示词与模型由该运行时自身维护</small></div>
        <AgentRuntimePromptButton platformId={project.id} platformName={project.name} disabled={!editable} />
      </div>)}</div>
    </section> : null}
  </>;
}

export function WorkspaceReleases({ agent, onSnapshot, onRestore, onConfig, onCreate, editable, onRun, onPublish }: {
  agent?: WorkspaceAgent; onSnapshot: (release: FusionRelease) => void; onRestore: (release: FusionRelease) => void;
  onConfig: () => void; onCreate: () => void; editable: boolean; onRun: () => void; onPublish: () => void;
}) {
  const config = agent?.config; const releases = agent?.releases || [];
  const current = releases.find(item => item.id === config?.publishedReleaseId);
  const changed = !current || !sameConfiguration(config?.draft, current.snapshot.draft);
  return <><div className="hub-release-banner"><div><span className="hub-bot-icon"><GitBranch size={24} /></span><div><strong>当前草稿</strong><p>{config ? `修订 ${config.revision} · ${changed ? '有待发布修改' : '与已发布配置一致'}` : '创建智能体后开始维护草稿与版本'}</p></div></div><Button disabled={!editable || !config} onClick={onConfig}>继续编辑草稿</Button></div><WorkspacePanel title="版本历史" subtitle={`${releases.length} 个版本 · 点击快照查看发布时的具体内容`}><div className="hub-release-list">{releases.map(release => <article className="hub-release-card" key={release.id}><div className="hub-release-marker"><GitBranch size={20} /></div><div className="hub-release-content"><div className="hub-inline"><h3>R{release.sequence}</h3>{config?.publishedReleaseId === release.id ? <Tag color="green">当前配置</Tag> : <Tag>历史版本</Tag>}{release.restoredFrom && <Tag color="blue">历史恢复</Tag>}</div><p>{release.note}</p><small>{dateLabel(release.createdAt)} · {release.snapshot.tasks.length} 个任务 · 摘要 {release.hash.slice(0, 12)}</small></div><div className="hub-release-actions">{release.id === config?.activeReleaseId ? <span className="hub-run-status">运行中</span> : null}<Button onClick={onRun}>上线与调试</Button><Button onClick={() => onSnapshot(release)}>查看快照</Button>{release.id !== config?.publishedReleaseId && <Button disabled={!editable || !!config?.pendingJobId} onClick={() => onRestore(release)} icon={<RotateCcw size={14} />}>恢复此配置</Button>}</div></article>)}</div>{!releases.length && <WorkspaceEmpty action={!agent ? <Button disabled={!editable} onClick={onCreate}>新建智能体</Button> : <Button type="primary" disabled={!editable || !config} onClick={onPublish}>发布运行版本</Button>}>{agent ? '绑定只创建项目草稿；发布运行版本后才能上线。' : '还没有智能体，创建后会在这里保留真实版本历史。'}</WorkspaceEmpty>}<p className="hub-help">恢复历史配置会创建新版本，保留全部历史和当前草稿。</p></WorkspacePanel></>;
}

export function WorkspaceWorkflow(props: Parameters<typeof FusionWorkflowEditor>[0]) {
  return <FusionWorkflowEditor {...props} />;
}

const sharedAssetChip = { 模型: 'hub-chip-p', 提示词: 'hub-chip-t' } as const;
const projectAssetChip = { skill: 'hub-chip-t', tool: 'hub-chip-y', knowledge: '', data: 'hub-chip-p' } as const;
const assetKinds = ['全部', '模型', '提示词', '技能', '工具', '知识库', '数据源'];

function projectAssetSummary(asset: FusionAsset) {
  if (asset.kind === 'tool') return [asset.content.readOnly ? '只读接口' : null, asset.content.method, asset.content.description].filter(Boolean).join(' · ');
  if (asset.kind === 'knowledge' || asset.kind === 'data') return [asset.content.documents?.length ? `${asset.content.documents.length} 篇文档` : null, asset.content.description].filter(Boolean).join(' · ');
  return asset.content.body?.slice(0, 100) || asset.content.description || '';
}

export function WorkspaceAssets({ catalog, agents, project, environment, onModels, onPrompts, onCenter }: {
  catalog?: FusionCatalog; agents: WorkspaceAgent[]; project?: string; environment: string;
  onModels: () => void; onPrompts: () => void; onCenter: () => void;
}) {
  const [kind, setKind] = useState('全部'); const [search, setSearch] = useState('');
  const [sharedView, setSharedView] = useState<(CatalogRevision & { type: string }) | null>(null);
  const [assetView, setAssetView] = useState<FusionAsset | null>(null);
  const projectAssets = useQuery({ queryKey: ['fusion-assets', project, environment], queryFn: () => fusionApi.assets(project!, environment), enabled: !!project, retry: false });
  const shared = [...latestResources(catalog?.availableModels || []).map(item => ({ ...item, type: '模型' })), ...latestResources(catalog?.prompts || []).map(item => ({ ...item, type: '提示词' }))];
  const local = projectAssets.data || [];
  const term = search.trim().toLowerCase();
  const countOf = (item: string) => shared.filter(asset => item === '全部' || asset.type === item).length + local.filter(asset => item === '全部' || assetKindLabel[asset.kind] === item).length;
  const visibleKind = kind === '全部' || countOf(kind) > 0 ? kind : '全部';
  const sharedFiltered = shared.filter(asset => (visibleKind === '全部' || asset.type === visibleKind) && `${asset.content.name}${asset.content.model || ''}${asset.content.body || ''}`.toLowerCase().includes(term));
  const localFiltered = local.filter(asset => (visibleKind === '全部' || assetKindLabel[asset.kind] === visibleKind) && `${asset.name} ${asset.content.description || ''} ${asset.content.body || ''}`.toLowerCase().includes(term));
  const references = (resource: CatalogRevision) => {
    const ids = new Set([...(catalog?.availableModels || []), ...(catalog?.prompts || [])].filter(item => item.resourceId === resource.resourceId).map(item => item.id));
    return agents.filter(agent => { const draft = agent.config?.draft; return draft && (ids.has(draft.defaultModelRevisionId) || (draft.role.kind === 'template' && ids.has(draft.role.revisionId)) || draft.tasks.some(task => ids.has(task.modelRevisionId || '') || task.nodes.some(node => ids.has(node.modelRevisionId || '')))); });
  };
  return <WorkspacePanel title="能力资产" subtitle="中台共享的模型与提示词，以及本项目的技能、工具、知识与数据引用；按类型筛选，点开卡片看详情。"
    action={<div className="hub-actions"><Button onClick={onModels}>管理模型目录</Button><Button onClick={onPrompts}>管理提示词模板</Button><Button type="link" onClick={onCenter}>前往能力中心</Button></div>}>
    <Alert type="info" showIcon title="主数据在中台能力中心维护" description="项目只引用固定修订；需要新建或修改资产内容时，前往能力中心创建新修订。" />
    {projectAssets.error && <Alert type="error" showIcon title="项目能力引用读取失败" description={(projectAssets.error as Error).message} />}
    <div className="hub-toolbar"><div className="hub-filter-tabs" role="group" aria-label="资产类型">{assetKinds.filter(item => item === '全部' || countOf(item) > 0).map(item => <button aria-pressed={visibleKind === item} className={visibleKind === item ? 'active' : ''} key={item} onClick={() => setKind(item)}>{item}<small>{countOf(item)}</small></button>)}</div><Input aria-label="搜索资产" value={search} onChange={event => setSearch(event.target.value)} allowClear prefix={<Search size={15} />} placeholder="搜索资产名称或内容" className="hub-search" /></div>
    {project && !projectAssets.isPending && !projectAssets.error && !local.length ? <p className="hub-help">当前项目暂无自定义技能、工具、知识库或数据源，可<Button type="link" size="small" onClick={onCenter}>前往能力中心</Button>创建。</p> : null}
    <div className="hub-assets-grid">
      {sharedFiltered.map(asset => <button className="hub-asset-card" key={`shared-${asset.type}-${asset.resourceId}`} onClick={() => setSharedView(asset)}>
        <div className="hub-asset-top"><span className={`hub-chip ${sharedAssetChip[asset.type as keyof typeof sharedAssetChip]}`}>{asset.type}</span><span className="hub-chip hub-chip-g">中台共享</span></div>
        <h3>{asset.content.name}</h3>
        <p>{asset.content.model || asset.content.body?.slice(0, 100) || '中台共享目录资源'}</p>
        <div className="hub-asset-ft"><span>{references(asset).length} 个智能体引用</span><span>{asset.content.version || '固定修订'}</span></div>
      </button>)}
      {localFiltered.map(asset => <button className="hub-asset-card" key={`local-${asset.id}`} onClick={() => setAssetView(asset)}>
        <div className="hub-asset-top"><span className={`hub-chip ${projectAssetChip[asset.kind]}`}>{assetKindLabel[asset.kind]}</span><span className={`hub-chip ${asset.enabled ? '' : 'hub-chip-p'}`}>{asset.enabled ? '启用中' : '已停用'}</span></div>
        <h3>{asset.name}</h3>
        <p>{projectAssetSummary(asset)}</p>
        <div className="hub-asset-ft"><span>项目引用</span><span>修订 {asset.revision}</span></div>
      </button>)}
    </div>
    {!sharedFiltered.length && !localFiltered.length && <WorkspaceEmpty>{catalog || local.length ? '没有匹配的资产，请调整筛选或搜索。' : '正在读取能力资产…'}</WorkspaceEmpty>}
    <Drawer title={sharedView?.content.name} open={!!sharedView} onClose={() => setSharedView(null)} width={600}>{sharedView && <div className="hub-form"><div><span className={`hub-chip ${sharedAssetChip[sharedView.type as keyof typeof sharedAssetChip]}`}>{sharedView.type}</span><span className="hub-chip">{sharedView.content.version || '固定修订'}</span></div><p className="hub-help">修订时间：{dateLabel(sharedView.createdAt)} · 摘要 {sharedView.hash.slice(0, 12)}</p><h3>{sharedView.type === '模型' ? '模型配置' : '模板正文'}</h3><pre className="hub-content-block">{sharedView.content.body || `模型名称：${sharedView.content.name}\n模型标识：${sharedView.content.model || ''}`}</pre><h3>当前引用</h3>{references(sharedView).map(agent => <div className="hub-asset-option" key={agent.id}><Bot size={18} /><strong>{agent.config?.name || agent.project.name}</strong></div>)}{!references(sharedView).length && <p className="hub-help">暂无智能体草稿引用此资源。</p>}<p className="hub-help">目录后续修改会生成新的修订，已发布配置继续保留原快照。</p></div>}</Drawer>
    <Drawer open={!!assetView} title={assetView ? `能力修订 · ${assetView.name}` : ''} width={640} onClose={() => setAssetView(null)}>
      {assetView && <div className="hub-form">
        <label>类型与版本<Input aria-label="类型与版本" value={`${assetKindLabel[assetView.kind]} · 修订 ${assetView.revision} · ${assetView.revisionId.slice(0, 8)}`} readOnly /></label>
        {assetView.kind === 'skill' && <label>技能指令<Input.TextArea aria-label="技能指令" rows={12} value={assetView.content.body || ''} readOnly /></label>}
        {assetView.kind === 'tool' && <>
          <label>接口地址<Input aria-label="接口地址" value={assetView.content.url || ''} readOnly /></label>
          <label>用途说明<Input.TextArea aria-label="用途说明" rows={3} value={assetView.content.description || ''} readOnly /></label>
        </>}
        {(assetView.kind === 'knowledge' || assetView.kind === 'data') && (assetView.content.documents || []).map((document, index) => <section className="comp" key={index}>
          <strong>文档 {index + 1}</strong>
          <label>文档标题<Input aria-label={`文档 ${index + 1} 标题`} value={document.title} readOnly /></label>
          <label>可检索内容<Input.TextArea aria-label={`文档 ${index + 1} 内容`} rows={6} value={document.text} readOnly /></label>
        </section>)}
        <Alert type="info" title="如需修改能力内容，请在中台能力中心创建新的固定修订，再在任务编排中引用。" />
      </div>}
    </Drawer>
  </WorkspacePanel>;
}


export function WorkspaceSettings({ projects, agents, selectedProject, session, onCreate }: {
  projects: FusionProject[]; agents: WorkspaceAgent[]; selectedProject?: FusionProject; session?: FusionSession;
  onCreate: () => void;
}) {
  const gates = [
    { title: '配置与版本管理', desc: '真实目录、项目草稿、版本发布和审计已接入。', state: '已接入' },
    { title: '真实模型执行', desc: '独立运行单元、任务输出、取消与运行记录已接入。', state: '已接入' },
    { title: '管理入口', desc: session?.intranetManagement ? '沿用现有中台内网入口。' : session?.localAuthentication ? '当前通过 SSH 隧道访问。' : '当前使用已接入的身份提供方。', state: session?.intranetManagement ? '内网访问' : session?.localAuthentication ? '本地访问' : '已接入' },
  ];
  return <><div className="hub-overview-columns">
    <WorkspacePanel title="访问与发布策略" subtitle="身份与权限由当前登录会话确定。">{[{ title: '项目隔离', note: '各项目分别保存草稿与配置版本，账号、项目和客户凭证权限保持不变。' }, { title: '统一的发布入口', note: '发布任务持久化保存，配置版本保留完整快照。' }, { title: '恢复保留历史记录', note: '历史恢复创建新版本，并保留当前草稿。' }].map(item => <div className="hub-policy" key={item.title}><span className="hub-policy-ic"><LockKeyhole size={18} /></span><div><strong>{item.title}</strong><p>{item.note}</p></div><span className="hub-chip hub-chip-g">已接入</span></div>)}</WorkspacePanel>
    <WorkspacePanel title="分阶段上线准备" subtitle="按实际接入状态展示。">{gates.map((item, index) => <div className="hub-policy" key={item.title}><span className="hub-policy-ic"><strong className="hub-mono">{index + 1}</strong></span><div><strong>{item.title}</strong><p>{item.desc}</p></div><span className="hub-chip hub-chip-g">{item.state}</span></div>)}</WorkspacePanel>
  </div>
    <WorkspacePanel title="业务项目绑定" subtitle="项目登记来自中台，各项目保持独立草稿与发布。" action={<Button disabled={!session?.identity.writable} onClick={onCreate}>新建智能体</Button>}><div className="hub-table-scroll"><table className="hub-table"><thead><tr><th>项目</th><th>智能体</th><th>草稿修订</th><th>状态</th></tr></thead><tbody>{projects.map(project => { const agent = agents.find(item => item.project.id === project.id); const status = bindingStatus(agent); return <tr key={project.id}><td>{project.name}</td><td>{agent?.config?.name || (agent ? '绑定处理中' : '未绑定')}</td><td className="hub-mono">{agent?.config?.revision ?? '—'}</td><td><span className={`hub-st hub-st-${status.cls}`}>{status.label}</span></td></tr>; })}</tbody></table></div><p className="hub-help">模型与提示词在「智能体配置」里直接选择，不再需要按项目授权；这里只保留访问与发布策略以及项目绑定概况。</p></WorkspacePanel>
  </>;
}

export function WorkspaceAudit({ agents, loading }: { agents: WorkspaceAgent[]; loading: boolean }) {
  const [query, setQuery] = useState('');
  const rows = auditRows(agents).filter(row => `${row.principal}${row.agent.config?.name || ''}${row.agent.project.name}${auditLabel(row.action)}`.includes(query.trim()));
  return <WorkspacePanel title="操作记录" subtitle="追溯真实创建、草稿和版本操作。"><div className="hub-toolbar"><Input aria-label="搜索审计记录" className="hub-search" allowClear prefix={<Search size={15} />} value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索操作、项目或智能体" /><span className="hub-help">{loading ? '读取中…' : `共 ${rows.length} 条`}</span></div><div className="hub-table-scroll"><table className="hub-table"><thead><tr><th>时间</th><th>操作者</th><th>操作</th><th>对象</th><th>详情</th></tr></thead><tbody>{rows.map((row, index) => { const release = row.agent.releases.find(item => item.id === row.target); return <tr key={`${row.created_at}-${index}`}><td>{dateLabel(row.created_at)}</td><td>{row.principal}</td><td><Tag>{auditLabel(row.action)}</Tag></td><td>{row.agent.config?.name || row.agent.project.name}</td><td>{release ? `配置 R${release.sequence}` : row.action === 'draft.saved' ? `草稿修订 ${row.target}` : row.agent.project.name}</td></tr>; })}</tbody></table></div>{!rows.length && <WorkspaceEmpty>{loading ? '正在读取操作记录…' : '没有匹配的操作记录。'}</WorkspaceEmpty>}</WorkspacePanel>;
}

export function WorkspaceSnapshot({ release, onClose }: { release: FusionRelease | null; onClose: () => void }) {
  return <Drawer open={!!release} title={release ? `配置快照 R${release.sequence}` : '配置快照'} width={760} onClose={onClose}>{release && <div className="hub-form"><div><Tag color="green">不可变配置</Tag><Tag>R{release.sequence}</Tag></div><p>{release.note}</p><p className="hub-help">{dateLabel(release.createdAt)} · 内容摘要 {release.hash}</p><h3>角色规则</h3><pre className="hub-content-block">{release.snapshot.effectiveRole}</pre>{release.snapshot.tasks.map(task => <section className="fusion-task-editor" key={task.key}><h3>{task.name}</h3><p>模型：{release.snapshot.models[task.modelRevisionId]?.content.name}</p><pre className="hub-content-block">{task.systemInstructions.join('\n\n')}</pre><FusionSnapshotNodes task={task} models={release.snapshot.models} /></section>)}</div>}</Drawer>;
}
