import axios from 'axios';
import { fusionManagementHeaders } from '@/pages/agent-hub/fusionApi';
import { getMidplatApiBaseURL } from '@/api/apiBaseURL';

type ApiResponse<T> = {
  success: boolean;
  data: T;
  message: string | null;
};

export type RouteItem = {
  name: string;
  path: string;
  title: string;
};

export type MenuItem = {
  id: string;
  parentId: string | null;
  name: string;
  routeName: string;
  path: string;
  filePath: string | null;
  icon: string;
  platformId: string | null;
  platformIds: string[] | null;
  sortOrder: number;
  visible: boolean;
  locked: boolean;
  children: MenuItem[];
};

export type ModelCapabilities = { toolCalls: boolean | null; jsonObject: boolean | null };

export type AiModel = {
  capabilities?: ModelCapabilities;
  id: string;
  name: string;
  kind: 'llm' | 'embedding' | 'rerank';
  model: string;
  baseUrl: string;
  pingStatus: string;
  inputPricePerMillion: number | null;
  outputPricePerMillion: number | null;
};

export type PromptItem = {
  id: string;
  name: string;
  slot: string;
  version: string;
  body: string;
};

export type PlatformItem = {
  id: string;
  name: string;
  entryUrl: string | null;
  icon: string;
  consume: boolean;
  token: string | null;
  llmModelId: string | null;
  embeddingModelId: string | null;
  rerankModelId: string | null;
  promptId: string | null;
  agentMode?: string | null;
};

export type ModelPayload = {
  capabilities?: ModelCapabilities;
  name: string;
  kind: AiModel['kind'];
  model: string;
  baseUrl: string;
  apiKey?: string;
  inputPricePerMillion?: number | null;
  outputPricePerMillion?: number | null;
};

export type PromptPayload = {
  name: string;
  slot: string;
  version?: string;
  body: string;
};

export type PlatformPayload = {
  name: string;
  entryUrl?: string;
  icon?: string;
  llmModelId?: string;
  embeddingModelId?: string;
  rerankModelId?: string;
  promptId?: string;
};

export type PlatformDelivery = { desiredRevision: number | null; appliedRevision: number | null; status: string; lastError: string | null; desiredHash: string | null; confirmedHash: string | null };

export type AgentConfigSync = {
  applicable: boolean;
  mode: 'unsupported' | 'unbound' | 'unpublished' | 'pull' | 'push' | 'unknown';
  message: string;
  source?: 'active' | 'published';
  releaseId?: string;
  releaseSequence?: number;
  taskKey?: string;
  model?: string;
  promptChars?: number;
  inSync?: boolean;
  delivery?: PlatformDelivery;
};

export type RuntimeSettings = {
  searchMode: string;
  searchTopK: number;
  ragTopK: number;
  maxContextLength: number;
  statsListingLimit: number;
  dataQueryDefaultLimit: number;
  dataQueryMaxLimit: number;
  rewriteEnabled: boolean;
  rewriteMaxQueries: number;
  rerankEnabled: boolean;
  searchEnableRerank: boolean;
  ragEnableRerank: boolean;
  rerankEvidenceThreshold: number;
};

export type ExceptionReportFilters = {
  platformId?: string;
  method?: string;
  status?: number | null;
  keyword?: string;
  occurredFrom?: string;
  occurredTo?: string;
};

export type AgentPromptBundle = {
  platformId: string;
  agent: string;
  task: 'report_generation' | 'fishbone_analysis';
  soulContent: string;
  skillKey: string;
  skillTitle: string;
  skillContent: string;
  writable: boolean;
};

export type AgentRuntimeView = {
  platformId: string;
  status: string;
  agent: string;
  model: string;
  skills: number;
  task: 'report_generation' | 'fishbone_analysis';
  skillKey: string;
  skillTitle: string;
  runtimeUrl: string;
};

const client = axios.create();

client.interceptors.request.use(async (config) => {
  const fusionHeaders = await fusionManagementHeaders();
  for (const [name, value] of Object.entries(fusionHeaders)) config.headers.set(name, value);
  config.withCredentials = true;
  config.baseURL = getMidplatApiBaseURL();
  return config;
});

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const detail = error.response?.data?.detail ?? error.response?.data?.message ?? error.message;
    return Promise.reject(new Error(typeof detail === 'string' ? detail : '请求失败'));
  },
);

