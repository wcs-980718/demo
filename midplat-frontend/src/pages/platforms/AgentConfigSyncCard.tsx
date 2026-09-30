import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Spin, message } from 'antd';
import { useNavigate } from '@umijs/max';
import { Bot, RefreshCw } from 'lucide-react';
import { midplatApi, type AgentConfigSync } from '@/api/midplatApi';
import { PlatformDeliveryStatus } from './PlatformDeliveryStatus';
import { SettingsSectionHeader } from '@/components/SettingsSectionHeader';

export const agentConfigModeLabel: Record<string, { text: string; cls: 'ok' | 'warn' | 'muted-st' }> = {
  push: { text: '直连热更新', cls: 'ok' },
  pull: { text: '下游读取 · 发布即生效', cls: 'ok' },
  unbound: { text: '未绑定智能体', cls: 'muted-st' },
  unpublished: { text: '待发布', cls: 'warn' },
  unknown: { text: '状态未知', cls: 'warn' },
};

export function agentConfigSourceText(view: Pick<AgentConfigSync, 'releaseSequence' | 'source'>) {
  if (view.releaseSequence == null) return '—';
  return `R${view.releaseSequence} · ${view.source === 'active' ? '已上线版本' : '已发布版本（尚未上线）'}`;
}

/** 项目页只读展示“接入的智能体配置”：模型与提示词在智能体开发里改，发布后由中台直连下发。 */
export function AgentConfigSyncCard({ platformId, step = 2 }: { platformId: string; step?: number }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const key = ['agent-config-sync', platformId];
  const query = useQuery({ queryKey: key, queryFn: () => midplatApi.getAgentConfigSync(platformId), refetchInterval: 8000, retry: false });
  const syncMutation = useMutation({
    mutationFn: () => midplatApi.syncAgentConfig(platformId, true),
    onSuccess: async (view) => {
      message.success(view.message || '已提交下发');
      queryClient.setQueryData(key, view);
      await queryClient.invalidateQueries({ queryKey: ['platform-delivery', platformId] });
    },
    onError: (error: Error) => message.error(error.message),
  });
  const view = query.data;
  if (query.isPending) return <section className="comp"><div className="agent-runtime-loading"><Spin /></div></section>;
  if (query.isError) {
    return (
      <section className="comp">
        <SettingsSectionHeader step={step} title="接入的智能体配置" description="模型与提示词在智能体开发中维护。" />
        <div className="project-api-error">
          <div><strong>智能体配置读取失败</strong><p>{(query.error as Error).message}</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => query.refetch()}>重试</Button>
        </div>
      </section>
    );
  }
  if (!view || !view.applicable) return null;
  const mode = agentConfigModeLabel[view.mode] ?? agentConfigModeLabel.unknown;
  const bound = view.releaseSequence != null;
  return (
    <section className="comp" aria-label="接入的智能体配置">
      <SettingsSectionHeader
        step={step}
        title="接入的智能体配置"
        description="模型与规则提示词只在智能体开发里修改；发布后由中台直连下发到本项目，无需在这里操作。"
        extra={<span className={`status ${mode.cls}`}>{mode.text}</span>}
      />
      {bound ? (
        <dl className="agent-runtime-grid">
          <div><dt>生效版本</dt><dd>{agentConfigSourceText(view)}</dd></div>
          <div><dt>承载任务</dt><dd className="mono">{view.taskKey || '—'}</dd></div>
          <div><dt>对话模型</dt><dd className="mono">{view.model || '—'}</dd></div>
          <div><dt>规则提示词</dt><dd>{view.promptChars ? `${view.promptChars} 字` : '—'}</dd></div>
        </dl>
      ) : null}
      <p className="note" style={{ marginTop: bound ? 12 : 0 }}>{view.message}</p>
      {view.mode === 'push' ? <PlatformDeliveryStatus platformId={platformId} /> : null}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Button icon={<Bot size={14} />} onClick={() => navigate(`/agent-hub/agents?project=${encodeURIComponent(platformId)}`)}>
          {view.mode === 'unbound' ? '去绑定智能体' : '去智能体开发修改'}
        </Button>
        {view.mode === 'push' ? (
          <Button icon={<RefreshCw size={14} />} loading={syncMutation.isPending} onClick={() => syncMutation.mutate()}>立即重新下发</Button>
        ) : null}
      </div>
    </section>
  );
}
