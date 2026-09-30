import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Spin, message } from 'antd';
import { Plus, RefreshCw, ShieldCheck, Waypoints } from 'lucide-react';
import { midplatApi, type PlatformApiItem, type PlatformApiPayload, type PlatformItem } from '@/api/midplatApi';
import { SettingsSectionHeader } from '@/components/SettingsSectionHeader';
import { ApiFormModal, type ApiFormValues } from '@/pages/platforms/ApiFormModal';
import { ProjectApiRow } from '@/pages/platforms/ProjectApiRow';
import { sortProjectApis, summarizeProjectApis } from '@/pages/platforms/apiCatalog';

export function ProjectApiConfig({ platform }: { platform: PlatformItem }) {
  const queryClient = useQueryClient();
  const queryKey = ['platform-apis', platform.id] as const;
  const [editor, setEditor] = useState<{ mode: 'create' | 'edit'; api: Partial<PlatformApiItem> | null } | null>(null);
  const apisQuery = useQuery({
    queryKey,
    queryFn: () => midplatApi.listApis(platform.id),
  });
  const remember = (items: PlatformApiItem[]) => {
    queryClient.setQueryData(queryKey, items);
  };
  const toggleMutation = useMutation({
    mutationFn: ({ apiId, external }: { apiId: string; external: boolean }) =>
      midplatApi.toggleApi(platform.id, apiId, external),
    onSuccess: (items, variables) => {
      remember(items);
      message.success(variables.external ? '已允许其他项目经中台调用' : '已禁止其他项目经中台调用');
    },
    onError: (error: Error) => message.error(`保存失败：${error.message}`),
  });
  const saveMutation = useMutation({
    mutationFn: (payload: ApiFormValues) => {
      const body: PlatformApiPayload = {
        id: payload.id,
        name: payload.name,
        method: payload.method,
        path: payload.path,
        note: payload.note,
        external: payload.external,
      };
      return editor?.mode === 'edit' && editor.api?.id
        ? midplatApi.updateApi(platform.id, editor.api.id, body)
        : midplatApi.createApi(platform.id, body);
    },
    onSuccess: (items) => {
      remember(items);
      message.success(editor?.mode === 'edit' ? '接口已更新' : '接口已新增');
      setEditor(null);
    },
    onError: (error: Error) => message.error(`保存失败：${error.message}`),
  });
  const deleteMutation = useMutation({
    mutationFn: (apiId: string) => midplatApi.deleteApi(platform.id, apiId),
    onSuccess: (items) => {
      remember(items);
      message.success('接口已删除');
    },
    onError: (error: Error) => message.error(`删除失败：${error.message}`),
  });

  const apis = sortProjectApis(apisQuery.data ?? []);
  const summary = summarizeProjectApis(apis);
  const pendingApiId = toggleMutation.isPending ? toggleMutation.variables?.apiId : undefined;

  return (
    <>
      <div className="page-head">
        <div>
          <h2>{platform.name} · 对外接口设置</h2>
          <p className="sub">控制其他项目能否经中台调用这些接口。经中台请求必须携带本项目或调用方项目的接入凭证。</p>
        </div>
        <Button type="primary" icon={<Plus size={16} />} onClick={() => setEditor({ mode: 'create', api: null })}>新增接口</Button>
      </div>
      <p className="note">经中台调用必须带调用方「接入凭证」：Authorization: Bearer sk-mid-...。开关只决定其他项目能不能调；不带凭证 401，关闭后其他项目经中台调用 403。直连来源系统地址仍可能绕过此开关。</p>

      <div className="form-layout project-api-layout">
        <div className="form-main">
          <section className="comp project-api-panel">
            <SettingsSectionHeader
              step={1}
              title="接口开放范围"
              description="逐项确认请求方法、访问路径和用途，再决定是否允许其他项目经中台调用。"
              extra={<span className="chip"><Waypoints size={13} /> 当前项目</span>}
            />

            {apisQuery.isLoading ? (
              <div className="project-api-loading" aria-label="正在加载接口列表"><Spin /></div>
            ) : apisQuery.isError ? (
              <div className="project-api-error" role="alert">
                <div>
                  <strong>接口列表加载失败</strong>
                  <p>{apisQuery.error instanceof Error ? apisQuery.error.message : '请稍后重试'}</p>
                </div>
                <Button icon={<RefreshCw size={14} />} onClick={() => apisQuery.refetch()}>重新加载</Button>
              </div>
            ) : apis.length === 0 ? (
              <div className="empty-panel">当前项目还没有可管理的接口。</div>
            ) : (
              <div className="project-api-list" role="list" aria-label={`${platform.name}接口开放列表`}>
                {apis.map((api) => (
                  <ProjectApiRow
                    key={api.id}
                    platformId={platform.id}
                    platformName={platform.name}
                    entryUrl={platform.entryUrl}
                    token={platform.token}
                    api={api}
                    saving={pendingApiId === api.id}
                    disabled={toggleMutation.isPending && pendingApiId !== api.id}
                    onToggle={(external) => toggleMutation.mutate({ apiId: api.id, external })}
                    onEdit={() => setEditor({ mode: 'edit', api })}
                    onDelete={() => deleteMutation.mutate(api.id)}
                  />
                ))}
              </div>
            )}
          </section>
        </div>

        <aside className="form-aside">
          <div className="aside-card">
            <h4>开放概况</h4>
            <div className="aside-row"><span className="label">接口总数</span><span className="value">{summary.total}</span></div>
            <div className="aside-row"><span className="label">允许其他项目</span><span className="value">{summary.external}</span></div>
            <div className="aside-row"><span className="label">仅本项目</span><span className="value">{summary.internal}</span></div>
          </div>
          <div className="aside-card project-api-guidance">
            <h4><ShieldCheck size={15} /> 开放原则</h4>
            <p>只打开标注发布等明确跨项目用途的接口；删除、日志和管理类接口保持仅本项目。</p>
          </div>
          <div className="aside-hint">与开发中心「对外接口总览」是同一套接口目录和开关，这里只显示当前项目。</div>
        </aside>
      </div>
      <ApiFormModal
        open={Boolean(editor)}
        title={editor?.mode === 'edit' ? '修改接口' : '新增接口'}
        submitting={saveMutation.isPending}
        initial={editor ? { ...editor.api, platformId: platform.id } : null}
        lockPlatform
        lockId={editor?.mode === 'edit'}
        entryUrl={platform.entryUrl}
        onCancel={() => setEditor(null)}
        onSubmit={(payload) => saveMutation.mutate(payload)}
      />
    </>
  );
}


