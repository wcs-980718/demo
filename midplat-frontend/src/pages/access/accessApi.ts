import axios from 'axios';
import { getMidplatApiBaseURL } from '@/api/apiBaseURL';
import { fusionManagementHeaders } from '../agent-hub/fusionApi';

/**
 * W5 客户与凭证管理 API。管理端点位于 /api/access（管理认证域），
 * 请求须携带融合管理会话的 CSRF 头（fusionManagementHeaders 负责）。
 */
const client = axios.create({ baseURL: `${getMidplatApiBaseURL()}/access`, withCredentials: true, timeout: 30000 });

async function request<T>(method: string, url: string, data?: unknown): Promise<T> {
  try {
    const headers = await fusionManagementHeaders();
    const response = await client.request<T>({ method, url, data, headers });
    return (response.data as { data?: T })?.data !== undefined ? (response.data as { data: T }).data : (response.data as unknown as T);
  } catch (error) {
    if (axios.isAxiosError(error)) {
      throw new Error(error.response?.data?.detail || error.response?.data?.message || '客户与凭证服务暂不可用，请重试。');
    }
    throw error;
  }
}

export type Consumer = { id: string; code: string; name: string; type: string; status: string };
export type AccessClient = { id: string; consumerId: string; projectId: string; environment: string; name: string; code: string; status: string; policyRevision: number };
export type Entitlement = { id: string; resourceKind: string; resourceId: string; action: string; constraints: string | null; status: string };
export type CredentialSummary = { id: string; keyId: string; label: string; status: string; expiresAt: string | null; lastUsedAt: string | null; policyRevision: number };
export type Grant = { id: string; entitlementId: string; constraints: string | null; status: string };
export type IssuedCredential = { credential: CredentialSummary; secretAvailable: boolean; secret?: string };

export const accessApi = {
  consumers: () => request<Consumer[]>('GET', '/consumers'),
  createConsumer: (data: { code: string; name: string; type: string }) => request<Consumer>('POST', '/consumers', data),
  clients: (projectId?: string) => request<AccessClient[]>('GET', `/clients${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`),
  createClient: (data: { consumerId: string; projectId: string; environment: string; name: string; code: string }) => request<AccessClient>('POST', '/clients', data),
  setClientStatus: (id: string, status: string) => request<AccessClient>('PUT', `/clients/${encodeURIComponent(id)}/status`, { status }),
  entitlements: (clientId: string) => request<Entitlement[]>('GET', `/clients/${encodeURIComponent(clientId)}/entitlements`),
  // 后端为增量语义：新条目创建（缺省 status=active），status:'revoked' 撤销；未列出的原样保留。
  // “收回”必须显式发送 revoked，而不是从列表中省略。
  setEntitlements: (clientId: string, commands: { resourceKind: string; resourceId: string; action: string; status?: 'revoked' }[]) =>
    request<Entitlement[]>('PUT', `/clients/${encodeURIComponent(clientId)}/entitlements`, { commands }),
  credentials: (clientId: string) => request<CredentialSummary[]>('GET', `/clients/${encodeURIComponent(clientId)}/credentials`),
  issueCredential: (clientId: string, data: { label: string; expiresAt?: string | null; idempotencyKey: string }) =>
    request<IssuedCredential>('POST', `/clients/${encodeURIComponent(clientId)}/credentials`, data),
  rotateCredential: (id: string, label: string) => request<IssuedCredential>('POST', `/credentials/${encodeURIComponent(id)}/rotations`, { label }),
  revokeCredential: (id: string) => request<CredentialSummary>('POST', `/credentials/${encodeURIComponent(id)}/revoke`, {}),
  grants: (credentialId: string) => request<Grant[]>('GET', `/credentials/${encodeURIComponent(credentialId)}/grants`),
  // 同上：勾选列表是“应生效集合”，取消勾选的既有授权要显式 revoked。
  setGrants: (credentialId: string, grants: { entitlementId: string; status?: 'revoked' }[]) =>
    request<Grant[]>('PUT', `/credentials/${encodeURIComponent(credentialId)}/grants`, { grants }),
};
