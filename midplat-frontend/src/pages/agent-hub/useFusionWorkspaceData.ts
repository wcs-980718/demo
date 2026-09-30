import { useQueries, useQuery } from '@tanstack/react-query';
import { fusionApi, type FusionAgentSummary, type FusionBindingRow, type FusionDeployment, type FusionProject, type FusionRelease } from './fusionApi';

export type WorkspaceView = 'overview' | 'agents' | 'releases' | 'workflows' | 'assets' | 'runs' | 'use' | 'settings' | 'audit';
export type AuditEntry = { principal: string; action: string; target: string; created_at: string };
export type WorkspaceAgent = {
  project: FusionProject;
  id: string;
  status: string;
  config?: FusionDeployment;
  releases: FusionRelease[];
  audit: AuditEntry[];
};
export type AgentCardItem =
  | { kind: 'agent'; key: string; agent: FusionAgentSummary; bindings: FusionBindingRow[]; deployments: WorkspaceAgent[] }
  | { kind: 'legacy'; key: string; agent: WorkspaceAgent; binding: FusionBindingRow };
export const projectKey = (project: string, environment: string) => ['fusion-project', project, environment];
export const auditLabel = (action: string) => ({
  'business.run.created': '业务任务执行', 'deployment.created': '创建智能体',
  'draft.saved': '保存草稿', 'workflow.generated': '生成智能体配置',
  'publication.requested': '提交发布',
  'configuration.published': '发布完成',
  'publication.failed': '发布失败',
  'runtime.activated': '运行版本上线',
  'runtime.activation.failed': '上线失败',
  'run.created': '创建运行',
  'run.cancel.requested': '取消运行',
}[action] || action);
export const environmentLabel = (environment: string) => ({ development: '开发环境', staging: '预发布环境', production: '生产配置' }[environment] || environment);
export const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN');

export function useFusionWorkspaceData(environment: string, requestedProject: string | null, requestedAgent: string | null, view: WorkspaceView) {
  const projects = useQuery({ queryKey: ['fusion-projects'], queryFn: fusionApi.projects, retry: false });
  const session = useQuery({ queryKey: ['fusion-session'], queryFn: fusionApi.session, retry: false });
  // 拉取全部环境的分配：卡片与详情的“是否已绑定”口径需与 deleteAgent 一致（不按当前环境过滤）；
  // 运行态列表（agents/routed/configs）仍按当前环境过滤。
  const bindings = useQuery({ queryKey: ['fusion-bindings'], queryFn: () => fusionApi.bindings(), retry: false });
  const agentList = useQuery({ queryKey: ['fusion-agent-list'], queryFn: fusionApi.agentList, enabled: !!session.data?.identity.admin, retry: false });
  const ownedAll = (bindings.data || []).filter(binding => projects.data?.some(project => project.id === binding.project_id));
  const ownedBindings = ownedAll.filter(binding => binding.environment === environment);
  const activeBindings = ownedBindings.filter(binding => binding.status !== 'unbound');
  const ready = activeBindings.filter(binding => binding.status === 'ready');
  const routed = ready.filter((binding, index) => binding.is_default || (
    !ready.some(candidate => candidate.project_id === binding.project_id && candidate.is_default)
    && ready.findIndex(candidate => candidate.project_id === binding.project_id) === index
  ));
  const configs = useQueries({ queries: routed.map(binding => ({
    queryKey: [...projectKey(binding.project_id, environment), 'config'],
    queryFn: () => fusionApi.config(binding.project_id, environment), refetchInterval: 5000, retry: false,
  })) });
  const releases = useQueries({ queries: routed.map(binding => ({
    queryKey: [...projectKey(binding.project_id, environment), 'releases'],
    queryFn: () => fusionApi.releases(binding.project_id, environment), refetchInterval: 5000, retry: false,
  })) });
  const readAudit = view === 'overview' || view === 'audit' || view === 'agents';
  const audits = useQueries({ queries: routed.map(binding => ({
    queryKey: [...projectKey(binding.project_id, environment), 'audit'],
    queryFn: () => fusionApi.audit(binding.project_id, environment), enabled: readAudit, retry: false,
  })) });
  const agents: WorkspaceAgent[] = [...activeBindings]
    .sort((left, right) => Number(right.is_default) - Number(left.is_default))
    .map(binding => {
      const index = routed.findIndex(row => row.deployment_id === binding.deployment_id);
      const config = index >= 0 && configs[index]?.data?.id === binding.deployment_id ? configs[index].data : undefined;
      return {
        project: projects.data!.find(project => project.id === binding.project_id)!,
        id: binding.deployment_id,
        status: binding.status,
        config,
        releases: config ? releases[index]?.data || [] : [],
        audit: config ? audits[index]?.data || [] : [],
      };
    });
  const agentIds = new Set((agentList.data || []).map(agent => agent.id));
  const cards: AgentCardItem[] = [
    ...(agentList.data || []).map(agent => ({
      kind: 'agent' as const, key: agent.id, agent,
      bindings: ownedAll.filter(binding => binding.agent_id === agent.id),
      deployments: agents.filter(item => activeBindings.some(binding => binding.deployment_id === item.id && binding.agent_id === agent.id)),
    })),
    ...activeBindings.filter(binding => !agentIds.has(binding.agent_id)).map(binding => ({
      kind: 'legacy' as const, key: binding.deployment_id,
      agent: agents.find(item => item.id === binding.deployment_id)!, binding,
    })),
  ];
  const selectedAgent = requestedAgent ? (agentList.data || []).find(agent => agent.id === requestedAgent) : undefined;
  const matchedCard = cards.find(card => card.kind === 'agent' ? card.agent.id === requestedAgent : card.agent.project.id === requestedProject);
  const selectedCard = matchedCard || (!requestedAgent && !requestedProject ? cards[0] : undefined);
  const selectedProject = projects.data?.find(project => project.id === requestedProject)
    || agents.find(agent => agent.config)?.project || agents[0]?.project || projects.data?.[0];
  const selected = agents.find(item => item.project.id === selectedProject?.id && item.config)
    || agents.find(item => item.project.id === selectedProject?.id);
  // 管理员用独立目录（不按项目过滤），没有任何项目时也能创建、编辑独立智能体并选择资源；
  // 非管理员仍使用所属项目的目录。
  const isAdmin = !!session.data?.identity.admin;
  const catalog = useQuery({
    queryKey: isAdmin ? ['fusion-agent-catalog'] : [...projectKey(selectedProject?.id || '', environment), 'catalog'],
    queryFn: () => isAdmin ? fusionApi.agentCatalog() : fusionApi.catalog(selectedProject!.id, environment),
    enabled: isAdmin || !!selectedProject, retry: false,
  });
  const configurationError = configs.find(query => query.error)?.error || releases.find(query => query.error)?.error;
  const auditError = readAudit ? audits.find(query => query.error)?.error : null;
  return {
    projects, session, bindings, agentList, selectedProject, selected, agents, cards, selectedAgent, selectedCard, catalog,
    loading: projects.isPending || bindings.isPending || session.isPending,
    detailsLoading: configs.some(query => query.isPending) || releases.some(query => query.isPending),
    auditLoading: readAudit && audits.some(query => query.isPending),
    error: projects.error || session.error || bindings.error,
    detailError: configurationError || auditError || catalog.error,
  };
}
