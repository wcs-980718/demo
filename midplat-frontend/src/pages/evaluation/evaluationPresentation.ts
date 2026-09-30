import type {
  EvaluationCaseFilters,
  EvaluationCaseLifecycleStatus,
  EvaluationCasePayload,
  EvaluationCaseReviewStatus,
  EvaluationCaseSeverity,
  EvaluationComparisonCategory,
  EvaluationCaseUpdatePayload,
  EvaluationDatasetVersionStatus,
  EvaluationEvaluatorType,
  EvaluationGateConclusion,
  EvaluationGatePolicyPayload,
  EvaluationResultStatus,
  EvaluationRunStatus,
} from './evaluationTypes';

const CASE_REVIEW_STATUS_LABELS: Record<EvaluationCaseReviewStatus, string> = {
  DRAFT: '草稿',
  REVIEWED: '已审核',
  REJECTED: '已驳回',
};

const CASE_LIFECYCLE_STATUS_LABELS: Record<EvaluationCaseLifecycleStatus, string> = {
  ACTIVE: '启用中',
  ARCHIVED: '已归档',
};

const DATASET_VERSION_STATUS_LABELS: Record<EvaluationDatasetVersionStatus, string> = {
  DRAFT: '草稿',
  FROZEN: '已冻结',
  ARCHIVED: '已归档',
};

const CASE_SEVERITY_META: Record<EvaluationCaseSeverity, { label: string; rank: number }> = {
  CRITICAL: { label: '关键', rank: 0 },
  HIGH: { label: '严重', rank: 1 },
  MEDIUM: { label: '中等', rank: 2 },
  LOW: { label: '低风险', rank: 3 },
};

const RUN_STATUS_LABELS: Record<EvaluationRunStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  COMPLETED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
};

const RESULT_STATUS_LABELS: Record<EvaluationResultStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  PASSED: '通过',
  FAILED: '未通过',
  ERROR: '执行错误',
  CANCELLED: '已取消',
};

export type CaseFormValues = {
  platformId?: string;
  name: string;
  category: string;
  severity: EvaluationCaseSeverity;
  inputText: string;
  expectedText: string;
  evaluatorType: EvaluationEvaluatorType;
};

export type CaseListUiState = Omit<EvaluationCaseFilters, 'page' | 'size'> & {
  page: number;
  pageSize: number;
};

export type EvaluationEmptyStateKind = 'cases' | 'caseFilters' | 'datasets' | 'overviewRuns';

export type GatePolicyFormValues = EvaluationGatePolicyPayload;

type SemanticMeta = {
  label: string;
  icon: string;
  tone: 'positive' | 'negative' | 'warning' | 'neutral';
};

const COMPARISON_CATEGORY_META: Record<EvaluationComparisonCategory, SemanticMeta> = {
  IMPROVED: { label: '已改进', icon: 'trending-up', tone: 'positive' },
  UNCHANGED: { label: '无变化', icon: 'minus', tone: 'neutral' },
  REGRESSED: { label: '已退化', icon: 'trending-down', tone: 'negative' },
  ERROR: { label: '执行错误', icon: 'circle-alert', tone: 'negative' },
  REVIEW_REQUIRED: { label: '待人工复核', icon: 'user-check', tone: 'warning' },
};

const GATE_CONCLUSION_META: Record<EvaluationGateConclusion, SemanticMeta> = {
  PASS: { label: '通过门禁', icon: 'shield-check', tone: 'positive' },
  FAIL: { label: '阻断发布', icon: 'shield-x', tone: 'negative' },
  REVIEW_REQUIRED: { label: '待人工复核', icon: 'shield-alert', tone: 'warning' },
};

export function caseReviewStatusLabel(status: EvaluationCaseReviewStatus): string {
  return CASE_REVIEW_STATUS_LABELS[status];
}

