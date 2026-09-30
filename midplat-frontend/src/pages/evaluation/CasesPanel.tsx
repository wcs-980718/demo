import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Input, Pagination, Select, Spin, Table, type TableProps, message } from 'antd';
import { Eye, FilterX, ListChecks, Plus, RefreshCw } from 'lucide-react';
import { midplatApi } from '@/api/midplatApi';
import { CaseDetailDrawer } from './CaseDetailDrawer';
import { CaseFormModal } from './CaseFormModal';
import { evaluationApi } from './evaluationApi';
import {
  buildCaseListParams,
  caseLifecycleStatusLabel,
  caseReviewStatusLabel,
  caseSeverityLabel,
  compareCaseSeverity,
  evaluationMutationErrorMessage,
  getEvaluationEmptyState,
  toCasePayload,
  toCaseUpdatePayload,
  type CaseFormValues,
  type CaseListUiState,
} from './evaluationPresentation';
import type { EvaluationCaseDetail, EvaluationCaseSummary } from './evaluationTypes';

const INITIAL_FILTERS: CaseListUiState = {
  page: 1,
  pageSize: 20,
  lifecycleStatus: 'ACTIVE',
};

export function CasesPanel() {
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<CaseListUiState>(INITIAL_FILTERS);
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [editingCase, setEditingCase] = useState<EvaluationCaseDetail | null>(null);
  const params = useMemo(() => buildCaseListParams(filters), [filters]);
  const casesQuery = useQuery({
    queryKey: ['evaluation', 'cases', params],
    queryFn: () => evaluationApi.listCases(params),
    placeholderData: (previous) => previous,
  });
  const platformsQuery = useQuery({
    queryKey: ['platforms'],
    queryFn: midplatApi.listPlatforms,
  });

  const saveMutation = useMutation({
    mutationFn: ({ values, editing }: { values: CaseFormValues; editing: EvaluationCaseDetail | null }) =>
      editing
        ? evaluationApi.updateCase(editing.id, toCaseUpdatePayload(values, editing.version))
        : evaluationApi.createCase(toCasePayload(values)),
    onSuccess: async (saved, variables) => {
      message.success(variables.editing ? '案例草稿已更新' : '评测案例已创建');
      setFormOpen(false);
      setEditingCase(null);
      setSelectedCaseId(saved.id);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['evaluation', 'cases'] }),
        queryClient.invalidateQueries({ queryKey: ['evaluation', 'case', saved.id] }),
        queryClient.invalidateQueries({ queryKey: ['evaluation', 'overview'] }),
      ]);
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });

  const updateFilter = <Key extends keyof CaseListUiState>(key: Key, value: CaseListUiState[Key]) => {
    setFilters((current) => ({ ...current, [key]: value, page: key === 'page' ? Number(value) : 1 }));
  };
  const hasFilters = Boolean(
    filters.platformId || filters.category || filters.severity || filters.reviewStatus
      || (filters.lifecycleStatus && filters.lifecycleStatus !== 'ACTIVE'),
  );
  const openCreate = () => {
    setEditingCase(null);
    setFormOpen(true);
  };

  const columns: TableProps<EvaluationCaseSummary>['columns'] = [
    {
      title: '案例',
      dataIndex: 'name',
      key: 'name',
      render: (_, row) => (
        <div className="evaluation-table-title">
          <strong>{row.name}</strong>
          <span>{row.category}</span>
        </div>
      ),
    },
    {
      title: '严重级别',
      dataIndex: 'severity',
      key: 'severity',
      sorter: (left, right) => compareCaseSeverity(left.severity, right.severity),
      render: (severity: EvaluationCaseSummary['severity']) => (
        <span className={`evaluation-severity severity-${severity.toLowerCase()}`}>{caseSeverityLabel(severity)}</span>
      ),
    },
    {
      title: '复核状态',
      dataIndex: 'reviewStatus',
      key: 'reviewStatus',
      render: (status: EvaluationCaseSummary['reviewStatus']) => (
        <span className={`evaluation-status review-${status.toLowerCase()}`}>{caseReviewStatusLabel(status)}</span>
      ),
    },
    {
      title: '生命周期',
      dataIndex: 'lifecycleStatus',
      key: 'lifecycleStatus',
      render: (status: EvaluationCaseSummary['lifecycleStatus']) => (
        <span className={`evaluation-status lifecycle-${status.toLowerCase()}`}>{caseLifecycleStatusLabel(status)}</span>
      ),
    },
    {
      title: '评分方式',
      dataIndex: 'evaluatorType',
      key: 'evaluatorType',
      render: (value: string) => <span className="evaluation-mono">{value}</span>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 112,
      render: (_, row) => (
        <Button icon={<Eye size={15} />} onClick={() => setSelectedCaseId(row.id)}>查看详情</Button>
      ),
    },
  ];

  return (
    <section className="evaluation-section" aria-labelledby="evaluation-cases-heading">
      <div className="evaluation-section-head">
        <div>
          <p className="evaluation-kicker">CASE CENTER</p>
          <h3 id="evaluation-cases-heading">案例中心</h3>
          <p>列表只展示元数据；输入和期望正文仅在主动打开详情后请求。</p>
        </div>
        <Button type="primary" icon={<Plus size={16} />} onClick={openCreate}>创建案例</Button>
      </div>
      <div className="evaluation-filters" aria-label="案例筛选条件">
        <label>
          <span>所属平台</span>
          <Select
            aria-label="按所属平台筛选"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="全部平台"
            value={filters.platformId}
            loading={platformsQuery.isLoading}
            options={(platformsQuery.data ?? []).map((platform) => ({ value: platform.id, label: platform.name }))}
            onChange={(value) => updateFilter('platformId', value)}
          />
        </label>
        <label>
          <span>业务分类</span>
          <Input
            aria-label="按业务分类筛选"
            allowClear
            placeholder="输入完整分类"
            value={filters.category}
            onChange={(event) => updateFilter('category', event.target.value)}
          />
        </label>
        <label>
          <span>严重级别</span>
          <Select
            aria-label="按严重级别筛选"
            allowClear
            placeholder="全部级别"
            value={filters.severity}
            options={[
              { value: 'CRITICAL', label: '关键' },
              { value: 'HIGH', label: '严重' },
              { value: 'MEDIUM', label: '中等' },
              { value: 'LOW', label: '低风险' },
            ]}
            onChange={(value) => updateFilter('severity', value)}
          />
        </label>
        <label>
          <span>复核状态</span>
          <Select
            aria-label="按复核状态筛选"
            allowClear
            placeholder="全部状态"
            value={filters.reviewStatus}
            options={[
              { value: 'DRAFT', label: '草稿' },
              { value: 'REVIEWED', label: '已审核' },
              { value: 'REJECTED', label: '已驳回' },
            ]}
            onChange={(value) => updateFilter('reviewStatus', value)}
          />
        </label>
        <label>
          <span>生命周期</span>
          <Select
            aria-label="按生命周期筛选"
            value={filters.lifecycleStatus}
            options={[
              { value: 'ACTIVE', label: '启用中' },
              { value: 'ARCHIVED', label: '已归档' },
            ]}
            onChange={(value) => updateFilter('lifecycleStatus', value)}
          />
        </label>
        <Button
          icon={<FilterX size={15} />}
          disabled={!hasFilters}
          onClick={() => setFilters(INITIAL_FILTERS)}
        >
          清除筛选
        </Button>
      </div>

      {casesQuery.isLoading ? (
        <div className="evaluation-loading" aria-label="正在加载案例"><Spin size="large" /></div>
      ) : casesQuery.isError ? (
        <div className="evaluation-error" role="alert">
          <div><strong>案例列表加载失败</strong><p>筛选条件已保留，可以直接重试。</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => void casesQuery.refetch()}>重新加载</Button>
        </div>
      ) : casesQuery.data?.items.length ? (
        <>
          <div className="evaluation-table-wrap">
            <Table<EvaluationCaseSummary>
              rowKey="id"
              className="surface-table"
              columns={columns}
              dataSource={casesQuery.data.items}
              pagination={false}
              loading={casesQuery.isFetching}
            />
          </div>
          <div className="evaluation-mobile-list">
            {casesQuery.data.items.map((row) => (
              <article className="evaluation-mobile-card" key={row.id}>
                <div className="evaluation-mobile-card-head">
                  <div><strong>{row.name}</strong><span>{row.category}</span></div>
                  <span className={`evaluation-severity severity-${row.severity.toLowerCase()}`}>{caseSeverityLabel(row.severity)}</span>
                </div>
                <div className="evaluation-mobile-meta">
                  <span className={`evaluation-status review-${row.reviewStatus.toLowerCase()}`}>{caseReviewStatusLabel(row.reviewStatus)}</span>
                  <span className={`evaluation-status lifecycle-${row.lifecycleStatus.toLowerCase()}`}>{caseLifecycleStatusLabel(row.lifecycleStatus)}</span>
                  <span className="evaluation-mono">{row.evaluatorType}</span>
                </div>
                <Button icon={<Eye size={15} />} onClick={() => setSelectedCaseId(row.id)}>查看详情</Button>
              </article>
            ))}
          </div>
          <Pagination
            className="evaluation-pagination"
            current={filters.page}
            pageSize={filters.pageSize}
            total={casesQuery.data.totalElements}
            showSizeChanger
            pageSizeOptions={[10, 20, 50, 100]}
            showTotal={(total) => `共 ${total} 个案例`}
            onChange={(page, pageSize) => setFilters((current) => ({ ...current, page, pageSize }))}
          />
        </>
      ) : (
        <CasesEmpty filtered={hasFilters} onCreate={openCreate} onClear={() => setFilters(INITIAL_FILTERS)} />
      )}

      <CaseDetailDrawer
        caseId={selectedCaseId}
        onClose={() => setSelectedCaseId(null)}
        onEdit={(detail) => {
          setEditingCase(detail);
          setFormOpen(true);
        }}
      />
      <CaseFormModal
        open={formOpen}
        editing={editingCase}
        platforms={platformsQuery.data ?? []}
        submitting={saveMutation.isPending}
        onCancel={() => {
          if (saveMutation.isPending) return;
          setFormOpen(false);
          setEditingCase(null);
        }}
        onSubmit={(values) => saveMutation.mutate({ values, editing: editingCase })}
      />
    </section>
  );
}

function CasesEmpty({
  filtered,
  onCreate,
  onClear,
}: {
  filtered: boolean;
  onCreate: () => void;
  onClear: () => void;
}) {
  const empty = getEvaluationEmptyState(filtered ? 'caseFilters' : 'cases');
  return (
    <div className="evaluation-empty">
      <ListChecks size={26} aria-hidden="true" />
      <div><strong>{empty.title}</strong><p>{empty.description}</p></div>
      <Button type="primary" onClick={filtered ? onClear : onCreate}>{empty.actionLabel}</Button>
    </div>
  );
}
