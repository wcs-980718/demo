import { useQuery } from '@tanstack/react-query';
import { Button, Spin } from 'antd';
import {
  Activity,
  BadgeCheck,
  ListChecks,
  Percent,
  RefreshCw,
  Snowflake,
  TrendingDown,
} from 'lucide-react';
import { evaluationApi } from './evaluationApi';
import { formatOverviewPassRate, getEvaluationEmptyState } from './evaluationPresentation';

export function OverviewPanel({ onOpenRuns }: { onOpenRuns: () => void }) {
  const overviewQuery = useQuery({
    queryKey: ['evaluation', 'overview'],
    queryFn: evaluationApi.getOverview,
  });
  const latestRunQuery = useQuery({
    queryKey: ['evaluation', 'runs', 'overview-latest'],
    queryFn: () => evaluationApi.listRuns({ page: 0, size: 1 }),
  });

  const retry = () => {
    void overviewQuery.refetch();
    void latestRunQuery.refetch();
  };

  if (overviewQuery.isLoading || latestRunQuery.isLoading) {
    return <div className="evaluation-loading" aria-label="正在加载评测总览"><Spin size="large" /></div>;
  }
  if (overviewQuery.isError || latestRunQuery.isError || !overviewQuery.data) {
    return (
      <div className="evaluation-error" role="alert">
        <div>
          <strong>评测总览加载失败</strong>
          <p>请检查中台服务连接后重试，本页不会展示不完整的统计。</p>
        </div>
        <Button icon={<RefreshCw size={15} />} onClick={retry}>重新加载</Button>
      </div>
    );
  }

  const overview = overviewQuery.data;
  const metrics = [
    { label: '案例总数', value: overview.totalCases, suffix: '个', icon: ListChecks },
    { label: '已复核案例', value: overview.reviewedCases, suffix: '个', icon: BadgeCheck },
    { label: '冻结版本', value: overview.frozenVersions, suffix: '个', icon: Snowflake },
    { label: '运行中任务', value: overview.runningRuns, suffix: '个', icon: Activity },
    { label: '最近通过率', value: formatOverviewPassRate(overview.latestTerminalPassRate), suffix: '', icon: Percent },
    { label: '回归案例', value: overview.regressionCount, suffix: '个', icon: TrendingDown },
  ];

  return (
    <section className="evaluation-section" aria-labelledby="evaluation-overview-heading">
      <div className="evaluation-section-head">
        <div>
          <p className="evaluation-kicker">运行态势</p>
          <h3 id="evaluation-overview-heading">评测治理总览</h3>
          <p>从案例资产到回归信号，集中观察当前评测基线。</p>
        </div>
      </div>
      <div className="evaluation-metric-grid">
        {metrics.map((metric) => {
          const Icon = metric.icon;
          return (
            <article className="evaluation-metric" key={metric.label}>
              <span className="evaluation-metric-icon" aria-hidden="true"><Icon size={18} /></span>
              <span className="evaluation-metric-label">{metric.label}</span>
              <strong>{metric.value}<small>{metric.suffix}</small></strong>
            </article>
          );
        })}
      </div>
      {latestRunQuery.data?.totalElements === 0 ? (
        <OverviewRunEmpty onOpenRuns={onOpenRuns} />
      ) : (
        <div className="evaluation-insight" role="status">
          <BadgeCheck size={18} aria-hidden="true" />
          <div>
            <strong>最近一次终态运行已纳入统计</strong>
            <p>最近通过率与回归案例数来自同一份后端一致性快照，可进入“评测任务”查看逐题结果。</p>
          </div>
          <Button onClick={onOpenRuns}>查看评测任务</Button>
        </div>
      )}
    </section>
  );
}

function OverviewRunEmpty({ onOpenRuns }: { onOpenRuns: () => void }) {
  const empty = getEvaluationEmptyState('overviewRuns');
  return (
    <div className="evaluation-empty evaluation-empty-inline">
      <Activity size={24} aria-hidden="true" />
      <div>
        <strong>{empty.title}</strong>
        <p>{empty.description}</p>
      </div>
      <Button type="primary" onClick={onOpenRuns}>{empty.actionLabel}</Button>
    </div>
  );
}