async function unwrap<T>(promise: Promise<{ data: ApiResponse<T> }>) {
  const response = await promise;
  return response.data.data;
}

export const midplatApi = {
  demoEnvironment: () => unwrap(client.get<ApiResponse<{ demo: boolean }>>('/demo/environment')),
  health: () => unwrap(client.get<ApiResponse<{ status: string }>>('/health')),
  listRoutes: () => unwrap(client.get<ApiResponse<RouteItem[]>>('/routes')),
  listMenus: () => unwrap(client.get<ApiResponse<MenuItem[]>>('/menus')),
  createMenu: (payload: {
    parentId?: string;
    name: string;
    routeName: string;
    icon?: string;
    platformId?: string;
    platformIds?: string[];
    visible?: boolean;
    path?: string;
    filePath?: string;
  }) => unwrap(client.post<ApiResponse<MenuItem>>('/menus', payload)),
  updateMenu: (id: string, payload: {
    name: string;
    routeName: string;
    icon?: string;
    platformId?: string;
    platformIds?: string[];
    visible: boolean;
    path?: string;
    filePath?: string;
  }) => unwrap(client.patch<ApiResponse<MenuItem>>(`/menus/${id}`, payload)),
  moveMenu: (id: string, direction: number) => unwrap(client.post<ApiResponse<null>>(`/menus/${id}/move`, { direction })),
  deleteMenu: (id: string) => unwrap(client.delete<ApiResponse<null>>(`/menus/${id}`)),
  listModels: () => unwrap(client.get<ApiResponse<AiModel[]>>('/models')),
  createModel: (payload: ModelPayload) => unwrap(client.post<ApiResponse<AiModel>>('/models', payload)),
  updateModel: (id: string, payload: ModelPayload) => unwrap(client.patch<ApiResponse<AiModel>>(`/models/${id}`, payload)),
  pingModel: (id: string) => unwrap(client.post<ApiResponse<AiModel>>(`/models/${id}/ping`)),
  deleteModel: (id: string) => unwrap(client.delete<ApiResponse<null>>(`/models/${id}`)),
  listPrompts: () => unwrap(client.get<ApiResponse<PromptItem[]>>('/prompts')),
  createPrompt: (payload: PromptPayload) => unwrap(client.post<ApiResponse<PromptItem>>('/prompts', payload)),
  updatePrompt: (id: string, payload: PromptPayload) => unwrap(client.patch<ApiResponse<PromptItem>>(`/prompts/${id}`, payload)),
  deletePrompt: (id: string) => unwrap(client.delete<ApiResponse<null>>(`/prompts/${id}`)),
  listPlatforms: () => unwrap(client.get<ApiResponse<PlatformItem[]>>('/platforms')),
  getPlatform: (id: string) => unwrap(client.get<ApiResponse<PlatformItem>>(`/platforms/${id}`)),
  createPlatform: (payload: PlatformPayload) => unwrap(client.post<ApiResponse<PlatformItem>>('/platforms', payload)),
  createPlatformInMenu: (menuId: string, payload: PlatformPayload) =>
    unwrap(client.post<ApiResponse<PlatformItem>>(`/menus/${menuId}/platforms`, payload)),
  deletePlatformInMenu: (menuId: string, platformId: string) =>
    unwrap(client.delete<ApiResponse<null>>(`/menus/${menuId}/platforms/${platformId}`)),
  updatePlatform: (id: string, payload: PlatformPayload) => unwrap(client.patch<ApiResponse<PlatformItem>>(`/platforms/${id}`, payload)),
  deletePlatform: (id: string) => unwrap(client.delete<ApiResponse<null>>(`/platforms/${id}`)),
  getDelivery: (id: string) => unwrap(client.get<ApiResponse<PlatformDelivery>>(`/platforms/${id}/delivery`)),
  getAgentConfigSync: (id: string) => unwrap(client.get<ApiResponse<AgentConfigSync>>(`/platforms/${id}/agent-config`)),
  syncAgentConfig: (id: string, force = false) => unwrap(client.post<ApiResponse<AgentConfigSync>>(`/platforms/${id}/agent-config/sync`, null, { params: { force } })),
  getRuntime: (id: string) => unwrap(client.get<ApiResponse<RuntimeSettings>>(`/platforms/${id}/runtime`)),
  updateRuntime: (id: string, payload: Partial<RuntimeSettings>) =>
    unwrap(client.patch<ApiResponse<RuntimeSettings>>(`/platforms/${id}/runtime`, payload)),
  getAgentPrompt: (id: string) =>
    unwrap(client.get<ApiResponse<AgentPromptBundle>>(`/platforms/${id}/agent-prompt`)),
  getAgentRuntime: (id: string) =>
    unwrap(client.get<ApiResponse<AgentRuntimeView>>(`/platforms/${id}/agent-runtime`)),
  updateAgentPrompt: (id: string, payload: Pick<AgentPromptBundle, 'soulContent' | 'skillContent'>) =>
    unwrap(client.put<ApiResponse<AgentPromptBundle>>(`/platforms/${id}/agent-prompt`, payload)),
  listApis: (id: string) => unwrap(client.get<ApiResponse<PlatformApiItem[]>>(`/platforms/${id}/apis`)),
  toggleApi: (id: string, apiId: string, external: boolean) =>
    unwrap(client.patch<ApiResponse<PlatformApiItem[]>>(`/platforms/${id}/apis/${apiId}`, { external })),
  createApi: (id: string, payload: PlatformApiPayload) =>
    unwrap(client.post<ApiResponse<PlatformApiItem[]>>(`/platforms/${id}/apis`, payload)),
  updateApi: (id: string, apiId: string, payload: PlatformApiPayload) =>
    unwrap(client.put<ApiResponse<PlatformApiItem[]>>(`/platforms/${id}/apis/${apiId}`, payload)),
  deleteApi: (id: string, apiId: string) =>
    unwrap(client.delete<ApiResponse<PlatformApiItem[]>>(`/platforms/${id}/apis/${apiId}`)),
  listExceptionReports: (params: ExceptionReportFilters & { page?: number; size?: number }) =>
    unwrap(client.get<ApiResponse<ExceptionReportPage>>('/exception-reports', { params })),
  getExceptionReport: (id: string) =>
    unwrap(client.get<ApiResponse<ExceptionReportDetail>>(`/exception-reports/${id}`)),
  listCapabilityAssets: (params: { kind?: string; status?: string; search?: string; includeCatalog?: boolean; page?: number; pageSize?: number }) =>
    unwrap(client.get<ApiResponse<CapabilityAssetPage>>('/capabilities/assets', { params })),
  createCapabilityAsset: (payload: CapabilityAssetPayload) =>
    unwrap(client.post<ApiResponse<CapabilityAssetDetail>>('/capabilities/assets', payload)),
  getCapabilityAsset: (id: string) =>
    unwrap(client.get<ApiResponse<CapabilityAssetDetail>>(`/capabilities/assets/${id}`)),
  updateCapabilityAsset: (id: string, payload: CapabilityAssetUpdatePayload) =>
    unwrap(client.put<ApiResponse<CapabilityAssetDetail & { sync: CapabilityAssetSync[] }>>(`/capabilities/assets/${id}`, payload)),
  setCapabilityAssetStatus: (id: string, status: string) =>
    unwrap(client.patch<ApiResponse<CapabilityAssetDetail & { sync: CapabilityAssetSync[] }>>(`/capabilities/assets/${id}/status`, { status })),
  capabilityAssetRevisions: (id: string) =>
    unwrap(client.get<ApiResponse<CapabilityAssetRevision[]>>(`/capabilities/assets/${id}/revisions`)),
  retryCapabilityAssetSync: (id: string) =>
    unwrap(client.post<ApiResponse<CapabilityAssetDetail & { sync: CapabilityAssetSync[] }>>(`/capabilities/assets/${id}/sync`)),
  listAssetGrants: (projectId: string, environment: string) =>
    unwrap(client.get<ApiResponse<CapabilityAssetGrantPage>>(`/capabilities/projects/${projectId}/asset-grants`, { params: { environment } })),
  replaceAssetGrants: (projectId: string, environment: string, payload: { policyRevision: number; grants: CapabilityAssetGrantInput[] }) =>
    unwrap(client.put<ApiResponse<CapabilityAssetGrantPage>>(`/capabilities/projects/${projectId}/asset-grants`, payload, { params: { environment } })),
};

