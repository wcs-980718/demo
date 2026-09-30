import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Drawer, Popconfirm, Spin, message } from 'antd';
import { Check, RefreshCw, RotateCcw, Square, X, XCircle } from 'lucide-react';
import { evaluationApi } from './evaluationApi';
import {
  evaluationMutationErrorMessage,
  formatLatency,
  formatLatencySummary,
  formatRunCost,
  formatRunProgress,
  formatTokenCount,
  resultStatusLabel,
  runPollInterval,
  runStatusLabel,
} from './evaluationPresentation';
import type { EvaluationResult, EvaluationRunSummary } from './evaluationTypes';

type Props = {
  runId: string | null;
  onClose: () => void;
  onRetryCreated: (run: EvaluationRunSummary) => void;
};

function isActive(status: EvaluationRunSummary['status']): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

function formatTime(value: string | null): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)) : '—';
}

export function RunDetailDrawer({ runId, onClose, onRetryCreated }: Props) {
  const queryClient = useQueryClient();
  const runQuery = useQuery({
    queryKey: ['evaluation', 'runs', runId],
    queryFn: () => evaluationApi.getRun(runId!),
    enabled: Boolean(runId),
    refetchInterval: (query) => runPollInterval(query.state.data?.status ?? 'COMPLETED'),
  });
  const resultsQuery = useQuery({
    queryKey: ['evaluation', 'runs', runId, 'results'],
    queryFn: () => evaluationApi.listRunResults(runId!),
    enabled: Boolean(runId),
    refetchInterval: () => runPollInterval(runQuery.data?.status ?? 'COMPLETED'),
  });

  const refreshRun = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'runs'] }),
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'runs', runId] }),
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'overview'] }),
    ]);
  };
  const cancelMutation = useMutation({
    mutationFn: (run: EvaluationRunSummary) => evaluationApi.cancelRun(run.id, { expectedVersion: run.version }),
    onSuccess: async () => {
      message.success('取消请求已提交');
      await refreshRun();
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });
  const retryMutation = useMutation({
    mutationFn: (run: EvaluationRunSummary) => evaluationApi.retryRunErrors(run.id, { expectedVersion: run.version }),
    onSuccess: async (retryRun) => {
      message.success('错误题已生成新的重试任务');
      await refreshRun();
      onRetryCreated(retryRun);
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });
  const reviewMutation = useMutation({
    mutationFn: ({ result, passed }: { result: EvaluationResult; passed: boolean }) =>
      evaluationApi.reviewRunResult(runId!, result.id, {
        expectedVersion: result.version,
        score: passed ? 1 : 0,
        passed,
      }),
    onSuccess: async (_, variables) => {
      message.success(variables.passed ? '已人工判定为通过' : '已人工判定为失败');
      await refreshRun();
      await queryClient.invalidateQueries({ queryKey: ['evaluation', 'runs', runId, 'results'] });
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });

  const run = runQuery.data;
  const loading = runQuery.isLoading || resultsQuery.isLoading;
  const failed = runQuery.isError || resultsQuery.isError;

  return (
    <Drawer
      rootClassName="evaluation-drawer"
      title="评测任务详情"
      open={Boolean(runId)}
      size={820}
      onClose={onClose}
      destroyOnHidden
      extra={<Button type="text" aria-label="关闭评测任务详情" icon={<X size={17} />} onClick={onClose} />}
    >
      {loading ? (
        <div className="evaluation-loading" aria-label="正在加载任务详情"><Spin size="large" /></div>
      ) : failed || !run ? (
        <div className="evaluation-error" role="alert">
          <div><strong>任务详情加载失败</strong><p>可重新请求任务概况和逐题结果。</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => { void runQuery.refetch(); void resultsQuery.refetch(); }}>重新加载</Button>
        </div>
      ) : (
        <div className="evaluation-detail-stack">
          <div className="evaluation-run-detail-head">
            <div>
              <span className={`evaluation-status run-${run.status.toLowerCase()}`}>{runStatusLabel(run.status)}</span>
              <h3>{run.id}</h3>
              <p>{formatTime(run.startedAt)} 至 {formatTime(run.completedAt)}</p>
            </div>
            <div className="evaluation-drawer-actions">
              {isActive(run.status) && (
                <Popconfirm
                  title="确定取消该评测任务？"
                  description="已完成的结果会保留，未完成项将停止。"
                  okText="确认取消"
                  cancelText="继续运行"
                  onConfirm={() => cancelMutation.mutate(run)}
                  okButtonProps={{ loading: cancelMutation.isPending }}
                >
                  <Button danger icon={<Square size={14} />} loading={cancelMutation.isPending}>取消任务</Button>
                </Popconfirm>
              )}
              {!isActive(run.status) && run.errorCount > 0 && (
                <Popconfirm
                  title="创建错误题重试任务？"
                  description={`将仅重试 ${run.errorCount} 道执行错误的题目，原任务保持不变。`}
                  okText="创建重试"
                  cancelText="取消"
                  onConfirm={() => retryMutation.mutate(run)}
                  okButtonProps={{ loading: retryMutation.isPending }}
                >
                  <Button icon={<RotateCcw size={14} />} loading={retryMutation.isPending}>重试错误题</Button>
                </Popconfirm>
              )}
            </div>
          </div>

          <div className="evaluation-run-stat-grid">
            <RunStat label="进度" value={formatRunProgress(run.completedCount, run.totalCount)} detail={`${run.completedCount} / ${run.totalCount} 题`} />
            <RunStat label="通过 / 失败" value={`${run.passedCount} / ${run.failedCount}`} detail={`${run.manualReviewCount} 题已人工复核`} />
            <RunStat label="Token" value={formatTokenCount(run.totalTokens)} detail="输入与输出合计" />
            <RunStat label="成本" value={formatRunCost(run.totalCost)} detail="按任务快照单价核算" />
            <RunStat label="延迟" value={formatLatency(run.avgLatencyMs)} detail={`P95 ${formatLatency(run.p95LatencyMs)}`} />
            <RunStat label="错误" value={`${run.errorCount} 题`} detail={run.cancelRequested ? '已请求取消' : '无取消请求'} />
          </div>
          <p className="evaluation-muted">{formatLatencySummary(run.avgLatencyMs, run.p95LatencyMs)}</p>

          <section className="evaluation-results" aria-labelledby="evaluation-results-heading">
            <div className="evaluation-content-section-head">
              <div><h4 id="evaluation-results-heading">逐题结果</h4><p>输入、期望和模型输出仅在展开当前结果时显示。</p></div>
            </div>
            {(resultsQuery.data ?? []).map((result) => (
              <ResultItem
                key={result.id}
                result={result}
                reviewing={reviewMutation.isPending && reviewMutation.variables?.result.id === result.id}
                onReview={(passed) => reviewMutation.mutate({ result, passed })}
              />
            ))}
            {!resultsQuery.data?.length && <div className="evaluation-empty evaluation-empty-compact"><strong>暂无逐题结果</strong></div>}
          </section>
        </div>
      )}
    </Drawer>
  );
}

function RunStat({ label, value, detail }: { label: string; value: string; detail: string }) {
  return <article><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>;
}

function ResultItem({ result, reviewing, onReview }: {
  result: EvaluationResult;
  reviewing: boolean;
  onReview: (passed: boolean) => void;
}) {
  const needsReview = result.evaluatorType === 'MANUAL' && result.status === 'PENDING' && result.reviewRequired;
  return (
    <details className="evaluation-result-item">
      <summary>
        <span className="evaluation-order">{result.orderNo}</span>
        <span><strong>{result.snapshotName}</strong><small>{result.snapshotCategory} · {result.evaluatorType}</small></span>
        <span className={`evaluation-status result-${result.status.toLowerCase()}`}>{resultStatusLabel(result.status)}</span>
      </summary>
      <div className="evaluation-result-body">
        <div className="evaluation-result-metrics">
          <span>{formatTokenCount(result.totalTokens)}</span>
          <span>{formatRunCost(result.cost)}</span>
          <span>{formatLatency(result.latencyMs)}</span>
          <span>尝试 {result.attemptCount} 次</span>
        </div>
        {result.errorSummary && <div className="evaluation-error evaluation-error-inline" role="alert"><XCircle size={16} /><span>{result.errorSummary}</span></div>}
        <div className="evaluation-result-content-grid">
          <ContentBlock title="案例输入" value={result.input} />
          <ContentBlock title="期望 JSON" value={result.expectedJson} />
          <ContentBlock title="模型输出" value={result.actualOutput || '暂无输出'} />
        </div>
        {needsReview && (
          <div className="evaluation-manual-review">
            <div><strong>等待人工评分</strong><p>请核对输入、期望和输出后做出二元判定。</p></div>
            <div>
              <Popconfirm title="确认人工判定为失败？" okText="确认失败" cancelText="取消" onConfirm={() => onReview(false)}>
                <Button danger icon={<XCircle size={14} />} loading={reviewing}>人工失败</Button>
              </Popconfirm>
              <Popconfirm title="确认人工判定为通过？" okText="确认通过" cancelText="取消" onConfirm={() => onReview(true)}>
                <Button type="primary" icon={<Check size={14} />} loading={reviewing}>人工通过</Button>
              </Popconfirm>
            </div>
          </div>
        )}
      </div>
    </details>
  );
}

function ContentBlock({ title, value }: { title: string; value: string }) {
  return <div className="evaluation-content-block"><h4>{title}</h4><pre>{value}</pre></div>;
}
