// Local browser fixture only: every API is intercepted; no business environment is contacted.
exports.installWorkflowFixture = async (page, { writable = true } = {}) => {
  const deployments = new Map(); const mutations = []; const agents = new Map();
  const create = (project, name) => ({ id: `dep-${project}`, projectId: project, environment: 'development', definitionId: 'definition', name, revision: 1,
    draft: { defaultModelRevisionId: 'model-1', temperature: 0.4, role: { kind: 'inline', body: '你是一名企业业务助手，依据已有信息作答，标明不确定内容。' }, tasks: [{ key: 'main', name: '业务分析', instructions: '提炼要点并给出建议。', assetRevisionIds: ['skill-1'], nodes: [] }] }, publishedReleaseId: null, activeReleaseId: null, pendingJobId: null, activationRevision: 0 });
  deployments.set('project-1', create('project-1', '业务分析智能体 · 验收示例'));
  const createAgent = (name, taskName) => {
    const id = `agent-${agents.size + 1}`;
    const agent = { summary: { id, name, description: '', status: 'draft', draftRevision: 1, latestVersionId: null, latestVersionSequence: null, sourceProjectId: null, portable: true },
      draft: { defaultModelRevisionId: 'model-1', temperature: 0.4, role: { kind: 'inline', body: '' }, tasks: [{ key: 'main', name: taskName || '主任务', instructions: '', nodes: [] }] },
      versions: [], expectedRevision: 1 };
    agents.set(id, agent); return agent;
  };
  const fixture = { deployments, mutations, agents, releases: new Map(), conflict: false, writable };
  const projects = [{ id: 'project-1', name: '编排验收示例' }, { id: 'project-2', name: '新建验收示例' }];
  const models = [{ id: 'model-1', resourceId: 'model', hash: 'a1234567890123', content: { name: '通用对话模型', model: 'local-test-model', kind: 'llm' } }];
  // 暴露模型目录，便于用例注入超长名称验证省略号收尾
  fixture.models = models; fixture.projects = projects; fixture.createAgent = createAgent;
  const prompts = [{ id: 'prompt-1', resourceId: 'prompt', hash: 'p12345678', content: { name: '企业助手模板', body: '你是企业业务助手。' } }];
  fixture.prompts = prompts;
  const asset = { id: 'skill', name: '结构化分析指南', kind: 'skill', enabled: true, revision: 1, revisionId: 'skill-1', hash: 'skillhash', content: { body: '先列事实，再给结论。' } };
  await page.route('**/api/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname;
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    if (path.endsWith('/agent-prompt')) return respond({ code: 200, data: { soulContent: '共享角色规则', skillContent: '当前任务规则', writable: true, skillTitle: '任务技能', skillKey: 'fishbone', task: 'fishbone' } });
    if (!path.includes('/fusion/')) return respond({ code: 200, data: [] });
    if (path.endsWith('/status')) return respond({ enabled: true, executionEnabled: true, runtimeEnvironment: 'development', localAuthentication: true });
    if (path.endsWith('/session')) return respond({ identity: { principal: '本地验收', projects: projects.map(project => project.id), writable: fixture.writable, admin: true }, csrf: 'test-csrf', localAuthentication: true });
    if (path.endsWith('/projects')) return respond(projects);
    if (path.endsWith('/bindings')) return respond([...deployments.values()].map(value => ({ project_id: value.projectId, environment: value.environment, deployment_id: value.id, binding_id: `bnd-${value.id}`, agent_id: '', desired_agent_version_id: null, alias: 'default', status: 'ready', is_default: true })));
    if (path.endsWith('/agents') && request.method() === 'GET') return respond([...agents.values()].map(agent => agent.summary));
    const agentDraftPath = path.match(/\/agents\/([^/]+)\/draft$/); const agentVersionsPath = path.match(/\/agents\/([^/]+)\/versions$/);
    const project = path.match(/\/projects\/([^/]+)\//)?.[1]; const deployment = deployments.get(project);
    if (request.method() !== 'GET') {
      if (!fixture.writable) return respond({ detail: 'Read-only' }, 403);
      if (request.headers()['x-fusion-csrf'] !== 'test-csrf') return respond({ detail: 'Missing CSRF' }, 403);
      const body = request.postDataJSON(); mutations.push({ path, body: structuredClone(body) });
      if (path.endsWith('/agents')) return respond(createAgent(body.name, body.taskName).summary);
      if (agentDraftPath) { const agent = agents.get(agentDraftPath[1]); agent.draft = structuredClone(body.draft); agent.expectedRevision += 1; agent.summary.draftRevision = agent.expectedRevision; return respond({ ...agent.summary, draft: agent.draft, expectedRevision: agent.expectedRevision }); }
      if (agentVersionsPath) { const agent = agents.get(agentVersionsPath[1]); const version = { id: `av-${agent.versions.length + 1}`, agentId: agent.summary.id, sequence: agent.versions.length + 1, hash: `agent-hash-${agent.versions.length + 1}`, note: body.note || '', content: structuredClone(agent.draft) }; agent.versions.push(version); agent.summary.latestVersionId = version.id; agent.summary.latestVersionSequence = version.sequence; return respond({ ...agent.summary, draft: agent.draft, expectedRevision: agent.expectedRevision }); }
      if (path.endsWith('/definitions')) return respond({ id: 'definition-2', name: body.name });
      if (path.endsWith('/binding')) { deployments.set(project, create(project, body.name)); return respond({ project_id: project, deployment_id: `dep-${project}`, status: 'ready' }); }
      if (path.endsWith('/draft') || path.endsWith('/draft/generate')) {
        if (fixture.conflict || body.expectedRevision !== deployment.revision) return respond({ detail: 'Draft changed; reload before saving or publishing' }, 409);
        deployment.draft = structuredClone(body.draft); deployment.revision++; return respond(deployment);
      }
      return respond({ detail: 'Fixture does not publish or run business requests' }, 422);
    }
    if (agentDraftPath) { const agent = agents.get(agentDraftPath[1]); return agent ? respond({ ...agent.summary, draft: agent.draft, expectedRevision: agent.expectedRevision }) : respond({ detail: 'agent not found' }, 404); }
    if (agentVersionsPath) return respond(agents.get(agentVersionsPath[1])?.versions || []);
    if (path.endsWith('/effective-config')) return respond(deployment);
    if (path.endsWith('/releases')) return respond(fixture.releases.get(project) || []);
    if (path.endsWith('/catalog')) return respond({ models, availableModels: models, prompts });
    if (path.endsWith('/assets')) return respond([asset]);
    if (path.endsWith('/definitions')) return respond([{ id: 'definition', name: '基础智能体定义' }]);
    return respond([]);
  });
  return fixture;
};
