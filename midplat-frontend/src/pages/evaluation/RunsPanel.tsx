import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Progress, Spin, Table, type TableProps } from 'antd';
import { Activity, Eye, Plus, RefreshCw } from 'lucide-react';
import { evaluationApi } from './evaluationApi';
import {
  formatLatencySummary,
  formatRunCost,
  formatRunProgress,
  formatTokenCount,
  runPollInterval,
  runStatusLabel,
} from './evaluationPresentation';
import type { EvaluationRunSummary } from './evaluationTypes';
import { RunCreateModal } from './RunCreateModal';
import { RunDetailDrawer } from './RunDetailDrawer';

function progressValue(run: EvaluationRunSummary): number {
  if (run.totalCount <= 0) return 0;
  return Math.min(100, Math.round((run.completedCount / run.totalCount) * 100));
}

function formatTime(value: string | null): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '尚未开始';
}

export function RunsPanel() {
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const runsQuery = useQuery({
    queryKey: ['evaluation', 'runs', 'list'],
    queryFn: () => evaluationApi.listRuns({ page: 0, size: 50 }),
    refetchInterval: (query) => {
      const active = query.state.data?.content.find((run) => runPollInterval(run.status) !== false);
      return active ? 2_000 : false;
    },
  });

  const openCreated = async (run: EvaluationRunSummary) => {
    setCreateOpen(false);
    setSelectedRunId(run.id);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'runs'] }),
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'overview'] }),
    ]);
  };

  const columns: TableProps<EvaluationRunSummary>['columns'] = [
    {
      title: '任务',
      dataIndex: 'id',
      key: 'id',
      render: (_, run) => (
        <div className="evaluation-table-title">
          <strong>{run.sourceRunId ? '错误题重试' : '标准评测任务'}</strong>
          <span className="evaluation-mono">{run.id}</span>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: EvaluationRunSummary['status']) => <span className={`evaluation-status run-${status.toLowerCase()}`}>{runStatusLabel(status)}</span>,
    },
    {
      title: '进度',
      key: 'progress',
      width: 170,
      render: (_, run) => (
        <div className="evaluation-run-progress">
          <Progress percent={progressValue(run)} size="small" status={run.status === 'FAILED' ? 'exception' : undefined} />
          <span>{formatRunProgress(run.completedCount, run.totalCount)} · {run.completedCount}/{run.totalCount}</span>
        </div>
      ),
    },
    {
      title: '消耗',
      key: 'usage',
      width: 190,
      render: (_, run) => <div className="evaluation-table-title"><strong>{formatTokenCount(run.totalTokens)}</strong><span>{formatRunCost(run.totalCost)}</span></div>,
    },
    {
      title: '延迟',
      key: 'latency',
      width: 190,
      render: (_, run) => <span className="evaluation-muted">{formatLatencySummary(run.avgLatencyMs, run.p95LatencyMs)}</span>,
    },
    {
      title: '开始时间',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 135,
      render: formatTime,
    },
    {
      title: '操作',
      key: 'actions',
      width: 112,
      render: (_, run) => <Button icon={<Eye size={15} />} onClick={() => setSelectedRunId(run.id)}>查看详情</Button>,
    },
  ];

  return (
    <section className="evaluation-section" aria-labelledby="evaluation-runs-heading">
      <div className="evaluation-section-head">
        <div>
          <p className="evaluation-kicker">RUNS &amp; RESULTS</p>
          <h3 id="evaluation-runs-heading">评测任务</h3>
          <p>在冻结快照上执行模型评测，集中查看质量、Token、成本和延迟。</p>
        </div>
        <Button type="primary" icon={<Plus size={16} />} onClick={() => setCreateOpen(true)}>创建评测任务</Button>
      </div>

      {runsQuery.isLoading ? (
        <div className="evaluation-loading" aria-label="正在加载评测任务"><Spin size="large" /></div>
      ) : runsQuery.isError ? (
        <div className="evaluation-error" role="alert">
          <div><strong>评测任务加载失败</strong><p>请检查中台服务后重试，已有任务不会被修改。</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => void runsQuery.refetch()}>重新加载</Button>
        </div>
      ) : runsQuery.data?.content.length ? (
        <>
          <div className="evaluation-table-wrap">
            <Table<EvaluationRunSummary> rowKey="id" className="surface-table" columns={columns} dataSource={runsQuery.data.content} pagination={false} />
          </div>
          <div className="evaluation-mobile-list">
            {runsQuery.data.content.map((run) => (
              <article className="evaluation-mobile-card" key={run.id}>
                <div className="evaluation-mobile-card-head">
                  <div><strong>{run.sourceRunId ? '错误题重试' : '标准评测任务'}</strong><span className="evaluation-mono">{run.id}</span></div>
                  <span className={`evaluation-status run-${run.status.toLowerCase()}`}>{runStatusLabel(run.status)}</span>
                </div>
                <Progress percent={progressValue(run)} size="small" status={run.status === 'FAILED' ? 'exception' : undefined} />
                <div className="evaluation-mobile-meta"><span>{formatTokenCount(run.totalTokens)}</span><span>{formatRunCost(run.totalCost)}</span></div>
                <p>{formatLatencySummary(run.avgLatencyMs, run.p95LatencyMs)} · {formatTime(run.startedAt)}</p>
                <Button icon={<Eye size={15} />} onClick={() => setSelectedRunId(run.id)}>查看详情</Button>
              </article>
            ))}
          </div>
        </>
      ) : (
        <div className="evaluation-empty">
          <Activity size={26} aria-hidden="true" />
          <div><strong>还没有评测任务</strong><p>冻结评测集版本并配置好平台后，即可创建首次运行。</p></div>
          <Button type="primary" onClick={() => setCreateOpen(true)}>创建首个任务</Button>
        </div>
      )}

      <RunCreateModal open={createOpen} onClose={() => setCreateOpen(false)} onCreated={(run) => void openCreated(run)} />
      <RunDetailDrawer runId={selectedRunId} onClose={() => setSelectedRunId(null)} onRetryCreated={(run) => setSelectedRunId(run.id)} />
    </section>
  );
}
