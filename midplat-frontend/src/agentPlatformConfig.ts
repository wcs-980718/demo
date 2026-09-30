/** AgentHub 原生页面统一使用独立路由命名空间。 */
export const AGENT_PLATFORM_URL = '/agent-hub/overview';

export function agentPlatformAgentUrl(agentName: string) {
  return `/agent-hub/agents?agent=${encodeURIComponent(agentName)}`;
}

export function agentPlatformManagementRoute(agentName: string) {
  return agentPlatformAgentUrl(agentName);
}
