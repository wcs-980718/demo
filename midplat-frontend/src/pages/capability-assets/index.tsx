import { useEffect, useState } from 'react';
import { useNavigate } from '@umijs/max';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Drawer, Input, Modal, Pagination, Popconfirm, Select, Spin, Switch, Table, Tag, message } from 'antd';
import { ArrowRight, BookOpen, Boxes, Cpu, Database, FileText, Plus, RefreshCw, Search, Sparkles, Wrench } from 'lucide-react';
import { midplatApi, type CapabilityAssetContent, type CapabilityAssetDetail, type CapabilityAssetGrantInput, type CapabilityAssetKind, type CapabilityAssetSync } from '@/api/midplatApi';
import { PageHeader } from '@/components/PageHeader';
import { AssetFormModal, ASSET_KIND_LABEL, type AssetFormPayload } from './assetForm';
import './asset.css';

const KIND_TABS = [
  { key: 'all', label: '全部' },
  { key: 'skill', label: '技能' },
  { key: 'tool', label: '工具' },
  { key: 'knowledge', label: '知识库' },
  { key: 'data', label: '数据源' },
  { key: 'model', label: '模型' },
  { key: 'prompt', label: '提示词' },
];

const STATUS_LABEL: Record<string, string> = { DRAFT: '草稿', ACTIVE: '启用', DISABLED: '停用', ARCHIVED: '已归档' };
const STATUS_COLOR: Record<string, string> = { DRAFT: 'default', ACTIVE: 'green', DISABLED: 'orange', ARCHIVED: 'red' };
const SYNC_COLOR: Record<string, string> = { SYNCED: 'green', FAILED: 'red', PENDING: 'orange', DISABLED: 'default' };
const ENVIRONMENTS = [
  { value: 'development', label: '开发环境' },
  { value: 'staging', label: '预发布环境' },
  { value: 'production', label: '生产配置' },
];

const isAssetKind = (kind: string): kind is CapabilityAssetKind => kind in ASSET_KIND_LABEL;
const shortHash = (value: string | null) => (value ? value.slice(0, 8) : '—');

const KIND_META: Record<string, { label: string; icon: typeof Sparkles; chip: string }> = {
  skill: { label: '技能', icon: Sparkles, chip: 'asset-chip-skill' },
  tool: { label: '工具', icon: Wrench, chip: 'asset-chip-tool' },
  knowledge: { label: '知识库', icon: BookOpen, chip: 'asset-chip-knowledge' },
  data: { label: '数据源', icon: Database, chip: 'asset-chip-data' },
  model: { label: '模型', icon: Cpu, chip: 'asset-chip-model' },
  prompt: { label: '提示词', icon: FileText, chip: 'asset-chip-prompt' },
};
const kindMeta = (kind: string) => KIND_META[kind] ?? { label: kind, icon: Boxes, chip: '' };

function statusTag(status: string) {
  return <Tag color={STATUS_COLOR[status] || 'default'}>{STATUS_LABEL[status] || status}</Tag>;
}

