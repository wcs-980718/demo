import axios from 'axios';
import { getMidplatApiBaseURL } from '@/api/apiBaseURL';
export type FusionIdentity = { principal: string; projects: string[]; writable: boolean; admin: boolean };
export type FusionSession = { identity: FusionIdentity; localAuthentication: boolean; intranetManagement?: boolean; csrf: string | null };
export type FusionStatus = { runtimeEnvironment?: string; enabled: boolean; localAuthentication: boolean; intranetManagement?: boolean; executionEnabled: boolean };
export type FusionProject = { id: string; name: string; agentMode?: string | null };
export type FusionBinding = { project_id: string; environment: string; deployment_id: string; definition_id: string; name: string; status: 'pending' | 'ready'; operation_id: string };
export type FusionNodeKind = 'llm' | 'condition' | 'parallel' | 'merge';
export type FusionCondition = { source: 'input' | 'previous'; field?: string; operator: 'contains' | 'equals' | 'not_equals' | 'gt' | 'lt' | 'is_empty' | 'not_empty'; value?: string };
export type FusionNode = { id: string; name: string; instructions: string; modelRevisionId?: string; responseFormat?: 'text' | 'json_object'; kind?: FusionNodeKind; condition?: FusionCondition };
export type FusionWorkflow = { version: 1; positions: { id: string; x: number; y: number }[]; edges: { source: string; target: string; sourceHandle?: 'true' | 'false' }[] };
export type FusionTask = { key: string; name: string; instructions: string; modelRevisionId?: string; nodes: FusionNode[]; assetRevisionIds?: string[]; responseFormat?: 'text' | 'json_object'; workflow?: FusionWorkflow };
export type FusionDraft = { defaultModelRevisionId: string; temperature: number; role: { kind: 'inline'; body: string } | { kind: 'template'; revisionId: string }; tasks: FusionTask[] };
export type FusionDeployment = { id: string; projectId: string; environment: string; definitionId: string; name: string; revision: number; draft: FusionDraft; publishedReleaseId: string | null; activeReleaseId: string | null; pendingJobId: string | null; activationRevision: number };
export type FusionRun = { origin?: 'agenthub' | 'business'; id: string; release_id: string; session_id: string; task_key: string; principal: string; input: string; output: string; status: 'queued' | 'running' | 'cancelling' | 'succeeded' | 'failed' | 'cancelled'; error: string | null; created_at: string; started_at: string | null; finished_at: string | null; last_event: number };
export type FusionRunEvent = { sequence: number; type: string; data: { text?: string; nodeId?: string; name?: string; detail?: string; output?: string; usage?: { prompt_tokens?: number; completion_tokens?: number }; [key: string]: unknown }; createdAt: string };
export type FusionAsset = { id: string; name: string; kind: 'skill' | 'tool' | 'knowledge' | 'data'; enabled: boolean; revision: number; revisionId: string; hash: string; content: { body?: string; description?: string; toolName?: string; documents?: { title: string; text: string }[]; url?: string; method?: 'GET' | 'POST'; parameters?: Record<string, unknown>; readOnly?: boolean } };
export type CatalogRevision = { id: string; resourceId: string; hash: string; createdAt: string; content: { name: string; kind?: string; model?: string; body?: string; slot?: string; version?: string } };
export type FusionAgentSummary = { id: string; name: string; description?: string; status: string; draftRevision: number; latestVersionId: string | null; latestVersionSequence?: number | null; sourceProjectId: string | null; portable: boolean; updatedAt?: string };
export type FusionAgentDraft = FusionAgentSummary & { draft: FusionDraft; expectedRevision: number };
export type FusionAgentVersion = { id: string; agentId: string; sequence: number; hash: string; note: string; createdBy?: string; createdAt?: string; content: FusionDraft };
export type AgentBinding = { binding_id: string; project_id: string; environment: string; deployment_id: string; agent_id: string; desired_agent_version_id: string | null; alias: string; status: 'pending' | 'ready' | 'unbound'; generation: number; operation_id: string | null; last_error: string | null; is_default: boolean };
export type FusionBindingRow = { project_id: string; environment: string; deployment_id: string; binding_id: string; agent_id: string; desired_agent_version_id: string | null; alias: string; status: 'pending' | 'ready' | 'unbound' | string; last_error: string | null; is_default: boolean };
export type FusionCatalog = { models: CatalogRevision[]; availableModels: CatalogRevision[]; prompts: CatalogRevision[] };
export type FusionCompiledNode = { id: string; name: string; kind?: FusionNodeKind; modelRevisionId?: string; systemInstructions?: string[]; condition?: FusionCondition };
export type FusionCompiledTask = { key: string; name: string; modelRevisionId: string; systemInstructions: string[]; nodes: FusionCompiledNode[]; workflow?: FusionWorkflow };
export type FusionRelease = { id: string; deploymentId: string; sequence: number; note: string; restoredFrom: string | null; hash: string; createdAt: string; snapshot: { draft: FusionDraft; effectiveRole: string; compilerVersion: string; models: Record<string, CatalogRevision>; tasks: FusionCompiledTask[] } };
export type Publication = { id: string; deploymentId: string; status: 'queued' | 'validating' | 'ready' | 'failed'; error: string | null; releaseId: string | null };
export class FusionApiError extends Error { constructor(message: string, public status: number) { super(message); } }
const client = axios.create({ baseURL: `${getMidplatApiBaseURL()}/fusion`, withCredentials: true, timeout: 50000 });
let sessionPromise: Promise<FusionSession> | undefined;
let sessionValue: FusionSession | undefined;
let csrf: string | null = null;
function forgetSession(expected?: FusionSession) {
  // A late 401 from an older request must not discard a session renewed by another request.
  if (expected && sessionValue !== expected) return;
  sessionPromise = undefined; sessionValue = undefined; csrf = null;
}
async function token() {
  const provider = (window as Window & { __MIDPLAT_GET_ACCESS_TOKEN__?: () => Promise<string> }).__MIDPLAT_GET_ACCESS_TOKEN__;
  return provider ? await provider() : null;
}
async function raw<T>(method: string, url: string, data?: unknown): Promise<T> {
  try {
    const accessToken = await token();
    const response = await client.request<T>({ method, url, data, headers: { ...(csrf ? { 'X-Fusion-CSRF': csrf } : {}), ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) } });
    return response.data;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const detail = error.response?.data?.detail || error.response?.data?.message || '融合管理服务暂不可用，请重试。';
      const description = detail === 'Draft changed; reload before saving or publishing' ? '草稿已被其他操作更新，请刷新后重新保存或发布。' : detail;
      throw new FusionApiError(description, error.response?.status || 0);
    }
    throw error;
  }
}
async function session(): Promise<FusionSession> {
  if (!sessionPromise) {
    const pending = raw<FusionSession>('GET', '/session').then(value => {
      if (sessionPromise === pending) { csrf = value.csrf; sessionValue = value; }
      return value;
    }).catch(error => { if (sessionPromise === pending) forgetSession(); throw error; });
    sessionPromise = pending;
  }
  return sessionPromise;
}
async function sendWithSession<T>(method: string, path: string, data?: unknown): Promise<T> {
  const activeSession = await session();
  try { return await raw<T>(method, path, data); }
  catch (error) {
    if (error instanceof FusionApiError && error.status === 401) forgetSession(activeSession);
    throw error;
  }
}
async function request<T>(method: string, path: string, data?: unknown): Promise<T> {
  try { return await sendWithSession<T>(method, path, data); }
  catch (error) {
    if ((method === 'GET' || method === 'HEAD') && error instanceof FusionApiError && error.status === 401) {
      return sendWithSession<T>(method, path, data);
    }
    throw error;
  }
}
const projectPath = (project: string, env: string, path: string) => `/projects/${encodeURIComponent(project)}/${path}?environment=${encodeURIComponent(env)}`;
export const fusionApi = {
  status: () => raw<FusionStatus>('GET', '/status'), session,
  reconnect: () => { forgetSession(); return session(); },
  projects: () => request<FusionProject[]>('GET', '/projects'),
  bindings: (e?: string) => request<FusionBindingRow[]>('GET', `/bindings${e ? `?environment=${encodeURIComponent(e)}` : ''}`),
  binding: (p: string, e: string) => request<FusionBinding | null>('GET', projectPath(p, e, 'binding')),
  definitions: (p: string, e: string) => request<{ id: string; name: string }[]>('GET', projectPath(p, e, 'definitions')),
  createDefinition: (p: string, e: string, data: { name: string; taskKey: string; taskName: string; idempotencyKey: string }) => request<{ id: string; name: string }>('POST', projectPath(p, e, 'definitions'), data),
  bind: (p: string, e: string, data: { definitionId: string; name: string; idempotencyKey: string }) => request<FusionBinding>('POST', projectPath(p, e, 'binding'), data),
  catalog: (p: string, e: string) => request<FusionCatalog>('GET', projectPath(p, e, 'catalog')),
  // 管理员独立目录：不按项目授权过滤，未绑定任何项目也能创建、编辑独立智能体并选择资源。
  agentCatalog: () => request<FusionCatalog>('GET', '/catalog'),
  config: (p: string, e: string) => request<FusionDeployment>('GET', projectPath(p, e, 'effective-config')),
  save: (p: string, e: string, expectedRevision: number, draft: FusionDraft) => request<FusionDeployment>('PUT', projectPath(p, e, 'draft'), { expectedRevision, draft }),
  generate: (p: string, e: string, expectedRevision: number, draft: FusionDraft) => request<FusionDeployment>('PUT', projectPath(p, e, 'draft/generate'), { expectedRevision, draft }),
  releases: (p: string, e: string) => request<FusionRelease[]>('GET', projectPath(p, e, 'releases')),
  publish: (p: string, e: string, body: { expectedRevision: number; note: string; idempotencyKey: string; restoreReleaseId?: string }) => request<Publication>('POST', projectPath(p, e, 'publications'), body),
  publication: (p: string, e: string, id: string) => request<Publication>('GET', projectPath(p, e, `publications/${encodeURIComponent(id)}`)),
  audit: (p: string, e: string) => request<{ principal: string; action: string; target: string; created_at: string }[]>('GET', projectPath(p, e, 'audit')),
  activate: (p: string, e: string, releaseId: string, expectedActivationRevision: number) => request<FusionDeployment>('POST', projectPath(p, e, 'activations'), { releaseId, expectedActivationRevision }),
  runs: (p: string, e: string) => request<FusionRun[]>('GET', projectPath(p, e, 'runs')),
  run: (p: string, e: string, id: string) => request<FusionRun>('GET', projectPath(p, e, `runs/${encodeURIComponent(id)}`)),
  startRun: (p: string, e: string, body: { input: string; taskKey: string; idempotencyKey: string; sessionId?: string }) => request<FusionRun>('POST', projectPath(p, e, 'runs'), body),
  cancelRun: (p: string, e: string, id: string) => request<FusionRun>('POST', projectPath(p, e, `runs/${encodeURIComponent(id)}/cancel`)),
  events: (p: string, e: string, id: string, after = 0) => request<FusionRunEvent[]>('GET', `${projectPath(p, e, `runs/${encodeURIComponent(id)}/events`)}&after=${after}`),
  closeSession: (p: string, e: string, id: string) => request('POST', projectPath(p, e, `sessions/${encodeURIComponent(id)}/close`)),
  // ---- W4 独立智能体与多分配 ----
  agentList: () => request<FusionAgentSummary[]>('GET', '/agents'),
  createAgent: (data: { name: string; description?: string; taskKey?: string; taskName?: string; idempotencyKey: string }) => request<FusionAgentSummary>('POST', '/agents', data),
  updateAgent: (agentId: string, data: { name: string; description?: string }) => request<FusionAgentSummary>('PUT', `/agents/${encodeURIComponent(agentId)}`, data),
  agentDraft: (agentId: string) => request<FusionAgentDraft>('GET', `/agents/${encodeURIComponent(agentId)}/draft`),
  saveAgentDraft: (agentId: string, data: { expectedRevision: number; draft: FusionDraft }) => request<FusionAgentDraft>('PUT', `/agents/${encodeURIComponent(agentId)}/draft`, data),
  publishAgentVersion: (agentId: string, data: { expectedRevision: number; note?: string; idempotencyKey: string }) => request<FusionAgentDraft & { publication?: { id: string; versionId: string; status: string } }>('POST', `/agents/${encodeURIComponent(agentId)}/versions`, data),
  agentVersions: (agentId: string) => request<FusionAgentVersion[]>('GET', `/agents/${encodeURIComponent(agentId)}/versions`),
  deleteAgent: (agentId: string) => request<{ deleted: string; deployments: number }>('DELETE', `/agents/${encodeURIComponent(agentId)}`),
  agentBindings: (p: string, e: string) => request<AgentBinding[]>('GET', projectPath(p, e, 'agent-bindings')),
  preflightBinding: (p: string, e: string, data: { agentId: string; agentVersionId: string }) => request<{ ready: boolean; missing: string[]; tasks: string[]; agentVersionId?: string }>('POST', projectPath(p, e, 'agent-bindings/preflight'), data),
  createBinding: (p: string, e: string, data: { agentId: string; agentVersionId: string; alias?: string; name?: string; idempotencyKey: string }) => request<AgentBinding>('POST', projectPath(p, e, 'agent-bindings'), data),
  setDefaultBinding: (p: string, e: string, bindingId: string, routeKind = 'legacy-agent-runs') => request<AgentBinding[]>('POST', projectPath(p, e, `agent-bindings/${encodeURIComponent(bindingId)}/default`), { routeKind }),
  unbindAgent: (p: string, e: string, bindingId: string) => request<AgentBinding[]>('DELETE', projectPath(p, e, `agent-bindings/${encodeURIComponent(bindingId)}`)),
  assets: (p: string, e: string) => request<FusionAsset[]>('GET', projectPath(p, e, 'assets')),
  saveAsset: (p: string, e: string, body: { id: string; name: string; kind: FusionAsset['kind']; expectedRevision: number; content: FusionAsset['content'] }) => request<FusionAsset>('POST', projectPath(p, e, 'assets'), body),
  assetEnabled: (p: string, e: string, id: string, enabled: boolean) => request<FusionAsset[]>('PUT', projectPath(p, e, `assets/${encodeURIComponent(id)}/enabled`), { enabled }),
  assetRevisions: (p: string, e: string, id: string) => request<{ id: string; revision: number; hash: string; content: FusionAsset['content'] }[]>('GET', projectPath(p, e, `assets/${encodeURIComponent(id)}/revisions`)),
};
export const assetKindLabel = { skill: '技能', tool: '工具', knowledge: '知识库', data: '数据源' } as const;

export function revisionLabel(r: CatalogRevision) { return `${r.content.name} · ${r.content.version || r.content.model || '修订'} · ${r.hash.slice(0, 8)}`; }

export function sameConfiguration(a: unknown, b: unknown): boolean {
  const ordered = (v: any): any => Array.isArray(v) ? v.map(ordered) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(k => [k, ordered(v[k])])) : v;
  return JSON.stringify(ordered(a)) === JSON.stringify(ordered(b));
}

let modePromise: Promise<FusionStatus> | undefined;
export async function fusionManagementHeaders(): Promise<Record<string, string>> {
  if (!modePromise) modePromise = fusionApi.status().catch(error => { modePromise = undefined; throw error; });
  if (!(await modePromise).enabled) return {};
  await session(); const bearer = await token();
  return { ...(csrf ? { 'X-Fusion-CSRF': csrf } : {}), ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) };
}
