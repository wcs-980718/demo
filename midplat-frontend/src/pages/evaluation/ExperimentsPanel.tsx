import { useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Button, Select, Spin } from 'antd';
import { AlertCircle, Minus, RefreshCw, Scale, TrendingDown, TrendingUp, UserCheck } from 'lucide-react';
import { evaluationApi } from './evaluationApi';
import { createComparisonSelectionHandlers } from './comparisonSelection';
import {
  caseSeverityLabel,
  comparisonCategoryMeta,
  evaluationMutationErrorMessage,
  formatCostDelta,
  formatLatencyDelta,
  formatQualityDelta,
  runStatusLabel,
} from './evaluationPresentation';
import type {
  EvaluationCaseComparison,
  EvaluationComparisonCategory,
  EvaluationExperimentComparison,
  EvaluationRunSummary,
} from './evaluationTypes';

const TERMINAL = new Set<EvaluationRunSummary['status']>(['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED']);
const CATEGORY_ORDER: EvaluationComparisonCategory[] = ['REGRESSED', 'ERROR', 'REVIEW_REQUIRED', 'IMPROVED', 'UNCHANGED'];

function runOption(run: EvaluationRunSummary) {
  const label = `${run.id.slice(0, 8)}… · ${runStatusLabel(run.status)} · ${run.passedCount}/${run.totalCount} 通过`;
  return { value: run.id, label, title: label };
}

export function ExperimentsPanel() {
  const [baselineRunId, setBaselineRunId] = useState<string>();
  const [candidateRunId, setCandidateRunId] = useState<string>();
  const runsQuery = useQuery({
    queryKey: ['evaluation', 'experiments', 'terminal-runs'],
    queryFn: () => evaluationApi.listRuns({ page: 0, size: 100 }),
    select: (page) => page.content.filter((run) => TERMINAL.has(run.status)),
  });
  const comparisonMutation = useMutation({
    mutationFn: ({ baseline, candidate }: { baseline: string; candidate: string }) => evaluationApi.compareRuns(baseline, candidate),
  });
  const selectionHandlers = createComparisonSelectionHandlers(
    () => comparisonMutation.reset(),
    setBaselineRunId,
    setCandidateRunId,
  );
  const options = (runsQuery.data ?? []).map(runOption);
  const canCompare = Boolean(baselineRunId && candidateRunId && baselineRunId !== candidateRunId);

  return (
    <section className="evaluation-section" aria-labelledby="evaluation-experiments-heading">
      <div className="evaluation-section-head">
        <div>
          <p className="evaluation-kicker">BASELINE VS CANDIDATE</p>
          <h3 id="evaluation-experiments-heading">实验对比</h3>
          <p>比较同一冻结快照的基线与候选运行，聚焦质量、成本和延迟变化。</p>
        </div>
      </div>

      {runsQuery.isLoading ? (
        <div className="evaluation-loading" aria-label="正在加载可比较任务"><Spin size="large" /></div>
      ) : runsQuery.isError ? (
        <div className="evaluation-error" role="alert">
          <div><strong>可比较任务加载失败</strong><p>请重试加载最近的终态任务。</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => void runsQuery.refetch()}>重新加载</Button>
        </div>
      ) : (
        <div className="evaluation-compare-picker">
          <label><span>基线运行</span><Select showSearch optionFilterProp="label" value={baselineRunId} options={options.filter((option) => option.value !== candidateRunId)} onChange={selectionHandlers.selectBaseline} placeholder="选择终态基线" /></label>
          <label><span>候选运行</span><Select showSearch optionFilterProp="label" value={candidateRunId} options={options.filter((option) => option.value !== baselineRunId)} onChange={selectionHandlers.selectCandidate} placeholder="选择终态候选" /></label>
          <Button
            type="primary"
            icon={<Scale size={16} />}
            disabled={!canCompare}
            loading={comparisonMutation.isPending}
            onClick={() => {
              if (baselineRunId && candidateRunId) comparisonMutation.mutate({ baseline: baselineRunId, candidate: candidateRunId });
            }}
          >开始比较</Button>
          <p>后端会校验两次运行使用同一冻结快照；如不一致，本页会保留选择并提示重新选取。</p>
        </div>
      )}

      {comparisonMutation.isError && (
        <div className="evaluation-error" role="alert">
          <div><strong>实验比较失败</strong><p>{evaluationMutationErrorMessage(comparisonMutation.error)}，请确认两个任务都已终止且来自同一冻结快照。</p></div>
          <Button onClick={() => comparisonMutation.reset()}>修改选择</Button>
        </div>
      )}
      {comparisonMutation.data && <ComparisonResult comparison={comparisonMutation.data} />}
      {!comparisonMutation.data && !comparisonMutation.isError && !runsQuery.isLoading && (
        <div className="evaluation-empty evaluation-empty-inline"><Scale size={24} /><div><strong>选择两次终态运行</strong><p>对比结果会按退化、错误、待复核、改进和无变化分组。</p></div></div>
      )}
    </section>
  );
}