export type CapabilityAssetKind = 'skill' | 'tool' | 'knowledge' | 'data';
export type CapabilityAssetStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED';

export type CapabilityAssetContent = {
  body?: string;
  toolName?: string;
  description?: string;
  url?: string;
  method?: 'GET' | 'POST';
  readOnly?: boolean;
  parameters?: Record<string, unknown>;
  documents?: { title: string; text: string }[];
  [key: string]: unknown;
};

export type CapabilityAssetSummary = {
  id: string;
  kind: CapabilityAssetKind | 'model' | 'prompt';
  name: string;
  description: string | null;
  status: string;
  revision: number | null;
  hash: string | null;
  currentRevisionId: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  grantCount: number;
  sync: { synced: number; failed: number; pending: number };
};

export type CapabilityAssetPage = {
  content: CapabilityAssetSummary[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
};

export type CapabilityAssetRevision = {
  id: string;
  revision: number;
  hash: string;
  note: string | null;
  createdBy: string | null;
  createdAt: string;
};

export type CapabilityAssetGrant = {
  id: string;
  assetId: string;
  assetKind: string;
  assetName: string;
  projectId: string;
  projectName: string;
  environment: string;
  operation: string;
  versionRule: 'pinned' | 'current';
  revisionId: string | null;
  enabled: boolean;
  policyRevision: number;
  updatedAt: string;
};

export type CapabilityAssetGrantInput = {
  assetId: string;
  operation: string;
  versionRule: 'pinned' | 'current';
  revisionId?: string | null;
  enabled: boolean;
};

export type CapabilityAssetGrantPage = {
  project: string;
  environment: string;
  policyRevision: number;
  grants: CapabilityAssetGrant[];
  sync?: CapabilityAssetSync[];
};

export type CapabilityAssetSync = {
  projectId: string;
  environment: string;
  fusionAssetId: string | null;
  fusionRevisionId: string | null;
  status: 'PENDING' | 'SYNCED' | 'FAILED' | 'DISABLED' | string;
  error: string | null;
};

export type CapabilityAssetDetail = {
  id: string;
  kind: CapabilityAssetKind;
  name: string;
  description: string | null;
  status: CapabilityAssetStatus;
  revision: number;
  currentRevisionId: string;
  hash: string;
  content: CapabilityAssetContent | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  revisions: CapabilityAssetRevision[];
  grants: CapabilityAssetGrant[];
  sync: CapabilityAssetSync[];
  grantCount: number;
};

export type CapabilityAssetPayload = {
  kind: CapabilityAssetKind;
  name: string;
  description?: string;
  status?: 'DRAFT' | 'ACTIVE';
  content: CapabilityAssetContent;
};

export type CapabilityAssetUpdatePayload = {
  expectedRevision: number;
  content: CapabilityAssetContent;
  note?: string;
  name?: string;
  description?: string | null;
};

export type ExceptionReportSummary = {
  id: string;
  platformId: string;
  url: string;
  method: string;
  status: number | null;
  errorMessage: string;
  category: string;
  categoryLabel: string;
  categoryDetail: string;
  categoryFault: boolean;
  occurredAt: string;
  createdAt: string;
};

export type ExceptionReportDetail = ExceptionReportSummary & {
  queryParams: string | null;
  requestBody: string | null;
};

export type ExceptionReportPage = {
  content: ExceptionReportSummary[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
};

export type PlatformApiItem = {
  id: string;
  name: string;
  method: string;
  path: string;
  note: string;
  external: boolean;
};

export type PlatformApiPayload = {
  id?: string;
  name: string;
  method: string;
  path: string;
  note?: string;
  external: boolean;
};
