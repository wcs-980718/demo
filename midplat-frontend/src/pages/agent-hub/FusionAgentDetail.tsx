import { createUuid } from '../../createUuid';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Drawer, Empty, Input, Modal, Select, Tag, message } from 'antd';
import { ChevronRight, GitBranch, Link2, Pencil, Trash2, Unlink } from 'lucide-react';
import { AgentRuntimePromptButton } from './AgentRuntimePromptEditor';
import { fusionApi, type FusionAgentSummary, type FusionBindingRow, type FusionProject } from './fusionApi';
import { isAgentManagedProject } from '@/pages/platforms/apiCatalog';
import { dateLabel, type WorkspaceAgent } from './useFusionWorkspaceData';

const bindingStatusLabel = { pending: '绑定中', ready: '已绑定', unbound: '已解绑' } as const;

/** 独立智能体详情：生命周期、版本历史与项目绑定管理（绑定/解绑/设默认）。 */
export function FusionAgentDetail({ agent, bindings, deployments, projects, bindableProjects, environment, editable, onWorkflow, onPublish, onOpenProject, onChanged, onDeleted }: {
  agent: FusionAgentSummary; bindings: FusionBindingRow[]; deployments: WorkspaceAgent[]; projects: FusionProject[]; bindableProjects: FusionProject[];
  environment: string; editable: boolean;
  onWorkflow: () => void; onPublish: () => void; onOpenProject: (project: string, view: 'releases' | 'runs') => void; onChanged: () => void; onDeleted: () => void;
}) {
  const cache = useQueryClient();
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [bindOpen, setBindOpen] = useState(false);
  const [bindProject, setBindProject] = useState('');
  const [bindVersion, setBindVersion] = useState('');
  const [bindAlias, setBindAlias] = useState('default');
  const [bindName, setBindName] = useState('');
  const [binding, setBinding] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [savingInfo, setSavingInfo] = useState(false);
  const versions = useQuery({ queryKey: ['fusion-agent-versions', agent.id], queryFn: () => fusionApi.agentVersions(agent.id), retry: false });
  const preflight = useQuery({
    queryKey: ['fusion-preflight', bindProject, environment, agent.id, bindVersion],
    queryFn: () => fusionApi.preflightBinding(bindProject, environment, { agentId: agent.id, agentVersionId: bindVersion }),
    enabled: !!bindProject && !!bindVersion, retry: false,
  });
  const refresh = async () => {
    await Promise.all([
      cache.invalidateQueries({ queryKey: ['fusion-bindings'] }),
      cache.invalidateQueries({ queryKey: ['fusion-agent-list'] }),
      cache.invalidateQueries({ queryKey: ['fusion-project'] }),
      versions.refetch(),
    ]);
    onChanged();
  };
  const unbind = (row: FusionBindingRow) => {
    const project = projects.find(item => item.id === row.project_id);
    Modal.confirm({
      title: `解除与「${project?.name || row.project_id}」的绑定？`,
      content: `解绑后该项目不再使用此智能体；部署、草稿与版本数据完整保留，重新绑定同一版本可恢复原部署。${row.environment !== environment ? `该绑定位于环境 ${row.environment}。` : ''}`,
      okText: '解除绑定', cancelText: '取消', okButtonProps: { danger: true },
      onOk: async () => {
        try { await fusionApi.unbindAgent(row.project_id, row.environment, row.binding_id); message.success('已解除绑定'); await refresh(); }
        catch (error) { message.error((error as Error).message); }
      },
    });
  };
  const setDefault = async (row: FusionBindingRow) => {
    try {
      await fusionApi.setDefaultBinding(row.project_id, row.environment, row.binding_id);
      cache.setQueryData<FusionBindingRow[]>(['fusion-bindings'], rows => rows?.map(item => item.project_id === row.project_id && item.environment === row.environment ? { ...item, is_default: item.binding_id === row.binding_id } : item));
      message.success('已设为默认路由'); await refresh();
    }
    catch (error) { message.error((error as Error).message); }
  };
  const removeAgent = () => {
    Modal.confirm({
      title: `永久删除「${agent.name}」？`,
      content: '删除后不可恢复：草稿、已发布版本、项目部署、运行历史与绑定记录将全部移除。只想暂停使用时请改用「解绑」。',
      okText: '永久删除', cancelText: '取消', okButtonProps: { danger: true },
      onOk: async () => {
        try { await fusionApi.deleteAgent(agent.id); message.success('智能体已删除'); onDeleted(); await refresh(); }
        catch (error) { message.error((error as Error).message); }
      },
    });
  };
  const openEdit = () => { setEditName(agent.name); setEditDescription(agent.description || ''); setEditOpen(true); };
  const saveInfo = async () => {
    setSavingInfo(true);
    try { await fusionApi.updateAgent(agent.id, { name: editName.trim(), description: editDescription.trim() }); message.success('基础信息已更新'); setEditOpen(false); await refresh(); }
    catch (error) { message.error((error as Error).message); }
    finally { setSavingInfo(false); }
  };
  const bind = async () => {
    if (!bindProject || !bindVersion) return;
    setBinding(true);
    try {
      await fusionApi.createBinding(bindProject, environment, { agentId: agent.id, agentVersionId: bindVersion, alias: bindAlias || 'default', name: bindName || agent.name, idempotencyKey: createUuid() });
      message.success('已绑定到项目；设为默认路由后可生成运行版本并上线'); setBindOpen(false); setBindProject(''); setBindVersion(''); setBindAlias('default'); setBindName('');
      await refresh();
    } catch (error) { message.error((error as Error).message); }
    finally { setBinding(false); }
  };
  // 预填的项目可能已有其他部署而不在 bindableProjects 中，补进选项以显示名称而非裸 id
  const bindProjectOptions = [...bindableProjects, ...(bindProject && !bindableProjects.some(item => item.id === bindProject) ? projects.filter(item => item.id === bindProject) : [])].map(item => ({ value: item.id, label: item.name, title: item.name }));
  const activeBindings = bindings.filter(row => row.status !== 'unbound');
  const unboundBindings = bindings.filter(row => row.status === 'unbound');
  const projectName = (id: string) => projects.find(item => item.id === id)?.name || id;
  const bindingView = (row: FusionBindingRow) => {
    if (!row.is_default) return { state: '设为默认后可管理', action: '设为默认', view: 'releases' as const };
    const deployment = deployments.find(item => item.id === row.deployment_id);
    const live = deployment?.releases.find(release => release.id === deployment.config?.activeReleaseId);
    if (live) return { state: `运行中 R${live.sequence}`, action: '运行调试', view: 'runs' as const };
    const published = deployment?.releases.find(release => release.id === deployment.config?.publishedReleaseId);
    if (published) return { state: `待上线 R${published.sequence}`, action: '上线版本', view: 'runs' as const };
    return { state: '待生成运行版本', action: '准备上线', view: 'releases' as const };
  };
  return <>
    <div className="hub-d-head">
      <div className="hub-d-row"><div className="hub-inline"><h2>{agent.name}</h2><Button type="text" size="small" aria-label="编辑基础信息" icon={<Pencil size={14} />} disabled={!editable} onClick={openEdit} /></div><span className={`hub-st ${activeBindings.length ? 'hub-st-pub' : 'hub-st-draft'}`}>{activeBindings.length ? `已绑定 ${activeBindings.length} 个项目` : '未绑定项目'}</span></div>
      <div className="hub-d-meta">独立智能体 · 不依赖项目创建，可绑定到多个项目或解除绑定{agent.description ? ` · ${agent.description}` : ''}</div>
    </div>
    <dl className="hub-d-facts">
      <div><dt>草稿修订</dt><dd>{agent.draftRevision}</dd></div>
      <div><dt>最新版本</dt><dd>{agent.latestVersionSequence ? `V${agent.latestVersionSequence}` : '未发布'}</dd></div>
      <div><dt>更新时间</dt><dd>{agent.updatedAt ? dateLabel(agent.updatedAt) : '—'}</dd></div>
      <div><dt>标识</dt><dd title={agent.id}>{agent.id.slice(0, 8)}</dd></div>
    </dl>
    <div className="hub-d-tasks">
      <h3>项目绑定</h3>
      <div className="hub-binding-list">
        {activeBindings.map(row => {
          const deployment = deployments.find(item => item.id === row.deployment_id);
          const state = bindingView(row);
          return <div className="hub-binding-row" key={row.binding_id}>
            <div><strong>{projectName(row.project_id)}</strong><small>别名 {row.alias}{row.environment !== environment ? ` · 环境 ${row.environment}` : ''} · {row.status === 'pending' ? '部署创建中' : !row.is_default ? '设为默认后可管理' : deployment?.config ? `${deployment.config.draft.tasks.length} 个任务` : '读取中'}{row.last_error ? ` · ${row.last_error}` : ''}</small></div>
            <div className="hub-binding-tags">
              <Tag color={row.status === 'ready' ? 'green' : 'orange'}>{bindingStatusLabel[row.status as 'pending' | 'ready'] || row.status}</Tag>
              {row.is_default ? <Tag color="blue">默认路由</Tag> : null}
              {row.status === 'ready' ? <span className="hub-binding-state">{state.state}</span> : null}
            </div>
            <div className="hub-binding-actions">
              {row.status === 'ready' && row.is_default ? <Button size="small" onClick={() => onOpenProject(row.project_id, state.view)}>{state.action}</Button> : null}
              {row.status === 'ready' && !row.is_default ? <Button size="small" onClick={() => void setDefault(row)}>设为默认</Button> : null}
              <Button size="small" danger disabled={!editable} icon={<Unlink size={13} />} onClick={() => unbind(row)}>解绑</Button>
            </div>
          </div>;
        })}
        {unboundBindings.map(row => <div className="hub-binding-row is-unbound" key={row.binding_id}>
          <div><strong>{projectName(row.project_id)}</strong><small>{row.last_error ? `上次绑定失败：${row.last_error}` : '重新绑定同一版本可恢复原部署与历史'}{row.environment !== environment ? ` · 环境 ${row.environment}` : ''}</small></div>
          <Tag>已解绑</Tag>
        </div>)}
        {!bindings.length && <p className="hub-help">尚未绑定任何项目。发布后可将版本绑定到业务项目投入使用。</p>}
      </div>
    </div>
    <div className="hub-d-actions">
      <Button type="primary" block disabled={!editable} onClick={onWorkflow}>进入编排工作台 <ChevronRight size={15} /></Button>
      <div className="hub-d-row-btns">
        <Button disabled={!editable} icon={<GitBranch size={14} />} onClick={onPublish}>发布版本</Button>
        <Button onClick={() => setVersionsOpen(true)}>版本历史</Button>
        <Button disabled={!editable || !versions.data?.length} icon={<Link2 size={14} />} onClick={() => setBindOpen(true)}>绑定到项目</Button>
        <Button danger disabled={!editable || activeBindings.length > 0} icon={<Trash2 size={14} />} title={activeBindings.length ? '存在生效中的项目绑定，请先解绑全部项目' : '删除后不可恢复'} onClick={removeAgent}>删除智能体</Button>
        {activeBindings.filter(row => isAgentManagedProject(projects.find(item => item.id === row.project_id) || row.project_id)).map(row => <AgentRuntimePromptButton key={row.binding_id} platformId={row.project_id} platformName={projectName(row.project_id)} disabled={!editable} />)}
      </div>
    </div>
    <Drawer open={versionsOpen} title={`版本历史 · ${agent.name}`} width={560} onClose={() => setVersionsOpen(false)}>
      <div className="hub-release-list">
        {(versions.data || []).map(version => <article className="hub-release-card" key={version.id}>
          <div className="hub-release-marker"><GitBranch size={20} /></div>
          <div className="hub-release-content">
            <div className="hub-inline"><h3>V{version.sequence}</h3>{agent.latestVersionId === version.id ? <Tag color="green">最新版本</Tag> : null}</div>
            <p>{version.note || '（无发布说明）'}</p>
            <small>{version.createdAt ? dateLabel(version.createdAt) : ''} · {version.content.tasks.length} 个任务 · 摘要 {version.hash.slice(0, 12)}</small>
            {activeBindings.filter(row => row.desired_agent_version_id === version.id).map(row => {
              const state = bindingView(row);
              return <div className="hub-release-binding" key={row.binding_id}>
                <strong>{projectName(row.project_id)}</strong>
                {row.is_default ? <Tag color="blue">默认路由</Tag> : null}
                <span className="hub-binding-state">{state.state}</span>
                {row.status === 'ready' ? (row.is_default
                  ? <Button size="small" onClick={() => onOpenProject(row.project_id, state.view)}>{state.action}</Button>
                  : <Button size="small" onClick={() => void setDefault(row)}>设为默认</Button>) : null}
              </div>;
            })}
          </div>
        </article>)}
        {versions.isPending ? null : !versions.data?.length && <Empty description="尚未发布版本。完善草稿后点击「发布版本」。" />}
      </div>
    </Drawer>
    <Modal open={editOpen} title="编辑基础信息" okText="保存" cancelText="取消" onCancel={() => setEditOpen(false)} onOk={saveInfo} confirmLoading={savingInfo} okButtonProps={{ disabled: !editName.trim() }}>
      <div className="hub-form">
        <label>名称<Input aria-label="智能体名称" maxLength={128} value={editName} onChange={event => setEditName(event.target.value)} /></label>
        <label>描述<Input.TextArea aria-label="智能体描述" maxLength={500} rows={3} value={editDescription} onChange={event => setEditDescription(event.target.value)} placeholder="可选，说明智能体用途" /></label>
      </div>
    </Modal>
    <Modal open={bindOpen} title="绑定到项目" okText="确认绑定" cancelText="取消" onCancel={() => setBindOpen(false)} onOk={bind} confirmLoading={binding} okButtonProps={{ disabled: !bindProject || !bindVersion || preflight.data?.ready === false || !editable }}>
      <div className="hub-form">
        <Alert type="info" showIcon title="绑定会把所选版本部署到项目中；解除绑定不删除智能体与版本数据。" />
        <label>目标项目<Select aria-label="绑定目标项目" value={bindProject || undefined} options={bindProjectOptions} onChange={setBindProject} placeholder="选择要绑定的业务项目" /></label>
        <label>智能体版本<Select aria-label="绑定智能体版本" value={bindVersion || undefined} options={(versions.data || []).map(item => { const label = `V${item.sequence} · ${item.note || '无说明'}`; return { value: item.id, label, title: label }; })} onChange={setBindVersion} placeholder="选择要部署的版本" loading={versions.isPending} /></label>
        <label>调用别名<Input aria-label="绑定调用别名" maxLength={64} value={bindAlias} onChange={event => setBindAlias(event.target.value)} placeholder="default" /></label>
        <label>部署名称<Input aria-label="绑定部署名称" maxLength={128} value={bindName} onChange={event => setBindName(event.target.value)} placeholder={agent.name} /></label>
        {bindProject && bindVersion && (preflight.isPending ? <Alert type="info" title="正在检查模型与提示词配置…" /> : preflight.error ? <Alert type="error" title={(preflight.error as Error).message} action={<Button size="small" onClick={() => void preflight.refetch()}>重试</Button>} /> : preflight.data?.ready ? <Alert type="success" showIcon title="预检通过，所需能力均可用" /> : <Alert type="warning" showIcon title="存在未满足的前置条件" description={<>
          <ul className="workflow-issues">{(preflight.data?.missing || []).map((item, index) => <li key={index}>{item}</li>)}</ul>
          <p className="hub-help">请确认智能体引用的模型或能力资产是否存在、是否已停用，以及智能体配置是否完整（默认模型与规则提示词为必填项）。</p>
        </>} action={<div className="hub-actions">
          <Button size="small" onClick={() => void preflight.refetch()}>重新预检</Button>
        </div>} />)}
      </div>
    </Modal>
  </>;
}