function ComparisonResult({ comparison }: { comparison: EvaluationExperimentComparison }) {
  const groups = useMemo(() => CATEGORY_ORDER.map((category) => ({
    category,
    cases: comparison.cases.filter((item) => item.category === category),
  })).filter((group) => group.cases.length), [comparison]);
  return (
    <div className="evaluation-comparison-result" aria-live="polite">
      <div className="evaluation-run-stat-grid evaluation-delta-grid">
        <DeltaStat label="质量变化" value={formatQualityDelta(comparison.qualityDelta)} tone={comparison.qualityDelta == null ? 'warning' : comparison.qualityDelta >= 0 ? 'positive' : 'negative'} />
        <DeltaStat label="成本变化" value={formatCostDelta(comparison.costDelta)} tone={comparison.costDelta <= 0 ? 'positive' : 'negative'} />
        <DeltaStat label="平均延迟" value={formatLatencyDelta(comparison.averageLatencyDeltaMs)} tone={comparison.averageLatencyDeltaMs <= 0 ? 'positive' : 'negative'} />
        <DeltaStat label="P95 延迟" value={formatLatencyDelta(comparison.p95LatencyDeltaMs)} tone={comparison.p95LatencyDeltaMs <= 0 ? 'positive' : 'negative'} />
      </div>
      <div className="evaluation-comparison-ids"><code>{comparison.baselineRunId}</code><span>→</span><code>{comparison.candidateRunId}</code></div>
      {groups.map((group) => <CaseGroup key={group.category} category={group.category} cases={group.cases} />)}
    </div>
  );
}

function DeltaStat({ label, value, tone }: { label: string; value: string; tone: 'positive' | 'negative' | 'warning' }) {
  return <article className={`evaluation-tone-${tone}`}><span>{label}</span><strong>{value}</strong><small>候选减基线</small></article>;
}

function CaseGroup({ category, cases }: { category: EvaluationComparisonCategory; cases: EvaluationCaseComparison[] }) {
  const meta = comparisonCategoryMeta(category);
  const Icon = category === 'IMPROVED' ? TrendingUp : category === 'REGRESSED' ? TrendingDown : category === 'ERROR' ? AlertCircle : category === 'REVIEW_REQUIRED' ? UserCheck : Minus;
  return (
    <section className="evaluation-comparison-group" aria-labelledby={`comparison-${category.toLowerCase()}`}>
      <header><Icon size={17} /><strong id={`comparison-${category.toLowerCase()}`}>{meta.label}</strong><span>{cases.length} 题</span></header>
      {cases.map((item) => (
        <details key={item.resultId} className={`evaluation-comparison-case evaluation-tone-${meta.tone}`}>
          <summary><span><strong>{item.name}</strong><small>{caseSeverityLabel(item.severity)} · {item.caseId}</small></span><em>{meta.label}</em></summary>
          <div><h5>候选输出摘要</h5><pre>{item.candidateOutputSummary || '暂无可展示摘要'}</pre></div>
        </details>
      ))}
    </section>
  );
}
