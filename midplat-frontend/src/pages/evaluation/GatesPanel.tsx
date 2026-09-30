import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Popconfirm, Select, Spin, message } from 'antd';
import { Edit3, Plus, RefreshCw, Shield, ShieldAlert, ShieldCheck, ShieldX } from 'lucide-react';
import { evaluationApi } from './evaluationApi';
import {
  evaluationMutationErrorMessage,
  gateConclusionMeta,
  runStatusLabel,
} from './evaluationPresentation';
import type {
  EvaluationGateConclusion,
  EvaluationGatePolicy,
  EvaluationGatePolicyPayload,
  EvaluationRunSummary,
} from './evaluationTypes';
import { GatePolicyModal } from './GatePolicyModal';

const TERMINAL = new Set<EvaluationRunSummary['status']>(['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED']);

function runOption(run: EvaluationRunSummary) {
  return { value: run.id, label: `${run.id.slice(0, 8)}… · ${runStatusLabel(run.status)} · ${run.passedCount}/${run.totalCount} 通过` };
}

function conclusionIcon(conclusion: EvaluationGateConclusion) {
  return conclusion === 'PASS' ? ShieldCheck : conclusion === 'FAIL' ? ShieldX : ShieldAlert;
}

export function GatesPanel() {
  const queryClient = useQueryClient();
  const [policyModalOpen, setPolicyModalOpen] = useState(false);
  const [editingPolicy, setEditingPolicy] = useState<EvaluationGatePolicy | null>(null);
  const [baselineRunId, setBaselineRunId] = useState<string>();
  const [candidateRunId, setCandidateRunId] = useState<string>();
  const [policyId, setPolicyId] = useState<string>();

  const policiesQuery = useQuery({ queryKey: ['evaluation', 'gates', 'policies'], queryFn: evaluationApi.listGatePolicies });
  const runsQuery = useQuery({
    queryKey: ['evaluation', 'gates', 'terminal-runs'],
    queryFn: () => evaluationApi.listRuns({ page: 0, size: 100 }),
    select: (page) => page.content.filter((run) => TERMINAL.has(run.status)),
  });
  const decisionsQuery = useQuery({
    queryKey: ['evaluation', 'gates', 'decisions', candidateRunId],
    queryFn: () => evaluationApi.listGateDecisions(candidateRunId!),
    enabled: Boolean(candidateRunId),
  });
  const savePolicyMutation = useMutation({
    mutationFn: ({ policy, payload }: { policy: EvaluationGatePolicy | null; payload: EvaluationGatePolicyPayload }) =>
      policy
        ? evaluationApi.updateGatePolicy(policy.id, { ...payload, expectedVersion: policy.version })
        : evaluationApi.createGatePolicy(payload),
    onSuccess: async (saved) => {
      message.success(editingPolicy ? '门禁策略已更新' : '门禁策略已创建');
      setPolicyModalOpen(false);
      setEditingPolicy(null);
      setPolicyId(saved.id);
      await queryClient.invalidateQueries({ queryKey: ['evaluation', 'gates', 'policies'] });
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });
  const decisionMutation = useMutation({
    mutationFn: ({ baseline, candidate, policy }: { baseline: string; candidate: string; policy: string }) =>
      evaluationApi.createGateDecision(candidate, { baselineRunId: baseline, policyId: policy }),
    onSuccess: async (decision) => {
      message.success(gateConclusionMeta(decision.conclusion).label);
      await queryClient.invalidateQueries({ queryKey: ['evaluation', 'gates', 'decisions', decision.runId] });
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });

  const options = (runsQuery.data ?? []).map(runOption);
  const enabledPolicies = (policiesQuery.data ?? []).filter((policy) => policy.enabled);
  const canDecide = Boolean(baselineRunId && candidateRunId && baselineRunId !== candidateRunId && policyId);
  const loading = policiesQuery.isLoading || runsQuery.isLoading;
  const failed = policiesQuery.isError || runsQuery.isError;

  const openCreate = () => { setEditingPolicy(null); setPolicyModalOpen(true); };
  const openEdit = (policy: EvaluationGatePolicy) => { setEditingPolicy(policy); setPolicyModalOpen(true); };

  return (
    <section className="evaluation-section" aria-labelledby="evaluation-gates-heading">
      <div className="evaluation-section-head">
        <div>
          <p className="evaluation-kicker">RELEASE GOVERNANCE</p>
          <h3 id="evaluation-gates-heading">发布门禁</h3>
          <p>用版本化策略判断候选运行，每次决策和证据都只追加、不覆盖。</p>
        </div>
        <Button type="primary" icon={<Plus size={16} />} onClick={openCreate}>创建门禁策略</Button>
      </div>

      {loading ? (
        <div className="evaluation-loading" aria-label="正在加载门禁数据"><Spin size="large" /></div>
      ) : failed ? (
        <div className="evaluation-error" role="alert">
          <div><strong>门禁数据加载失败</strong><p>可重新加载策略和终态运行，不会产生新决策。</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => { void policiesQuery.refetch(); void runsQuery.refetch(); }}>重新加载</Button>
        </div>
      ) : (
        <>
          <section className="evaluation-policy-section" aria-labelledby="evaluation-policy-heading">
            <div className="evaluation-content-section-head"><div><h4 id="evaluation-policy-heading">门禁策略</h4><p>修改策略会增加版本，历史决策仍保留当时使用的策略版本。</p></div></div>
            {policiesQuery.data?.length ? (
              <div className="evaluation-policy-grid">
                {policiesQuery.data.map((policy) => <PolicyCard key={policy.id} policy={policy} onEdit={() => openEdit(policy)} />)}
              </div>
            ) : (
              <div className="evaluation-empty evaluation-empty-inline"><Shield size={24} /><div><strong>还没有门禁策略</strong><p>先定义通过率、成本和延迟阈值，再生成发布决策。</p></div><Button onClick={openCreate}>创建策略</Button></div>
            )}
          </section>

          <section className="evaluation-decision-section" aria-labelledby="evaluation-decision-heading">
            <div className="evaluation-content-section-head"><div><h4 id="evaluation-decision-heading">生成不可变决策</h4><p>选择同一冻结快照的基线和候选运行，并使用已启用策略。</p></div></div>
            <div className="evaluation-gate-picker">
              <label><span>基线运行</span><Select showSearch optionFilterProp="label" options={options.filter((item) => item.value !== candidateRunId)} value={baselineRunId} onChange={setBaselineRunId} placeholder="选择基线" /></label>
              <label><span>候选运行</span><Select showSearch optionFilterProp="label" options={options.filter((item) => item.value !== baselineRunId)} value={candidateRunId} onChange={setCandidateRunId} placeholder="选择候选" /></label>
              <label><span>启用策略</span><Select showSearch optionFilterProp="label" options={enabledPolicies.map((policy) => { const label = `${policy.name} · V${policy.version}`; return { value: policy.id, label, title: label }; })} value={policyId} onChange={setPolicyId} placeholder="选择策略" /></label>
              <Popconfirm
                title="确认生成门禁决策？"
                description="决策与证据将不可变地追加，后续修改策略不会改写它。"
                okText="确认生成"
                cancelText="取消"
                disabled={!canDecide}
                onConfirm={() => {
                  if (baselineRunId && candidateRunId && policyId) decisionMutation.mutate({ baseline: baselineRunId, candidate: candidateRunId, policy: policyId });
                }}
                okButtonProps={{ loading: decisionMutation.isPending }}
              >
                <Button type="primary" icon={<ShieldCheck size={16} />} disabled={!canDecide} loading={decisionMutation.isPending}>生成门禁决策</Button>
              </Popconfirm>
            </div>
          </section>

          {candidateRunId && <DecisionHistory loading={decisionsQuery.isLoading} error={decisionsQuery.isError} decisions={decisionsQuery.data ?? []} onRetry={() => void decisionsQuery.refetch()} />}
        </>
      )}

      <GatePolicyModal
        open={policyModalOpen}
        policy={editingPolicy}
        pending={savePolicyMutation.isPending}
        onClose={() => { setPolicyModalOpen(false); setEditingPolicy(null); }}
        onSubmit={(payload) => savePolicyMutation.mutate({ policy: editingPolicy, payload })}
      />
    </section>
  );
}

function PolicyCard({ policy, onEdit }: { policy: EvaluationGatePolicy; onEdit: () => void }) {
  return (
    <article className="evaluation-policy-card">
      <div><span className={`evaluation-status ${policy.enabled ? 'result-passed' : 'result-cancelled'}`}>{policy.enabled ? '已启用' : '已停用'}</span><strong>{policy.name}</strong><small>V{policy.version} · {policy.platformId || '全平台'} · {policy.category || '全分类'}</small></div>
      <dl>
        <div><dt>最低通过率</dt><dd>{policy.minPassRate.toFixed(2)}%</dd></div>
        <div><dt>最大成本增长</dt><dd>{policy.maxCostGrowthPercent.toFixed(2)}%</dd></div>
        <div><dt>延迟阈值</dt><dd>{policy.maxAverageLatencyMs} / {policy.maxP95LatencyMs} ms</dd></div>
        <div><dt>关键案例必过</dt><dd>{policy.requireCriticalCasesPassed ? '是' : '否'}</dd></div>
      </dl>
      <Button icon={<Edit3 size={14} />} onClick={onEdit}>编辑策略</Button>
    </article>
  );
}

function DecisionHistory({ loading, error, decisions, onRetry }: {
  loading: boolean;
  error: boolean;
  decisions: Awaited<ReturnType<typeof evaluationApi.listGateDecisions>>;
  onRetry: () => void;
}) {
  if (loading) return <div className="evaluation-loading evaluation-loading-compact"><Spin /></div>;
  if (error) return <div className="evaluation-error" role="alert"><div><strong>决策记录加载失败</strong><p>可重试读取，不会重复生成决策。</p></div><Button onClick={onRetry}>重新加载</Button></div>;
  return (
    <section className="evaluation-decision-history" aria-labelledby="evaluation-decision-history-heading">
      <div className="evaluation-content-section-head"><div><h4 id="evaluation-decision-history-heading">候选运行决策记录</h4><p>以下证据为不可变历史，仅在用户展开时显示完整 JSON。</p></div></div>
      {decisions.length ? decisions.map((decision) => {
        const meta = gateConclusionMeta(decision.conclusion);
        const Icon = conclusionIcon(decision.conclusion);
        return (
          <details className={`evaluation-decision-item evaluation-tone-${meta.tone}`} key={decision.id}>
            <summary><Icon size={18} /><span><strong>{meta.label}</strong><small>策略 V{decision.policyVersion} · {new Date(decision.decidedAt).toLocaleString('zh-CN')}</small></span><em>查看证据</em></summary>
            <pre>{JSON.stringify(decision.evidence, null, 2)}</pre>
          </details>
        );
      }) : <div className="evaluation-empty evaluation-empty-compact"><strong>该候选运行还没有门禁决策</strong><p>选择基线和策略后生成第一条不可变记录。</p></div>}
    </section>
  );
}
