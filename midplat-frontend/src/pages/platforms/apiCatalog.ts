export type PlatformApi = {
  id: string;
  name: string;
  method: string;
  path: string;
  note: string;
  external: boolean;
};

export type ProjectConfigTab = {
  key: 'models' | 'apis' | 'runtime' | 'access' | 'exceptions';
  label: string;
  icon: string;
};

export type AgentPromptSpec = {
  task: 'report_generation' | 'fishbone_analysis';
  skillKey: string;
  skillLabel: string;
};

const SEEDED_EXTERNAL_AGENT_IDS = new Set(['plat-root-cause', 'plat-fishbone']);

/** 外部运行时项目。带回 agentMode 时只认 external；字段为空时仍认已登记的根因报告和鱼骨图。 */
export function isAgentManagedProject(platform: { id: string; agentMode?: string | null } | string) {
  if (typeof platform === 'string') return SEEDED_EXTERNAL_AGENT_IDS.has(platform);
  if (platform.agentMode) return platform.agentMode === 'external';
  return SEEDED_EXTERNAL_AGENT_IDS.has(platform.id);
}

/** 套用下拉：智能体服务启用后只展示已授权项，并保留当前已选以免历史数据消失。 */
export function grantedSelectOptions<T extends { value: string }>(
  options: T[],
  grantedIds: Iterable<string> | null | undefined,
  currentId?: string | null,
): T[] {
  if (grantedIds == null) return options;
  const granted = new Set(grantedIds);
  if (currentId) granted.add(currentId);
  return options.filter((item) => granted.has(item.value));
}

export function agentPromptSpec(platformId: string): AgentPromptSpec | null {
  if (platformId === 'plat-root-cause') {
    return {
      task: 'report_generation',
      skillKey: 'skill-root-cause-report-generation',
      skillLabel: '根因报告生成技能',
    };
  }
  if (platformId === 'plat-fishbone') {
    return {
      task: 'fishbone_analysis',
      skillKey: 'skill-root-cause-fishbone-analysis',
      skillLabel: '鱼骨图分析技能',
    };
  }
  return null;
}

export function projectConfigTabs(platform: { id: string; consume: boolean }): ProjectConfigTab[] {
  const tabs: ProjectConfigTab[] = [
    { key: 'models', label: '基本信息', icon: 'Settings2' },
    { key: 'apis', label: '对外接口设置', icon: 'Waypoints' },
  ];
  if (platform.id === 'plat-kb') {
    tabs.push({ key: 'runtime', label: '运行参数', icon: 'Gauge' });
  }
  tabs.push({ key: 'access', label: '接入凭证', icon: 'KeyRound' });
  tabs.push({ key: 'exceptions', label: '异常历史', icon: 'History' });
  return tabs;
}

export function openApiPath(platformId: string, apiId: string, sourcePath = '') {
  const extra = [...sourcePath.matchAll(/\{[^/}]+\}/g)].map((match) => `/${match[0]}`).join('');
  return `/api/open/${platformId}/${apiId}${extra}`;
}

export const MIDPLAT_PUBLIC_ORIGIN = 'http://portal.example.com:31010';

export function midplatPublicOrigin() {
  if (typeof window === 'undefined') {
    return MIDPLAT_PUBLIC_ORIGIN;
  }
  const hostname = window.location.hostname;
  if (hostname === 'localhost' || hostname === '127.0.0.1') {
    return MIDPLAT_PUBLIC_ORIGIN;
  }
  return `${window.location.protocol}//${hostname}:31010`;
}

export function openApiUrl(platformId: string, apiId: string, sourcePath = '') {
  return `${midplatPublicOrigin()}${openApiPath(platformId, apiId, sourcePath)}`;
}

export function openApiCurl(method: string, url: string, body?: string, token?: string | null) {
  const verb = method.toUpperCase();
  const credential = token?.trim() ? token.trim() : 'sk-mid-********';
  const lines = [`curl -X ${verb} "${url}"`, `  -H "Authorization: Bearer ${credential}"`];
  if (verb !== 'GET') {
    lines.push('  -H "Content-Type: application/json"');
    if (body) {
      lines.push(`  -d ${JSON.stringify(body)}`);
    }
  }
  return lines.map((line, index) => (index === lines.length - 1 ? line : `${line} \\`)).join('\n');
}

function pathnameOf(url: string) {
  const pathname = new URL(url).pathname || '/';
  try {
    return decodeURIComponent(pathname);
  } catch {
    return pathname;
  }
}

export function previewSourcePath(value: string) {
  const trimmed = (value ?? '').trim() || '/';
  if (/^https?:\/\//i.test(trimmed)) {
    try {
      return pathnameOf(trimmed);
    } catch {
      return trimmed;
    }
  }
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
}

