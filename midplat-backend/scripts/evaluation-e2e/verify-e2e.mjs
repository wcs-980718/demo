const baseUrl = String(process.env.MIDPLAT_E2E_BASE_URL ?? '').replace(/\/+$/, '');
const fakeBaseUrl = String(process.env.MIDPLAT_E2E_FAKE_BASE_URL ?? '').replace(/\/+$/, '');
const testToken = String(process.env.MIDPLAT_E2E_TEST_TOKEN ?? 'local-e2e-token');

if (!baseUrl || !fakeBaseUrl) throw new Error('缺少端到端服务地址');

const capturedResponses = [];

async function api(path, options = {}) {
  const response = await fetch(`${baseUrl}/api${path}`, {
    ...options,
    headers: { 'content-type': 'application/json', ...(options.headers ?? {}) },
  });
  const text = await response.text();
  capturedResponses.push(text);
  let payload;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    throw new Error(`${options.method ?? 'GET'} ${path} 返回非 JSON，状态 ${response.status}`);
  }
  if (!response.ok || payload?.success !== true) {
    throw new Error(`${options.method ?? 'GET'} ${path} 失败，状态 ${response.status}`);
  }
  return payload.data;
}

const body = (value) => ({ method: 'POST', body: JSON.stringify(value) });

async function waitForRun(runId) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const run = await api(`/evaluation/runs/${encodeURIComponent(runId)}`);
    if (['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(run.status)) return run;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`评测运行未在 30 秒内结束：${runId}`);
}

const suffix = Date.now().toString(36);
const model = await api('/models', body({
  name: `端到端假模型-${suffix}`,
  kind: 'llm',
  model: 'fake-evaluation-model',
  baseUrl: fakeBaseUrl,
  apiKey: testToken,
  inputPricePerMillion: 1,
  outputPricePerMillion: 2,
}));
const prompt = await api('/prompts', body({
  name: `端到端提示词-${suffix}`,
  slot: 'app',
  version: 'v1',
  body: '你是受控评测模型，只返回要求的短文本。',
}));
const platform = await api('/platforms', body({
  name: `端到端平台-${suffix}`,
  entryUrl: '',
  icon: 'FlaskConical',
  llmModelId: model.id,
  embeddingModelId: null,
  rerankModelId: null,
  promptId: prompt.id,
}));
const createdCase = await api('/evaluation/cases', body({
  platformId: platform.id,
  name: `端到端精确匹配-${suffix}`,
  category: 'E2E',
  severity: 'HIGH',
  inputText: '请只回答“评测通过”。',
  expected: { value: '评测通过' },
  evaluatorType: 'EXACT',
}));
const reviewedCase = await api(`/evaluation/cases/${createdCase.id}/review`, body({
  decision: 'APPROVE',
  expectedVersion: createdCase.version,
}));
if (reviewedCase.reviewStatus !== 'REVIEWED') throw new Error('案例未进入已复核状态');

const dataset = await api('/evaluation/datasets', body({
  name: `端到端评测集-${suffix}`,
  description: '完全虚构的本地端到端验收数据',
}));
const draft = dataset.versions[0];
const arranged = await api(`/evaluation/dataset-versions/${draft.id}/items`, {
  method: 'PUT',
  body: JSON.stringify({ expectedVersion: draft.version, caseIds: [createdCase.id] }),
});
const frozen = await api(`/evaluation/dataset-versions/${draft.id}/freeze`, body({
  expectedVersion: arranged.version,
}));
if (frozen.status !== 'FROZEN' || !frozen.snapshotHash) throw new Error('评测集没有成功冻结');

async function createCompletedRun() {
  const created = await api('/evaluation/runs', body({
    datasetVersionId: frozen.id,
    platformId: platform.id,
    modelId: model.id,
    promptId: prompt.id,
    temperature: 0,
    maxTokens: 64,
    timeoutMs: 10_000,
  }));
  const completed = await waitForRun(created.id);
  if (completed.status !== 'COMPLETED' || completed.passedCount !== 1) {
    throw new Error(`评测运行结果不符合预期：${completed.status}`);
  }
  return completed;
}

const baseline = await createCompletedRun();
const candidate = await createCompletedRun();
const comparison = await api(`/evaluation/experiments/compare?baselineRunId=${encodeURIComponent(baseline.id)}&candidateRunId=${encodeURIComponent(candidate.id)}`);
if (comparison.cases?.length !== 1 || comparison.cases[0].category !== 'UNCHANGED') {
  throw new Error('实验比较没有得到稳定的持平结果');
}

const policy = await api('/evaluation/gates', body({
  name: `端到端门禁-${suffix}`,
  platformId: platform.id,
  category: 'E2E',
  minPassRate: 100,
  maxCostGrowthPercent: 100,
  maxAverageLatencyMs: 120_000,
  maxP95LatencyMs: 120_000,
  requireCriticalCasesPassed: true,
  enabled: true,
}));
const decision = await api(`/evaluation/runs/${candidate.id}/gate-decision`, body({
  baselineRunId: baseline.id,
  policyId: policy.id,
}));
if (decision.conclusion !== 'PASS') throw new Error(`门禁结论不是 PASS：${decision.conclusion}`);

const overview = await api('/evaluation/overview');
if (overview.totalCases < 1 || overview.frozenVersions < 1 || overview.latestTerminalPassRate !== 100) {
  throw new Error('治理总览没有反映端到端数据');
}
if (capturedResponses.some((value) => value.includes(testToken))) throw new Error('测试令牌出现在 HTTP 响应中');

process.stdout.write(`${JSON.stringify({
  caseCount: overview.totalCases,
  frozenSnapshotHash: frozen.snapshotHash,
  baselineRunId: baseline.id,
  candidateRunId: candidate.id,
  comparisonCategory: comparison.cases[0].category,
  gateConclusion: decision.conclusion,
  latestTerminalPassRate: overview.latestTerminalPassRate,
}, null, 2)}\n`);
