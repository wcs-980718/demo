import { useMemo, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Input, Select, Spin, message } from 'antd';
import { Plus, RefreshCw, Search, ShieldCheck, Waypoints } from 'lucide-react';
import { SettingsSectionHeader } from '@/components/SettingsSectionHeader';
import { midplatApi, type PlatformApiItem, type PlatformApiPayload, type PlatformItem } from '@/api/midplatApi';
import { workspaceBoundPlatformIds } from '@/menuHref';
import { ApiFormModal, type ApiFormValues } from '@/pages/platforms/ApiFormModal';
import { ProjectApiRow } from '@/pages/platforms/ProjectApiRow';
import { sortProjectApis, summarizeProjectApis } from '@/pages/platforms/apiCatalog';

type CatalogRow = PlatformApiItem & {
  platformId: string;
};

type ScopeFilter = 'all' | 'external' | 'internal';

export default function CapabilitiesPage() {
  const queryClient = useQueryClient();
  const [query, setQuery] = useState('');
  const [projectId, setProjectId] = useState<string | 'all'>('all');
  const [scope, setScope] = useState<ScopeFilter>('all');
  const [editor, setEditor] = useState<{
    mode: 'create' | 'edit';
    platformId: string;
    api: Partial<PlatformApiItem> | null;
  } | null>(null);

  const menusQuery = useQuery({
    queryKey: ['menus'],
    queryFn: midplatApi.listMenus,
  });
  const platformsQuery = useQuery({
    queryKey: ['platforms'],
    queryFn: midplatApi.listPlatforms,
  });
  const boundIds = useMemo(
    () => new Set(workspaceBoundPlatformIds(menusQuery.data ?? [])),
    [menusQuery.data],
  );
  const platforms = useMemo(
    () => (platformsQuery.data ?? []).filter((item) => boundIds.has(item.id)),
    [boundIds, platformsQuery.data],
  );
  const apiQueries = useQueries({
    queries: platforms.map((platform) => ({
      queryKey: ['platform-apis', platform.id],
      queryFn: () => midplatApi.listApis(platform.id),
      enabled: platformsQuery.isSuccess,
    })),
  });

  const rows = useMemo<CatalogRow[]>(() => {
    return platforms.flatMap((platform, index) => {
      const apis = apiQueries[index]?.data ?? [];
      return apis.map((api) => ({ ...api, platformId: platform.id }));
    });
  }, [apiQueries, platforms]);

  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return rows.filter((row) => {
      const matchedQuery = !keyword || [row.name, row.id, row.path, row.note, row.method]
        .some((value) => value.toLowerCase().includes(keyword));
      const matchedProject = projectId === 'all' || row.platformId === projectId;
      const matchedScope = scope === 'all'
        || (scope === 'external' && row.external)
        || (scope === 'internal' && !row.external);
      return matchedQuery && matchedProject && matchedScope;
    });
  }, [projectId, query, rows, scope]);

  const grouped = useMemo(() => {
    const searching = Boolean(query.trim()) || scope !== 'all' || projectId !== 'all';
    return platforms
      .filter((platform) => projectId === 'all' || platform.id === projectId)
      .map((platform) => ({
        platform,
        apis: sortProjectApis(filtered.filter((row) => row.platformId === platform.id)),
      }))
      .filter((group) => group.apis.length > 0 || !searching);
  }, [filtered, platforms, projectId, query, scope]);

  const summary = summarizeProjectApis(rows);
  const loading = menusQuery.isLoading || platformsQuery.isLoading || apiQueries.some((item) => item.isLoading);
  const loadError = menusQuery.isError || platformsQuery.isError || apiQueries.some((item) => item.isError);
  const platformOptions = platforms.map((platform) => ({ value: platform.id, label: platform.name }));

  const remember = (ownerId: string, items: PlatformApiItem[]) => {
    queryClient.setQueryData(['platform-apis', ownerId], items);
  };
  const toggleMutation = useMutation({
    mutationFn: ({ ownerId, apiId, external }: { ownerId: string; apiId: string; external: boolean }) =>
      midplatApi.toggleApi(ownerId, apiId, external),
    onSuccess: (items, variables) => {
      remember(variables.ownerId, items);
      message.success(variables.external ? '已允许其他项目经中台调用' : '已禁止其他项目经中台调用');
    },
    onError: (error: Error) => message.error(`保存失败：${error.message}`),
  });
  const saveMutation = useMutation({
    mutationFn: (payload: ApiFormValues) => {
      const ownerId = payload.platformId ?? editor?.platformId;
      if (!ownerId) {
        throw new Error('请选择所属项目');
      }
      const body: PlatformApiPayload = {
        id: payload.id,
        name: payload.name,
        method: payload.method,
        path: payload.path,
        note: payload.note,
        external: payload.external,
      };
      return editor?.mode === 'edit' && editor.api?.id
        ? midplatApi.updateApi(ownerId, editor.api.id, body)
        : midplatApi.createApi(ownerId, body);
    },
    onSuccess: (items, payload) => {
      const ownerId = payload.platformId ?? editor?.platformId;
      if (ownerId) {
        remember(ownerId, items);
      }
      message.success(editor?.mode === 'edit' ? '接口已更新' : '接口已新增');
      setEditor(null);
    },
    onError: (error: Error) => message.error(`保存失败：${error.message}`),
  });
  const deleteMutation = useMutation({
    mutationFn: ({ ownerId, apiId }: { ownerId: string; apiId: string }) =>
      midplatApi.deleteApi(ownerId, apiId),
    onSuccess: (items, variables) => {
      remember(variables.ownerId, items);
      message.success('接口已删除');
    },
    onError: (error: Error) => message.error(`删除失败：${error.message}`),
  });

  const pendingKey = toggleMutation.isPending
    ? `${toggleMutation.variables?.ownerId}:${toggleMutation.variables?.apiId}`
    : undefined;

  const reload = () => {
    void menusQuery.refetch();
    void platformsQuery.refetch();
    apiQueries.forEach((item) => {
      void item.refetch();
    });
  };

  return (
    <div className="page-shell capability-page">
      <div className="page-head capability-page-head">
        <div>
          <p className="eyebrow">EXTERNAL API SETTINGS</p>
          <h2>对外接口总览</h2>
          <p className="sub">跨项目查看可经中台转发的接口。某个项目自己的开关在 AI 工作台的「对外接口设置」里改，两边是同一套目录。</p>
        </div>
        <span className="status ok">已允许跨项目 {summary.external} 项</span>
      </div>
      <p className="note">经中台调用必须带调用方「接入凭证」：Authorization: Bearer sk-mid-...。开关只决定其他项目能不能调；不带凭证 401，关闭后其他项目经中台调用 403。本项目自己调用不受开关影响。</p>

      <section className="capability-metrics" aria-label="接口开放概览">
        <Metric label="接口总数" value={summary.total} />
        <Metric label="允许其他项目" value={summary.external} />
        <Metric label="仅本项目" value={summary.internal} />
        <Metric label="项目数" value={platforms.length} />
      </section>

      <div className="capability-toolbar">
        <Input
          className="capability-search"
          prefix={<Search size={16} />}
          placeholder="搜索接口名称、路径或用途"
          allowClear
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <Select
          aria-label="按所属项目筛选"
          value={projectId}
          onChange={setProjectId}
          className="capability-filter"
          options={[
            { value: 'all', label: '全部项目' },
            ...platforms.map((platform) => ({ value: platform.id, label: platform.name })),
          ]}
        />
        <Select
          aria-label="按开放范围筛选"
          value={scope}
          onChange={setScope}
          className="capability-filter capability-status-filter"
          options={[
            { value: 'all', label: '全部范围' },
            { value: 'external', label: '允许其他项目' },
            { value: 'internal', label: '仅本项目' },
          ]}
        />
        <span className="capability-result-count">{filtered.length} 项接口</span>
      </div>

      {loading ? (
        <div className="project-api-loading" aria-label="正在加载接口目录"><Spin size="large" /></div>
      ) : loadError ? (
        <div className="project-api-error" role="alert">
          <div>
            <strong>接口目录加载失败</strong>
            <p>请检查中台服务后重试。</p>
          </div>
          <Button icon={<RefreshCw size={14} />} onClick={reload}>重新加载</Button>
        </div>
      ) : grouped.length === 0 ? (
        <div className="empty-panel">没有匹配的接口，请调整筛选条件。</div>
      ) : (
        <div className="capability-api-groups">
          {grouped.map(({ platform, apis }) => (
            <ProjectApiGroup
              key={platform.id}
              platform={platform}
              apis={apis}
              pendingKey={pendingKey}
              toggling={toggleMutation.isPending}
              onCreate={() => setEditor({ mode: 'create', platformId: platform.id, api: null })}
              onToggle={(apiId, external) => toggleMutation.mutate({ ownerId: platform.id, apiId, external })}
              onEdit={(api) => setEditor({ mode: 'edit', platformId: platform.id, api })}
              onDelete={(apiId) => deleteMutation.mutate({ ownerId: platform.id, apiId })}
            />
          ))}
        </div>
      )}

      <p className="capability-catalog-hint">
        <ShieldCheck size={15} />
        <span>开发中心按项目分组看全部接口，工作台只看当前项目。两边改的是同一套目录。</span>
      </p>
      <ApiFormModal
        open={Boolean(editor)}
        title={editor?.mode === 'edit' ? '修改接口' : '新增接口'}
        submitting={saveMutation.isPending}
        initial={editor ? { ...editor.api, platformId: editor.platformId || undefined } : null}
        platformOptions={editor?.mode === 'create' && !editor.platformId ? platformOptions : undefined}
        lockPlatform={editor?.mode === 'edit' || Boolean(editor?.platformId)}
        lockId={editor?.mode === 'edit'}
        entryUrl={platforms.find((item) => item.id === editor?.platformId)?.entryUrl}
        onCancel={() => setEditor(null)}
        onSubmit={(payload) => saveMutation.mutate({ ...payload, platformId: payload.platformId || editor?.platformId })}
      />
    </div>
  );
}