export function normalizeSourcePath(value: string) {
  const path = previewSourcePath(value);
  if (!path.startsWith('/')) {
    throw new Error('来源 URL 必须是路径或完整地址');
  }
  return path;
}

export function apiIdFromOpenUrl(url: string, platformId: string) {
  if (!url.trim() || !platformId) {
    return undefined;
  }
  try {
    const pathname = /^https?:\/\//i.test(url.trim())
      ? pathnameOf(url.trim())
      : url.trim().split('?')[0];
    const path = pathname.startsWith('/') ? pathname : `/${pathname}`;
    const prefix = `/api/open/${platformId}/`;
    if (!path.startsWith(prefix)) {
      return undefined;
    }
    const apiId = path.slice(prefix.length).split('/').filter(Boolean)[0];
    return apiId || undefined;
  } catch {
    return undefined;
  }
}

export function newApiId() {
  const rand = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID().replace(/-/g, '').slice(0, 12)
    : Math.random().toString(36).slice(2, 14);
  return `api-${rand}`;
}

export function sourceApiUrl(entryUrl: string | null | undefined, sourcePath: string) {
  if (!entryUrl) {
    return sourcePath;
  }
  try {
    return `${new URL(entryUrl).origin}${sourcePath.startsWith('/') ? sourcePath : `/${sourcePath}`}`;
  } catch {
    return `${entryUrl.replace(/\/+$/, '')}${sourcePath.startsWith('/') ? sourcePath : `/${sourcePath}`}`;
  }
}

export function summarizeProjectApis(apis: Array<{ external: boolean }>) {
  const external = apis.filter((api) => api.external).length;
  return {
    total: apis.length,
    external,
    internal: apis.length - external,
  };
}

export function sortProjectApis<T extends { external: boolean }>(apis: T[]) {
  return apis
    .map((api, index) => ({ api, index }))
    .sort((left, right) => Number(right.api.external) - Number(left.api.external) || left.index - right.index)
    .map((item) => item.api);
}

function item(id: string, name: string, method: string, path: string, note: string, external: boolean): PlatformApi {
  return { id, name, method, path, note, external };
}

