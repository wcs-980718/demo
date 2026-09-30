import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Drawer, Popconfirm, Select, Spin, message } from 'antd';
import { CopyPlus, RefreshCw, Save, Snowflake } from 'lucide-react';
import { evaluationApi } from './evaluationApi';
import {
  canEditDatasetVersion,
  caseSeverityLabel,
  evaluationMutationErrorMessage,
  formatDatasetVersion,
} from './evaluationPresentation';
import type {
  EvaluationCaseSummary,
  EvaluationDatasetDetail,
  EvaluationDatasetVersion,
} from './evaluationTypes';

export function DatasetDetailDrawer({
  datasetId,
  onClose,
}: {
  datasetId: string | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [selectedVersionId, setSelectedVersionId] = useState<string>();
  const [draftCaseIds, setDraftCaseIds] = useState<string[]>([]);
  const detailQuery = useQuery({
    queryKey: ['evaluation', 'dataset', datasetId],
    queryFn: () => evaluationApi.getDataset(datasetId as string),
    enabled: Boolean(datasetId),
  });
  const versions = detailQuery.data?.versions ?? [];
  const selectedVersion = versions.find((version) => version.id === selectedVersionId) ?? versions[0];
  const draftVersion = versions.find((version) => version.status === 'DRAFT');
  const latestFrozenVersion = versions.find((version) => version.status === 'FROZEN');

  useEffect(() => {
    if (!detailQuery.data?.versions.length) {
      setSelectedVersionId(undefined);
      setDraftCaseIds([]);
      return;
    }
    setSelectedVersionId((current) =>
      detailQuery.data?.versions.some((version) => version.id === current)
        ? current
        : detailQuery.data?.versions[0].id,
    );
  }, [detailQuery.data]);

  useEffect(() => {
    setDraftCaseIds(selectedVersion?.items.map((item) => item.caseId) ?? []);
  }, [selectedVersion?.id, selectedVersion?.version]);

  const reviewedCasesQuery = useQuery({
    queryKey: ['evaluation', 'reviewed-cases'],
    enabled: Boolean(datasetId && selectedVersion?.status === 'DRAFT'),
    queryFn: async () => {
      const first = await evaluationApi.listCases({
        reviewStatus: 'REVIEWED',
        lifecycleStatus: 'ACTIVE',
        page: 0,
        size: 100,
      });
      if (first.totalPages <= 1) return first.items;
      const remaining = await Promise.all(
        Array.from({ length: first.totalPages - 1 }, (_, index) => evaluationApi.listCases({
          reviewStatus: 'REVIEWED',
          lifecycleStatus: 'ACTIVE',
          page: index + 1,
          size: 100,
        })),
      );
      return [first, ...remaining].flatMap((page) => page.items);
    },
    staleTime: 30_000,
  });

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'dataset', datasetId] }),
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'datasets'] }),
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'overview'] }),
    ]);
  };
  const replaceMutation = useMutation({
    mutationFn: (version: EvaluationDatasetVersion) => evaluationApi.replaceDatasetVersionItems(version.id, {
      expectedVersion: version.version,
      caseIds: draftCaseIds,
    }),
    onSuccess: async () => {
      message.success('草稿条目已保存');
      await invalidate();
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });
  const freezeMutation = useMutation({
    mutationFn: (version: EvaluationDatasetVersion) =>
      evaluationApi.freezeDatasetVersion(version.id, { expectedVersion: version.version }),
    onSuccess: async () => {
      message.success('版本已冻结，快照内容不可再修改');
      await invalidate();
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });
  const deriveMutation = useMutation({
    mutationFn: (detail: EvaluationDatasetDetail) =>
      evaluationApi.deriveDatasetVersion(detail.id, { expectedVersion: detail.version }),
    onSuccess: async (created) => {
      message.success(`已从最新冻结版派生 V${created.versionNo} 草稿`);
      setSelectedVersionId(created.id);
      await invalidate();
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });
  const busy = replaceMutation.isPending || freezeMutation.isPending || deriveMutation.isPending;
  const caseOptions = useMemo(() => {
    const reviewed = reviewedCasesQuery.data ?? [];
    const byId = new Map<string, EvaluationCaseSummary>(reviewed.map((item) => [item.id, item] as const));
    const options = reviewed.map((item) => {
      const label = `${caseSeverityLabel(item.severity)} · ${item.name} · ${item.category}`;
      return { value: item.id, label, title: label };
    });
    for (const caseId of draftCaseIds) {
      if (!byId.has(caseId)) { const label = `当前条目 ${caseId}（已不在可选案例中）`; options.push({ value: caseId, label, title: label }); }
    }
    return options;
  }, [draftCaseIds, reviewedCasesQuery.data]);

  return (
    <Drawer
      title="评测集详情"
      open={Boolean(datasetId)}
      size={780}
      rootClassName="evaluation-drawer"
      destroyOnHidden
      onClose={busy ? undefined : onClose}
    >
      {detailQuery.isLoading ? (
        <div className="evaluation-loading"><Spin size="large" /></div>
      ) : detailQuery.isError || !detailQuery.data ? (
        <div className="evaluation-error" role="alert">
          <div><strong>评测集详情加载失败</strong><p>版本和条目尚未加载，请重试。</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => void detailQuery.refetch()}>重试</Button>
        </div>
      ) : (
        <DatasetDetailContent
          detail={detailQuery.data}
          selectedVersion={selectedVersion}
          draftVersion={draftVersion}
          latestFrozenVersion={latestFrozenVersion}
          draftCaseIds={draftCaseIds}
          caseOptions={caseOptions}
          reviewedCasesLoading={reviewedCasesQuery.isLoading}
          busy={busy}
          replacing={replaceMutation.isPending}
          freezing={freezeMutation.isPending}
          deriving={deriveMutation.isPending}
          onSelectVersion={setSelectedVersionId}
          onChangeCaseIds={setDraftCaseIds}
          onReplace={() => selectedVersion && replaceMutation.mutate(selectedVersion)}
          onFreeze={() => selectedVersion && freezeMutation.mutate(selectedVersion)}
          onDerive={() => deriveMutation.mutate(detailQuery.data as EvaluationDatasetDetail)}
        />
      )}
    </Drawer>
  );
}

function DatasetDetailContent({
  detail,
  selectedVersion,
  draftVersion,
  latestFrozenVersion,
  draftCaseIds,
  caseOptions,
  reviewedCasesLoading,
  busy,
  replacing,
  freezing,
  deriving,
  onSelectVersion,
  onChangeCaseIds,
  onReplace,
  onFreeze,
  onDerive,
}: {
  detail: EvaluationDatasetDetail;
  selectedVersion: EvaluationDatasetVersion | undefined;
  draftVersion: EvaluationDatasetVersion | undefined;
  latestFrozenVersion: EvaluationDatasetVersion | undefined;
  draftCaseIds: string[];
  caseOptions: Array<{ value: string; label: string }>;
  reviewedCasesLoading: boolean;
  busy: boolean;
  replacing: boolean;
  freezing: boolean;
  deriving: boolean;
  onSelectVersion: (id: string) => void;
  onChangeCaseIds: (ids: string[]) => void;
  onReplace: () => void;
  onFreeze: () => void;
  onDerive: () => void;
}) {
  if (!selectedVersion) return <div className="evaluation-empty"><strong>此评测集还没有版本</strong></div>;
  const editable = canEditDatasetVersion(selectedVersion.status);
  const savedCaseIds = selectedVersion.items.map((item) => item.caseId);
  const changed = savedCaseIds.length !== draftCaseIds.length
    || savedCaseIds.some((caseId, index) => caseId !== draftCaseIds[index]);

  return (
    <div className="evaluation-detail-stack">
      <div className="evaluation-detail-title">
        <div><h3>{detail.name}</h3><p>{detail.description || '暂无用途说明'}</p></div>
        {!draftVersion && latestFrozenVersion ? (
          <Button icon={<CopyPlus size={15} />} loading={deriving} disabled={busy} onClick={onDerive}>
            从最新冻结版派生草稿
          </Button>
        ) : null}
      </div>
      <div className="evaluation-version-picker">
        <label htmlFor="evaluation-dataset-version">查看版本</label>
        <Select
          id="evaluation-dataset-version"
          aria-label="选择评测集版本"
          value={selectedVersion.id}
          options={detail.versions.map((version) => ({ value: version.id, label: formatDatasetVersion(version) }))}
          onChange={onSelectVersion}
        />
        <span>{selectedVersion.items.length} 个条目</span>
      </div>

      {editable ? (
        <section className="evaluation-dataset-editor" aria-labelledby="evaluation-dataset-items-heading">
          <div><h4 id="evaluation-dataset-items-heading">草稿条目</h4><p>仅显示已审核且启用中的案例，可搜索名称、分类或严重级别。</p></div>
          <Select
            mode="multiple"
            aria-label="选择已审核案例"
            showSearch
            optionFilterProp="label"
            loading={reviewedCasesLoading}
            value={draftCaseIds}
            options={caseOptions}
            placeholder="选择要加入的已审核案例"
            maxTagCount="responsive"
            onChange={onChangeCaseIds}
          />
          <div className="evaluation-editor-actions">
            <Button icon={<Save size={15} />} loading={replacing} disabled={!changed || busy} onClick={onReplace}>
              保存条目
            </Button>
            <Popconfirm
              title="确定冻结这个版本？"
              description="冻结不可逆；系统会固化案例输入、期望、评分方式和内容哈希。"
              okText="确认冻结"
              cancelText="取消"
              okButtonProps={{ loading: freezing }}
              onConfirm={onFreeze}
            >
              <Button icon={<Snowflake size={15} />} disabled={busy || changed || draftCaseIds.length === 0}>
                冻结版本
              </Button>
            </Popconfirm>
          </div>
          <div className="evaluation-freeze-warning" role="note">
            冻结前请先保存条目。冻结后该版本不可编辑，只能从最新冻结版派生新草稿。
          </div>
        </section>
      ) : (
        <section className="evaluation-snapshot-summary">
          <div><span>冻结时间</span><strong>{selectedVersion.frozenAt ? new Date(selectedVersion.frozenAt).toLocaleString('zh-CN') : '—'}</strong></div>
          <div><span>快照哈希</span><code>{selectedVersion.snapshotHash ?? '—'}</code></div>
        </section>
      )}

      <section className="evaluation-version-items" aria-labelledby="evaluation-version-items-heading">
        <div><h4 id="evaluation-version-items-heading">版本条目</h4><p>冻结版本可展开核对快照正文；草稿条目在冻结时生成快照。</p></div>
        {selectedVersion.items.length ? selectedVersion.items.map((item) => (
          <details key={item.caseId} className="evaluation-dataset-item">
            <summary>
              <span className="evaluation-order">{item.orderNo}</span>
              <span><strong>{item.snapshotName || item.caseId}</strong><small>{item.snapshotCategory || '草稿案例'}</small></span>
              {item.snapshotSeverity ? <em>{caseSeverityLabel(item.snapshotSeverity)}</em> : null}
            </summary>
            {item.snapshotInputText ? (
              <div className="evaluation-item-snapshot">
                <div><h5>输入快照</h5><pre>{item.snapshotInputText}</pre></div>
                <div><h5>期望快照</h5><pre>{item.snapshotExpectedJson}</pre></div>
                <code>内容哈希：{item.snapshotContentHash}</code>
              </div>
            ) : <p>此条目仍是草稿引用，冻结成功后才会保存内容快照。</p>}
          </details>
        )) : <div className="evaluation-empty evaluation-empty-compact"><strong>当前版本还没有条目</strong><p>从上方选择已审核案例并保存。</p></div>}
      </section>
    </div>
  );
}