function ProjectApiGroup({
  platform,
  apis,
  pendingKey,
  toggling,
  onCreate,
  onToggle,
  onEdit,
  onDelete,
}: {
  platform: PlatformItem;
  apis: CatalogRow[];
  pendingKey?: string;
  toggling: boolean;
  onCreate: () => void;
  onToggle: (apiId: string, external: boolean) => void;
  onEdit: (api: CatalogRow) => void;
  onDelete: (apiId: string) => void;
}) {
  return (
    <section className="comp project-api-panel">
      <SettingsSectionHeader
        title={platform.name}
        description="本项目可经中台转发的接口。开关与工作台该项目的对外接口设置同步，这里是跨项目总览。"
        extra={(
          <Button type="primary" size="small" icon={<Plus size={14} />} onClick={onCreate}>
            新增接口
          </Button>
        )}
      />
      {apis.length === 0 ? (
        <div className="empty-panel">这个项目还没有对外接口。</div>
      ) : (
        <div className="project-api-list" role="list" aria-label={`${platform.name}接口开放列表`}>
          {apis.map((api) => (
            <ProjectApiRow
              key={`${platform.id}:${api.id}`}
              platformId={platform.id}
              platformName={platform.name}
              entryUrl={platform.entryUrl}
              token={platform.token}
              api={api}
              saving={pendingKey === `${platform.id}:${api.id}`}
              disabled={toggling && pendingKey !== `${platform.id}:${api.id}`}
              onToggle={(external) => onToggle(api.id, external)}
              onEdit={() => onEdit(api)}
              onDelete={() => onDelete(api.id)}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <article className="capability-metric">
      <span className="capability-metric-icon blue" aria-hidden="true"><Waypoints size={18} /></span>
      <span>
        <strong>{value}</strong>
        <small>{label}</small>
      </span>
    </article>
  );
}