export function defaultApis(platform: { id: string; consume: boolean }): PlatformApi[] {
  if (platform.consume) {
    return [
      item('rt-chat', '中台对话', 'POST', '/api/runtime/chat', '新项目调用', true),
      item('rt-embed', '中台向量', 'POST', '/api/runtime/embed', '新项目调用', true),
      item('rt-rerank', '中台重排', 'POST', '/api/runtime/rerank', '新项目调用', true),
    ];
  }
  if (platform.id === 'plat-kb') {
    return [
      item('kb-settings-get', '运行时设置读取', 'GET', '/api/settings', '中台拉配置', true),
      item('kb-settings-put', '运行时设置写入', 'PUT', '/api/settings', '中台热更新', true),
      item('kb-list', '知识库列表', 'GET', '/api/knowledge-bases', '项目内管理', false),
      item('kb-create', '创建知识库', 'POST', '/api/knowledge-bases', '项目内管理', false),
      item('kb-get', '知识库详情', 'GET', '/api/knowledge-bases/{id}', '项目内管理', false),
      item('kb-update', '更新知识库', 'PUT', '/api/knowledge-bases/{id}', '项目内管理', false),
      item('kb-delete', '删除知识库', 'DELETE', '/api/knowledge-bases/{id}', '项目内管理', false),
      item('kb-docs', '文档列表', 'GET', '/api/knowledge-bases/{kbId}/documents', '项目内管理', false),
      item('kb-upload', '上传文档', 'POST', '/api/knowledge-bases/{kbId}/documents/upload', '项目内管理', false),
      item('kb-process', '处理文档', 'POST', '/api/documents/{id}/process', '项目内管理', false),
      item('kb-doc-del', '删除文档', 'DELETE', '/api/documents/{id}', '项目内管理', false),
      item('kb-search', '检索', 'POST', '/api/search', '可单独给其他系统用', true),
      item('kb-rag', 'RAG 问答', 'POST', '/api/rag/ask', '可单独给其他系统用', true),
      item('kb-rag-stream', 'RAG 流式问答', 'POST', '/api/rag/ask/stream', '可单独给其他系统用', true),
      item('kb-history', '问答历史', 'GET', '/api/rag/history', '项目内管理', false),
      item('kb-logs', '操作日志', 'GET', '/api/operation-logs', '项目内管理', false),
    ];
  }
  if (platform.id === 'plat-an') {
    return [
      item('an-settings-get', '预标注设置读取', 'GET', '/api/settings', '中台拉配置', true),
      item('an-settings-put', '预标注设置写入', 'PUT', '/api/settings', '中台热更新', true),
      item('an-health', '健康检查', 'GET', '/api/health', '探活', false),
      item('an-projects', '项目列表', 'GET', '/api/projects', '项目内管理', false),
      item('an-create-project', '创建项目', 'POST', '/api/projects', '项目内管理', false),
      item('an-tasks', '任务列表', 'GET', '/api/tasks', '项目内管理', false),
      item('an-create-task', '创建任务', 'POST', '/api/tasks', '项目内管理', false),
      item('an-submit', '提交任务', 'POST', '/api/tasks/{taskId}/submit', '项目内管理', false),
      item('an-prelabel', '预标注批次', 'POST', '/api/prelabel-batches', '可单独给其他系统用', true),
      item('an-review-q', '复核队列', 'GET', '/api/reviews/queue', '项目内管理', false),
      item('an-review', '提交复核', 'POST', '/api/reviews', '项目内管理', false),
      item('an-publish', '发布任务', 'POST', '/api/publish-jobs', '项目内管理', false),
    ];
  }
  if (platform.id === 'plat-qa') {
    return [
      item('qa-settings-get', '运行时设置读取', 'GET', '/api/settings', '中台拉配置', true),
      item('qa-settings-put', '运行时设置写入', 'PUT', '/api/settings', '中台热更新', true),
      item('qa-providers', 'Provider 列表', 'GET', '/api/llm-providers', '中台读配置', true),
      item('qa-activate', '激活 Provider', 'PATCH', '/api/llm-providers/{providerId}/activate', '中台热更新', true),
      item('qa-chat', '智能问数', 'POST', '/api/agent/chat/smart', '可单独给其他系统用', true),
      item('qa-stream', '智能问数流式', 'POST', '/api/agent/chat/smart/stream', '可单独给其他系统用', true),
      item('qa-drill', '下钻解析', 'POST', '/api/agent/drilldown/resolve', '可单独给其他系统用', true),
      item('qa-conv', '会话列表', 'GET', '/api/conversations', '项目内管理', false),
      item('qa-sql', '生成 SQL', 'POST', '/api/tools/generate_sql', '项目内管理', false),
    ];
  }
  if (platform.id === 'plat-root-cause') {
    return [
      item('rc-list', '根因项目列表', 'GET', '/api/closed-loop/improve/rootCause/list', '项目内管理', false),
      item('rc-query', '根因项目详情', 'POST', '/api/closed-loop/improve/rootCause/project/{projectId}/query', '项目内管理', false),
      item('rc-context', '智能体分析上下文', 'POST', '/api/closed-loop/improve/rootCause/agentContext', '生成报告前读取证据', false),
      item('rc-report', '根因报告生成', 'POST', '/chat/completions', 'root_cause 智能体 report_generation', true),
      item('rc-resources', '智能体资源读取', 'GET', '/agent/resources', '读取 SOUL 与技能资源', false),
    ];
  }
  if (platform.id === 'plat-fishbone') {
    return [
      item('fb-context', '智能体分析上下文', 'POST', '/api/closed-loop/improve/rootCause/agentContext', '生成鱼骨图前读取证据', false),
      item('fb-analyze', '鱼骨图智能分析', 'POST', '/chat/completions', 'root_cause 智能体 fishbone_analysis', true),
      item('fb-resources', '智能体资源读取', 'GET', '/agent/resources', '读取 SOUL 与技能资源', false),
      item('fb-list', '鱼骨图明细读取', 'GET', '/api/closed-loop/improve/rootCause/{rootCauseId}/item', '项目内管理', false),
      item('fb-save', '鱼骨图明细保存', 'POST', '/api/closed-loop/improve/rootCause/{rootCauseId}/item', '项目内管理', false),
    ];
  }
  return [];
}

export function hotUpdateStatus(platform: { id: string; consume: boolean; entryUrl?: string | null }): { text: string; cls: string } {
  if (platform.consume) {
    return { text: '调用中台', cls: 'ok' };
  }
  if (supportsHotUpdate(platform)) {
    return { text: '可热更新', cls: 'ok' };
  }
  if (defaultApis(platform).length > 0) {
    return { text: '已接入', cls: 'ok' };
  }
  if (platform.entryUrl) {
    return { text: '已上线', cls: 'ok' };
  }
  return { text: '待补接口', cls: 'warn' };
}

export function supportsHotUpdate(platform: { id: string; consume: boolean }) {
  return !platform.consume && defaultApis(platform).some((item) => item.note === '中台热更新');
}