export function caseLifecycleStatusLabel(status: EvaluationCaseLifecycleStatus): string {
  return CASE_LIFECYCLE_STATUS_LABELS[status];
}

export function datasetVersionStatusLabel(status: EvaluationDatasetVersionStatus): string {
  return DATASET_VERSION_STATUS_LABELS[status];
}

export function caseSeverityLabel(severity: EvaluationCaseSeverity): string {
  return CASE_SEVERITY_META[severity].label;
}

export function compareCaseSeverity(
  left: EvaluationCaseSeverity,
  right: EvaluationCaseSeverity,
): number {
  return CASE_SEVERITY_META[left].rank - CASE_SEVERITY_META[right].rank;
}

function optionalText(value: string | undefined): string | undefined {
  const normalized = value?.trim();
  return normalized ? normalized : undefined;
}

export function buildCaseListParams(state: CaseListUiState): EvaluationCaseFilters {
  const params: EvaluationCaseFilters = {
    page: Math.max(0, state.page - 1),
    size: state.pageSize,
  };
  const platformId = optionalText(state.platformId);
  const category = optionalText(state.category);
  if (platformId) params.platformId = platformId;
  if (category) params.category = category;
  if (state.severity) params.severity = state.severity;
  if (state.reviewStatus) params.reviewStatus = state.reviewStatus;
  if (state.lifecycleStatus) params.lifecycleStatus = state.lifecycleStatus;
  return params;
}

export function toCasePayload(values: CaseFormValues): EvaluationCasePayload {
  let expected: EvaluationCasePayload['expected'];
  try {
    expected = JSON.parse(values.expectedText) as EvaluationCasePayload['expected'];
  } catch {
    throw new Error('期望结果必须是合法 JSON');
  }
  return {
    platformId: optionalText(values.platformId) ?? null,
    name: values.name.trim(),
    category: values.category.trim(),
    severity: values.severity,
    inputText: values.inputText,
    expected,
    evaluatorType: values.evaluatorType,
  };
}

export function toCaseUpdatePayload(
  values: CaseFormValues,
  expectedVersion: number,
): EvaluationCaseUpdatePayload {
  return { ...toCasePayload(values), expectedVersion };
}

export function canEditDatasetVersion(status: EvaluationDatasetVersionStatus): boolean {
  return status === 'DRAFT';
}

export function formatDatasetVersion(version: {
  versionNo: number;
  status: EvaluationDatasetVersionStatus;
}): string {
  return `V${version.versionNo} · ${datasetVersionStatusLabel(version.status)}`;
}

const EMPTY_STATES: Record<EvaluationEmptyStateKind, {
  title: string;
  description: string;
  actionLabel: string;
}> = {
  cases: {
    title: '还没有评测案例',
    description: '先创建一个可审核、可进入评测集的标准案例。',
    actionLabel: '创建首个案例',
  },
  caseFilters: {
    title: '没有匹配的案例',
    description: '当前筛选条件没有结果，可以清除筛选后再试。',
    actionLabel: '清除筛选',
  },
  datasets: {
    title: '还没有评测集',
    description: '创建评测集，把已审核案例组织成可冻结版本。',
    actionLabel: '创建首个评测集',
  },
  overviewRuns: {
    title: '还没有运行历史',
    description: '冻结一个评测集版本后，即可创建首次评测任务。',
    actionLabel: '去创建评测任务',
  },
};

export function getEvaluationEmptyState(kind: EvaluationEmptyStateKind) {
  return { ...EMPTY_STATES[kind] };
}

export function evaluationMutationErrorMessage(error: unknown): string {
  if (!(error instanceof Error) || !error.message) {
    return '操作失败，请稍后重试。';
  }
  if (/版本.*(?:变化|冲突)|\b(?:version|conflict)\b/i.test(error.message)) {
    return '数据版本已变化，请刷新后重试。';
  }
  return error.message;
}

export function formatOverviewPassRate(value: number): string {
  return `${value.toFixed(2)}%`;
}