export default function CapabilityAssetsPage() {
  const cache = useQueryClient();
  const navigate = useNavigate();
  const [kind, setKind] = useState('all');
  const [status, setStatus] = useState('all');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const pageSize = 12;
  const [editor, setEditor] = useState<{ mode: 'create' | 'edit'; asset: CapabilityAssetDetail | null } | null>(null);
  const [drawerId, setDrawerId] = useState<string | null>(null);
  const [grantsOpen, setGrantsOpen] = useState(false);

  const listQuery = useQuery({
    queryKey: ['capability-assets', kind, status, search, page],
    queryFn: () => midplatApi.listCapabilityAssets({
      kind: kind === 'all' ? undefined : kind,
      status: status === 'all' ? undefined : status,
      search: search.trim() || undefined,
      page: page - 1,
      pageSize,
    }),
    retry: false,
  });

  const reload = () => { void listQuery.refetch(); };

  const refreshAll = () => {
    void cache.invalidateQueries({ queryKey: ['capability-assets'] });
    void cache.invalidateQueries({ queryKey: ['capability-asset'] });
    void cache.invalidateQueries({ queryKey: ['asset-grants'] });
  };

  const reportSync = (sync?: CapabilityAssetSync[]) => {
    if (!sync || sync.length === 0) return;
    const failed = sync.filter((item) => item.status === 'FAILED');
    if (failed.length) message.warning(`已保存本地授权，但 ${failed.length} 个智能体镜像同步失败（${failed[0].error || '上游不可用'}），可在详情中查看并重试。`, 8);
    else message.success(`已同步 ${sync.length} 个智能体镜像`);
  };

  const saveMutation = useMutation({
    mutationFn: async (payload: AssetFormPayload & { expectedRevision?: number }) => {
      if (editor?.mode === 'edit' && editor.asset) {
        return midplatApi.updateCapabilityAsset(editor.asset.id, {
          expectedRevision: payload.expectedRevision ?? editor.asset.revision,
          content: payload.content,
          name: payload.name,
          description: payload.description ?? null,
          note: '来自能力资产页面编辑',
        });
      }
      return midplatApi.createCapabilityAsset(payload);
    },
    onSuccess: (detail) => {
      setEditor(null);
      refreshAll();
      reportSync((detail as { sync?: CapabilityAssetSync[] }).sync);
      message.success(editor?.mode === 'edit' ? '已保存为新修订' : '资产已创建（正文只保存在中台，模型/提示词目录仅登记摘要）');
    },
    onError: (error: Error) => message.error(`保存失败：${error.message}`),
  });

  const statusMutation = useMutation({
    mutationFn: ({ id, next }: { id: string; next: string }) => midplatApi.setCapabilityAssetStatus(id, next),
    onSuccess: (detail) => {
      refreshAll();
      reportSync(detail.sync);
      message.success('状态已更新');
    },
    onError: (error: Error) => message.error(`状态更新失败：${error.message}`),
  });

  const openEdit = async (id: string) => {
    try {
      setEditor({ mode: 'edit', asset: await midplatApi.getCapabilityAsset(id) });
    } catch (error) {
      message.error((error as Error).message);
    }
  };

  const rows = listQuery.data?.content ?? [];
  const hasAssetKind = isAssetKind(kind) || kind === 'all';

  return (
    <div className="page-shell capability-assets-page">
      <PageHeader
        eyebrow="CAPABILITY ASSETS"
        title="能力资产"
        description="统一管理技能、工具、知识库与数据源四类能力资产；修订不可变，项目按授权引用固定修订并镜像到智能体侧。"
        actions={(
          <>
            <Button icon={<Boxes size={14} />} onClick={() => setGrantsOpen(true)}>项目授权</Button>
            <Button type="primary" icon={<Plus size={14} />} disabled={!hasAssetKind} onClick={() => setEditor({ mode: 'create', asset: null })}>新建资产</Button>
            <Button icon={<RefreshCw size={14} />} onClick={reload} aria-label="刷新能力资产" />
          </>
        )}
      />
      <Alert
        type="info"
        showIcon
        title="知识库/数据源为兼容内嵌文档/数据资产"
        description="正文随中台修订管理并镜像到智能体侧，不代表已连接外部知识库或数据平台；密钥不允许写入资产正文（可只填部署配置 secretRef 引用）。模型与提示词仍由各自页面维护，此处仅为目录摘要。"
      />

      <div className="toolbar">
        <div className="tabs">
          {KIND_TABS.map((tab) => (
            <button key={tab.key} type="button" className={kind === tab.key ? 'active' : ''} onClick={() => { setKind(tab.key); setPage(1); }}>
              {tab.label}
            </button>
          ))}
        </div>
        <Input
          prefix={<Search size={14} />}
          placeholder="搜索资产名称"
          allowClear
          value={search}
          onChange={(event) => { setSearch(event.target.value); setPage(1); }}
          className="capability-assets-search toolbar-search"
        />
        <Select
          aria-label="按状态筛选"
          value={status}
          onChange={(value) => { setStatus(value); setPage(1); }}
          style={{ width: 140 }}
          options={[
            { value: 'all', label: '全部状态' },
            { value: 'DRAFT', label: '草稿' },
            { value: 'ACTIVE', label: '启用' },
            { value: 'DISABLED', label: '停用' },
            { value: 'ARCHIVED', label: '已归档' },
          ]}
        />
        <span className="capability-assets-count">{listQuery.data?.total ?? 0} 项资产</span>
      </div>

      {listQuery.isError && <Alert type="error" showIcon title="能力资产加载失败" description={(listQuery.error as Error).message} action={<Button size="small" onClick={reload}>重试</Button>} />}

      {listQuery.isPending ? (
        <div className="loading-panel"><Spin size="large" /></div>
      ) : !rows.length ? (
        <div className="empty-panel">{search || kind !== 'all' || status !== 'all' ? '没有匹配的资产，请调整筛选条件。' : '还没有能力资产，点右上角「新建资产」开始登记。'}</div>
      ) : (
        <>
          <div className="entry-grid">
            {rows.map((row) => {
              const meta = kindMeta(row.kind);
              const KindIcon = meta.icon;
              const manageable = isAssetKind(row.kind);
              return (
                <article key={row.id} className="entry-card asset-card">
                  <div className="entry-top">
                    <div className="asset-head">
                      <span className={`asset-icon ${meta.chip}`}><KindIcon size={17} /></span>
                      <div className="asset-title">
                        <button type="button" className="asset-name" onClick={() => (manageable ? setDrawerId(row.id) : navigate(row.kind === 'model' ? '/models' : '/prompts'))}>{row.name}</button>
                        <p className="asset-sub">{row.description || (manageable ? `${meta.label}资产` : '中台目录摘要')}</p>
                      </div>
                    </div>
                    <span className={`chip ${meta.chip}`}>{meta.label}</span>
                  </div>
                  <div className="asset-meta">
                    {statusTag(row.status)}
                    {row.revision != null && <span className="asset-rev">r{row.revision} · <code>{shortHash(row.hash)}</code></span>}
                  </div>
                  <div className="asset-usage">
                    {manageable ? (
                      <>
                        <span>{row.grantCount} 项授权</span>
                        <span className="asset-sync-ok">已同步 {row.sync.synced}</span>
                        {row.sync.failed > 0 && <span className="asset-sync-failed">失败 {row.sync.failed}</span>}
                        {row.sync.pending > 0 && <span className="asset-sync-pending">待同步 {row.sync.pending}</span>}
                      </>
                    ) : <span>{row.grantCount} 处引用</span>}
                  </div>
                  <div className="asset-actions">
                    {manageable ? (
                      <>
                        <Button size="small" onClick={() => setDrawerId(row.id)}>详情</Button>
                        {row.status !== 'ARCHIVED' && <Button size="small" onClick={() => void openEdit(row.id)}>编辑</Button>}
                        {row.status !== 'ACTIVE' && row.status !== 'ARCHIVED' && (
                          <Button size="small" loading={statusMutation.isPending && statusMutation.variables?.id === row.id} onClick={() => statusMutation.mutate({ id: row.id, next: 'ACTIVE' })}>启用</Button>
                        )}
                        {row.status === 'ACTIVE' && (
                          <Button size="small" onClick={() => statusMutation.mutate({ id: row.id, next: 'DISABLED' })}>停用</Button>
                        )}
                        {row.status !== 'ARCHIVED' && (
                          <Popconfirm title="确定归档该资产？" description="归档为终态：智能体侧镜像会停用，历史修订仍可查看。" okText="归档" cancelText="取消" onConfirm={() => statusMutation.mutate({ id: row.id, next: 'ARCHIVED' })}>
                            <Button size="small" danger ghost className="asset-danger">归档</Button>
                          </Popconfirm>
                        )}
                      </>
                    ) : (
                      <Button size="small" onClick={() => navigate(row.kind === 'model' ? '/models' : '/prompts')}>{row.kind === 'model' ? '模型管理' : '提示词管理'}<ArrowRight size={13} /></Button>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
          <div className="asset-pagination">
            <Pagination current={page} pageSize={pageSize} total={listQuery.data?.total ?? 0} onChange={setPage} showTotal={(total) => `共 ${total} 项`} hideOnSinglePage />
          </div>
        </>
      )}

      <AssetFormModal
        open={Boolean(editor)}
        initial={editor?.asset ?? null}
        submitting={saveMutation.isPending}
        onCancel={() => setEditor(null)}
        onSubmit={(payload) => saveMutation.mutate(payload)}
      />

      <AssetDrawer id={drawerId} onClose={() => setDrawerId(null)} onSynced={reportSync} />
      <GrantsPanel open={grantsOpen} onClose={() => setGrantsOpen(false)} />
    </div>
  );
}

function AssetDrawer({ id, onClose, onSynced }: { id: string | null; onClose: () => void; onSynced: (sync?: CapabilityAssetSync[]) => void }) {
  const cache = useQueryClient();
  const detail = useQuery({
    queryKey: ['capability-asset', id],
    queryFn: () => midplatApi.getCapabilityAsset(id!),
    enabled: Boolean(id),
    retry: false,
  });
  const retrySync = useMutation({
    mutationFn: () => midplatApi.retryCapabilityAssetSync(id!),
    onSuccess: (result) => {
      void cache.invalidateQueries({ queryKey: ['capability-asset', id] });
      void cache.invalidateQueries({ queryKey: ['capability-assets'] });
      onSynced(result.sync);
    },
    onError: (error: Error) => message.error(`重试同步失败：${error.message}`),
  });
  const asset = detail.data;
  return (
    <Drawer open={Boolean(id)} onClose={onClose} width={720} title={asset ? `能力资产 · ${asset.name}` : '能力资产'}>
      {detail.isError && <Alert type="error" showIcon title="详情加载失败" description={(detail.error as Error).message} />}
      {asset && (
        <div className="capability-asset-detail">
          <p className="capability-assets-meta">
            {ASSET_KIND_LABEL[asset.kind]} {statusTag(asset.status)} 修订 r{asset.revision} · <code>{shortHash(asset.hash)}</code> · {asset.grantCount} 项授权
          </p>
          {asset.description && <p className="capability-assets-muted">{asset.description}</p>}
          <h4>当前修订正文</h4>
          <AssetContentView kind={asset.kind} content={asset.content} />
          <h4>修订历史</h4>
          <Table
            rowKey="id" size="small" pagination={false} dataSource={asset.revisions}
            columns={[
              { title: '修订', dataIndex: 'revision', render: (value: number) => `r${value}` },
              { title: '哈希', dataIndex: 'hash', render: (value: string) => <code>{shortHash(value)}</code> },
              { title: '说明', dataIndex: 'note' },
              { title: '创建人', dataIndex: 'createdBy' },
              { title: '时间', dataIndex: 'createdAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
            ]}
          />
          <h4>项目授权</h4>
          {asset.grants.length === 0 ? <p className="capability-assets-muted">还没有项目引用该资产。</p> : (
            <Table
              rowKey="id" size="small" pagination={false} dataSource={asset.grants}
              columns={[
                { title: '项目', dataIndex: 'projectName' },
                { title: '环境', dataIndex: 'environment' },
                { title: '操作', dataIndex: 'operation' },
                { title: '版本规则', dataIndex: 'versionRule', render: (value: string) => (value === 'pinned' ? '固定修订' : '跟随最新') },
                { title: '状态', dataIndex: 'enabled', render: (value: boolean) => <Tag color={value ? 'green' : 'orange'}>{value ? '已启用' : '已停用'}</Tag> },
              ]}
            />
          )}
          <h4>智能体镜像同步</h4>
          {asset.sync.length === 0 ? <p className="capability-assets-muted">尚无镜像记录；为启用中的资产授权项目后会自动同步。</p> : (
            <Table
              rowKey={(row) => `${row.projectId}:${row.environment}:${row.fusionAssetId ?? ''}`} size="small" pagination={false} dataSource={asset.sync}
              columns={[
                { title: '项目/环境', key: 'target', render: (_, row) => `${asset?.grants.find((grant) => grant.projectId === row.projectId)?.projectName ?? row.projectId} · ${row.environment}` },
                { title: '镜像资产', dataIndex: 'fusionAssetId', render: (value: string | null) => (value ? <code>{value.slice(0, 12)}</code> : '—') },
                { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={SYNC_COLOR[value] || 'default'}>{value}</Tag> },
                { title: '错误', dataIndex: 'error', render: (value: string | null) => (value ? <span className="capability-assets-error">{value}</span> : '—') },
              ]}
            />
          )}
          {asset.sync.some((item) => item.status === 'FAILED') && (
            <Button style={{ marginBottom: 12 }} loading={retrySync.isPending} onClick={() => retrySync.mutate()}>重试失败的镜像同步</Button>
          )}
          {asset.status !== 'ARCHIVED' && <Alert type="info" showIcon title="编辑会创建新修订" description="旧修订与哈希永久保留。停用会尝试停用智能体镜像；归档为终态，不再允许启用。" />}
        </div>
      )}
    </Drawer>
  );
}

function AssetContentView({ kind, content }: { kind: CapabilityAssetKind; content: CapabilityAssetContent | null }) {
  if (!content) return <p className="capability-assets-muted">尚无正文。</p>;
  if (kind === 'skill') return <pre className="capability-asset-code">{content.body ?? ''}</pre>;
  if (kind === 'tool') return (
    <div className="capability-assets-tool-view">
      <p><code>{content.method} {content.url}</code> {content.readOnly ? '（只读）' : ''}</p>
      <p className="capability-assets-muted">{content.description}</p>
      {content.secretRef ? <p>凭证引用：<code>{String(content.secretRef)}</code></p> : null}
      <pre className="capability-asset-code">{JSON.stringify(content.parameters ?? {}, null, 2)}</pre>
    </div>
  );
  return (
    <div className="capability-asset-docs">
      {(content.documents ?? []).map((doc, index) => (
        <section className="comp" key={index}>
          <strong>文档 {index + 1} · {doc.title}</strong>
          <pre className="capability-asset-code">{doc.text}</pre>
        </section>
      ))}
    </div>
  );
}

type EditableGrant = {
  key: string;
  assetId: string;
  assetName: string;
  operation: string;
  versionRule: 'pinned' | 'current';
  revisionId: string | null;
  enabled: boolean;
};

function GrantsPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const cache = useQueryClient();
  const [projectId, setProjectId] = useState<string>();
  const [environment, setEnvironment] = useState('development');
  const [rows, setRows] = useState<EditableGrant[]>([]);
  const [pick, setPick] = useState<{ assetId?: string; operation: string; versionRule: 'pinned' | 'current'; revisionId?: string }>({ operation: 'use', versionRule: 'current' });

  const projects = useQuery({ queryKey: ['platforms'], queryFn: midplatApi.listPlatforms, enabled: open, retry: false });
  const grants = useQuery({
    queryKey: ['asset-grants', projectId, environment],
    queryFn: () => midplatApi.listAssetGrants(projectId!, environment),
    enabled: open && Boolean(projectId),
    retry: false,
  });
  const activeAssets = useQuery({
    queryKey: ['capability-assets', 'active-pool'],
    queryFn: () => midplatApi.listCapabilityAssets({ status: 'ACTIVE', includeCatalog: false, pageSize: 100 }),
    enabled: open,
    retry: false,
  });

  useEffect(() => {
    if (grants.data) {
      setRows((grants.data.grants ?? []).map((row) => ({
        key: row.id,
        assetId: row.assetId,
        assetName: row.assetName,
        operation: row.operation,
        versionRule: row.versionRule,
        revisionId: row.revisionId,
        enabled: row.enabled,
      })));
    }
  }, [grants.data]);

  const policyRevision = grants.data?.policyRevision ?? 0;
  const grantableAssets = (activeAssets.data?.content ?? []).filter((item) => isAssetKind(item.kind));

  const revisionsOf = async (assetId: string) => midplatApi.capabilityAssetRevisions(assetId);

  const addRow = async () => {
    const assetId = pick.assetId;
    if (!assetId) { message.warning('请选择要授权的能力资产'); return; }
    const operation = pick.operation.trim() || 'use';
    if (rows.some((row) => row.assetId === assetId && row.operation === operation)) {
      message.warning('同一资产的该操作已存在');
      return;
    }
    let revisionId: string | null = null;
    if (pick.versionRule === 'pinned') {
      const revisions = await revisionsOf(assetId);
      revisionId = pick.revisionId ?? revisions[0]?.id ?? null;
      if (!revisionId) { message.warning('该资产还没有可选修订'); return; }
    }
    setRows((current) => [...current, {
      key: `new-${current.length}-${Date.now()}`,
      assetId,
      assetName: grantableAssets.find((item) => item.id === assetId)?.name ?? assetId,
      operation,
      versionRule: pick.versionRule,
      revisionId,
      enabled: true,
    }]);
    setPick({ operation: 'use', versionRule: 'current' });
  };

  const save = useMutation({
    mutationFn: () => midplatApi.replaceAssetGrants(projectId!, environment, {
      policyRevision,
      grants: rows.map((row): CapabilityAssetGrantInput => ({
        assetId: row.assetId,
        operation: row.operation.trim() || 'use',
        versionRule: row.versionRule,
        revisionId: row.versionRule === 'pinned' ? row.revisionId : null,
        enabled: row.enabled,
      })),
    }),
    onSuccess: (result) => {
      message.success('项目授权已保存');
      const failed = (result.sync ?? []).filter((item) => item.status === 'FAILED');
      if (failed.length) message.warning(`${failed.length} 个智能体镜像同步失败（${failed[0].error || '上游不可用'}），可在资产详情查看并重试。`, 8);
      void cache.invalidateQueries({ queryKey: ['asset-grants'] });
      void cache.invalidateQueries({ queryKey: ['capability-assets'] });
      void cache.invalidateQueries({ queryKey: ['capability-asset'] });
    },
    onError: (error: Error) => message.error(`保存授权失败：${error.message}`),
  });

  const switchRule = async (key: string, rule: 'pinned' | 'current') => {
    const row = rows.find((item) => item.key === key);
    if (!row) return;
    let revisionId = row.revisionId;
    if (rule === 'current') revisionId = null;
    else if (!revisionId) {
      const revisions = await revisionsOf(row.assetId);
      revisionId = revisions[0]?.id ?? null;
    }
    setRows((current) => current.map((item) => (item.key === key ? { ...item, versionRule: rule, revisionId } : item)));
  };

  return (
    <Modal open={open} title="项目能力资产授权" width={860} okText="保存授权" cancelText="关闭" confirmLoading={save.isPending} destroyOnHidden
      onCancel={onClose}
      onOk={() => {
        if (!projectId) { message.warning('请选择项目'); return; }
        if (rows.some((row) => !row.assetId || !row.operation.trim())) { message.warning('授权行的操作标识不能为空'); return; }
        if (rows.some((row) => row.versionRule === 'pinned' && !row.revisionId)) { message.warning('固定修订授权必须选择修订'); return; }
        save.mutate();
      }}
    >
      <Alert type="info" showIcon title="保存是整组替换" description="本页保存该项目在当前环境的全部资产授权；乐观锁冲突会被拒绝并提示刷新。镜像同步失败不影响本地授权保存，状态在资产详情中可审查。" style={{ marginBottom: 12 }} />
      <div className="capability-assets-grant-toolbar">
        <Select
          aria-label="选择项目" showSearch optionFilterProp="label" style={{ minWidth: 220 }}
          placeholder="选择项目"
          value={projectId}
          onChange={(value) => { setProjectId(value); setRows([]); }}
          options={(projects.data ?? []).map((item) => ({ value: item.id, label: item.name, title: item.name }))}
        />
        <Select aria-label="选择环境" style={{ width: 150 }} value={environment} onChange={setEnvironment} options={ENVIRONMENTS} />
        <Button size="small" icon={<RefreshCw size={13} />} onClick={() => void grants.refetch()}>刷新</Button>
      </div>
      {grants.isError && <Alert type="error" showIcon title="授权加载失败" description={(grants.error as Error).message} />}
      <Table<EditableGrant>
        rowKey="key" size="small" pagination={false} dataSource={rows}
        locale={{ emptyText: projectId ? '该项目还没有能力资产授权。' : '先选择项目。' }}
        columns={[
          { title: '资产', dataIndex: 'assetName' },
          { title: '操作', dataIndex: 'operation', render: (value: string, row) => <Input size="small" style={{ width: 120 }} value={value} onChange={(event) => setRows((current) => current.map((item) => (item.key === row.key ? { ...item, operation: event.target.value } : item)))} /> },
          { title: '版本规则', dataIndex: 'versionRule', render: (value: string, row) => (
            <Select size="small" style={{ width: 110 }} value={value} options={[{ value: 'current', label: '跟随最新' }, { value: 'pinned', label: '固定修订' }]} onChange={(next) => void switchRule(row.key, next as 'pinned' | 'current')} />
          ) },
          { title: '修订', key: 'revision', render: (_, row) => (row.versionRule === 'pinned'
            ? <PinnedRevisionSelect assetId={row.assetId} value={row.revisionId ?? undefined} onChange={(value) => setRows((current) => current.map((item) => (item.key === row.key ? { ...item, revisionId: value } : item)))} />
            : <span className="capability-assets-muted">最新</span>) },
          { title: '启用', dataIndex: 'enabled', render: (value: boolean, row) => <Switch size="small" checked={value} onChange={(next) => setRows((current) => current.map((item) => (item.key === row.key ? { ...item, enabled: next } : item)))} /> },
          { title: '', key: 'remove', width: 56, render: (_, row) => <Button size="small" danger onClick={() => setRows((current) => current.filter((item) => item.key !== row.key))}>移除</Button> },
        ]}
      />
      <div className="capability-assets-grant-add">
        <Select
          aria-label="选择资产" showSearch optionFilterProp="label" placeholder="选择启用中的资产" style={{ minWidth: 220 }}
          value={pick.assetId}
          onChange={(value) => setPick((current) => ({ ...current, assetId: value, revisionId: undefined }))}
          options={grantableAssets.map((item) => ({ value: item.id, label: item.name, title: item.name }))}
        />
        <Input aria-label="操作标识" style={{ width: 120 }} value={pick.operation} onChange={(event) => setPick((current) => ({ ...current, operation: event.target.value }))} placeholder="use" />
        <Select
          aria-label="版本规则" style={{ width: 110 }} value={pick.versionRule}
          options={[{ value: 'current', label: '跟随最新' }, { value: 'pinned', label: '固定修订' }]}
          onChange={(value) => setPick((current) => ({ ...current, versionRule: value }))}
        />
        {pick.versionRule === 'pinned' && pick.assetId && (
          <PinnedRevisionSelect assetId={pick.assetId} value={pick.revisionId} onChange={(value) => setPick((current) => ({ ...current, revisionId: value }))} />
        )}
        <Button size="small" type="primary" icon={<Plus size={13} />} onClick={() => void addRow()}>添加授权行</Button>
      </div>
      <p className="capability-assets-muted">当前策略版本：{policyRevision}（保存后 +1；清空授权也会递增，不会回退到 0）</p>
    </Modal>
  );
}

function PinnedRevisionSelect({ assetId, value, onChange }: { assetId: string; value?: string; onChange: (value: string) => void }) {
  const revisions = useQuery({
    queryKey: ['capability-asset-revisions', assetId],
    queryFn: () => midplatApi.capabilityAssetRevisions(assetId),
    retry: false,
  });
  return (
    <Select
      aria-label="选择固定修订" style={{ width: 200 }} placeholder="选择修订" showSearch optionFilterProp="label"
      value={value ?? revisions.data?.[0]?.id}
      onChange={onChange}
      options={(revisions.data ?? []).map((item) => ({ value: item.id, label: `r${item.revision} · ${item.hash.slice(0, 8)}` }))}
    />
  );
}
