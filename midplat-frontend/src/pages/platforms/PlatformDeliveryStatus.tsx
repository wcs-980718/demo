import { useQuery } from '@tanstack/react-query';
import { Button } from 'antd';
import { midplatApi } from '@/api/midplatApi';

const labels: Record<string, string> = {
  pending: '等待下发', sending: '下发与核对中', applied: '已核对生效',
  failed: '生效失败', blocked: '已保存 · 下发隔离', untracked: '尚无下发核对记录',
};

export function PlatformDeliveryStatus({ platformId }: { platformId: string }) {
  const query = useQuery({
    queryKey: ['platform-delivery', platformId], queryFn: () => midplatApi.getDelivery(platformId),
    refetchInterval: q => ['pending', 'sending'].includes(q.state.data?.status ?? '') ? 1500 : false,
  });
  const state = query.data;
  return <section className="comp" aria-label="配置下发状态" style={{ marginBottom: 16 }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'center' }}>
      <div>
        <strong>{query.isPending ? '正在读取下发状态' : query.isError ? '下发状态读取失败' : labels[state?.status ?? ''] ?? state?.status}</strong>
        <p className="note" style={{ margin: '6px 0 0' }}>
          {query.isError ? (query.error as Error).message : state?.lastError ?? (state?.status === 'untracked'
            ? '现有配置尚未经过本次下发流程核对；保存后会记录目标版本与实际生效版本。'
            : `目标版本：${state?.desiredRevision ?? '—'} · 已核对生效版本：${state?.appliedRevision ?? '—'}`)}
        </p>
        {state?.lastError ? <p className="note" style={{ margin: '6px 0 0' }}>目标版本：{state.desiredRevision ?? '—'} · 已核对生效版本：{state.appliedRevision ?? '—'}</p> : null}
      </div>
      <Button size="small" loading={query.isFetching} onClick={() => query.refetch()}>刷新状态</Button>
    </div>
  </section>;
}