export function resetEvaluationFormWhenOpened(open: boolean, resetFields: () => void): void {
  if (open) resetFields();
}

export function evaluationAvailabilityMeta() {
  return {
    label: '开发中',
    productionReady: false,
    notice: '功能开发中，仅供内部验证。当前评测结果和门禁结论不得作为正式生产发布依据。',
  } as const;
}

function isEvaluationFormValidationError(error: unknown): error is { errorFields: unknown[] } {
  return typeof error === 'object'
    && error !== null
    && Array.isArray((error as { errorFields?: unknown }).errorFields);
}

export async function submitValidatedEvaluationForm<T>(
  validateFields: () => Promise<T>,
  onValid: (values: T) => void,
): Promise<void> {
  let values: T;
  try {
    values = await validateFields();
  } catch (error) {
    if (isEvaluationFormValidationError(error)) return;
    throw error;
  }
  onValid(values);
}

export function runStatusLabel(status: EvaluationRunStatus): string {
  return RUN_STATUS_LABELS[status];
}

export function resultStatusLabel(status: EvaluationResultStatus): string {
  return RESULT_STATUS_LABELS[status];
}

export function formatRunProgress(completedCount: number, totalCount: number): string {
  if (totalCount <= 0) return '0%';
  const percentage = Math.round((Math.max(0, completedCount) / totalCount) * 100);
  return `${Math.min(100, percentage)}%`;
}

export function formatTokenCount(value: number): string {
  return `${Math.max(0, Math.trunc(value)).toLocaleString('en-US')} Token`;
}

export function formatRunCost(value: number, currency = 'CNY'): string {
  const amount = Math.max(0, value).toFixed(8);
  return currency === 'CNY' ? `¥${amount}` : `${currency} ${amount}`;
}

export function formatLatency(value: number): string {
  return `${Math.max(0, Math.round(value))} ms`;
}

export function formatLatencySummary(averageMs: number, p95Ms: number): string {
  return `平均 ${formatLatency(averageMs)} · P95 ${formatLatency(p95Ms)}`;
}

export function runPollInterval(status: EvaluationRunStatus): 2000 | false {
  return status === 'QUEUED' || status === 'RUNNING' ? 2_000 : false;
}

export function comparisonCategoryMeta(category: EvaluationComparisonCategory): SemanticMeta {
  return { ...COMPARISON_CATEGORY_META[category] };
}

function signed(value: number, digits: number): string {
  if (value === 0) return value.toFixed(digits);
  return `${value > 0 ? '+' : '-'}${Math.abs(value).toFixed(digits)}`;
}

export function formatQualityDelta(value: number | null): string {
  return value == null ? '待人工复核' : `${signed(value, 2)} 个百分点`;
}

export function formatCostDelta(value: number): string {
  const amount = Math.abs(value).toFixed(8);
  return value === 0 ? `¥${amount}` : `${value > 0 ? '+' : '-'}¥${amount}`;
}

export function formatLatencyDelta(value: number): string {
  return `${value > 0 ? '+' : ''}${Math.round(value)} ms`;
}

export function toGatePolicyPayload(values: GatePolicyFormValues): EvaluationGatePolicyPayload {
  return {
    name: values.name.trim(),
    platformId: optionalText(values.platformId ?? undefined) ?? null,
    category: optionalText(values.category ?? undefined) ?? null,
    minPassRate: values.minPassRate,
    maxCostGrowthPercent: values.maxCostGrowthPercent,
    maxAverageLatencyMs: values.maxAverageLatencyMs,
    maxP95LatencyMs: values.maxP95LatencyMs,
    requireCriticalCasesPassed: values.requireCriticalCasesPassed,
    enabled: values.enabled,
  };
}

export function gateConclusionMeta(conclusion: EvaluationGateConclusion): SemanticMeta {
  return { ...GATE_CONCLUSION_META[conclusion] };
}
