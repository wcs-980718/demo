export type JsonValue =
  | null
  | boolean
  | number
  | string
  | JsonValue[]
  | { [key: string]: JsonValue };

export type EvaluationOverview = {
  totalCases: number;
  reviewedCases: number;
  frozenVersions: number;
  runningRuns: number;
  latestTerminalPassRate: number;
  regressionCount: number;
};

export type EvaluationCaseSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type EvaluationCaseReviewStatus = 'DRAFT' | 'REVIEWED' | 'REJECTED';
export type EvaluationCaseLifecycleStatus = 'ACTIVE' | 'ARCHIVED';
export type EvaluationCaseSourceType = 'MANUAL' | 'KNOWLEDGE' | 'AGENT' | 'INDICATOR' | 'CLOSED_LOOP';
export type EvaluationEvaluatorType =
  | 'EXACT'
  | 'CONTAINS_ALL'
  | 'JSON_VALID'
  | 'NUMERIC_RANGE'
  | 'FORBIDDEN_TERMS'
  | 'MANUAL';

export type EvaluationCaseSummary = {
  id: string;
  platformId: string | null;
  name: string;
  category: string;
  severity: EvaluationCaseSeverity;
  sourceType: EvaluationCaseSourceType;
  sourceRef: string | null;
  evaluatorType: EvaluationEvaluatorType;
  reviewStatus: EvaluationCaseReviewStatus;
  lifecycleStatus: EvaluationCaseLifecycleStatus;
  createdAt: string;
  updatedAt: string;
  version: number;
};

export type EvaluationCaseDetail = EvaluationCaseSummary & {
  inputText: string;
  expected: JsonValue;
};

export type EvaluationCasePage = {
  items: EvaluationCaseSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type EvaluationCaseFilters = {
  platformId?: string;
  category?: string;
  severity?: EvaluationCaseSeverity;
  reviewStatus?: EvaluationCaseReviewStatus;
  lifecycleStatus?: EvaluationCaseLifecycleStatus;
  page?: number;
  size?: number;
};

export type EvaluationCasePayload = {
  platformId?: string | null;
  name: string;
  category: string;
  severity: EvaluationCaseSeverity;
  inputText: string;
  expected: JsonValue;
  evaluatorType: EvaluationEvaluatorType;
};

export type EvaluationCaseUpdatePayload = EvaluationCasePayload & {
  expectedVersion: number;
};

export type EvaluationCaseImportPayload = EvaluationCasePayload & {
  sourceType: Exclude<EvaluationCaseSourceType, 'MANUAL'>;
  sourceRef: string;
};

export type EvaluationCaseReviewPayload = {
  decision: 'APPROVE' | 'REJECT';
  expectedVersion: number;
};

export type ExpectedVersionPayload = {
  expectedVersion: number;
};

export type EvaluationDatasetVersionStatus = 'DRAFT' | 'FROZEN' | 'ARCHIVED';

export type EvaluationDatasetSummary = {
  id: string;
  name: string;
  description: string | null;
  version: number;
};

export type EvaluationDatasetItem = {
  caseId: string;
  orderNo: number;
  snapshotName: string | null;
  snapshotCategory: string | null;
  snapshotSeverity: EvaluationCaseSeverity | null;
  snapshotInputText: string | null;
  snapshotExpectedJson: string | null;
  snapshotEvaluatorType: EvaluationEvaluatorType | null;
  snapshotContentHash: string | null;
};

export type EvaluationDatasetVersion = {
  id: string;
  datasetId: string;
  versionNo: number;
  status: EvaluationDatasetVersionStatus;
  snapshotHash: string | null;
  frozenAt: string | null;
  version: number;
  items: EvaluationDatasetItem[];
};

export type EvaluationDatasetDetail = EvaluationDatasetSummary & {
  versions: EvaluationDatasetVersion[];
};

export type EvaluationDatasetPayload = {
  name: string;
  description?: string | null;
};

export type EvaluationDatasetItemsPayload = ExpectedVersionPayload & {
  caseIds: string[];
};

export type EvaluationRunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
export type EvaluationResultStatus = 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR' | 'CANCELLED';

export type EvaluationRunSummary = {
  id: string;
  sourceRunId: string | null;
  status: EvaluationRunStatus;
  version: number;
  totalCount: number;
  completedCount: number;
  passedCount: number;
  failedCount: number;
  errorCount: number;
  manualReviewCount: number;
  totalTokens: number;
  totalCost: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
  cancelRequested: boolean;
  startedAt: string | null;
  completedAt: string | null;
};

export type EvaluationRunPage = {
  content: EvaluationRunSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type EvaluationRunPayload = {
  datasetVersionId: string;
  platformId: string;
  modelId: string;
  promptId: string;
  temperature: number;
  maxTokens: number;
  timeoutMs: number;
};

export type EvaluationResult = {
  id: string;
  caseId: string;
  orderNo: number;
  snapshotName: string;
  snapshotCategory: string;
  snapshotSeverity: EvaluationCaseSeverity;
  input: string;
  expectedJson: string;
  evaluatorType: EvaluationEvaluatorType;
  actualOutput: string | null;
  score: number | null;
  passed: boolean | null;
  reviewRequired: boolean;
  reviewedAt: string | null;
  reviewSummary: string | null;
  status: EvaluationResultStatus;
  errorCode: string | null;
  errorSummary: string | null;
  traceSummaryJson: string | null;
  latencyMs: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  cost: number;
  attemptCount: number;
  version: number;
};

export type EvaluationResultReviewPayload = ExpectedVersionPayload & {
  score: number;
  passed: boolean;
};

export type EvaluationResultReview = {
  run: EvaluationRunSummary;
  result: EvaluationResult;
};

export type EvaluationComparisonCategory =
  | 'IMPROVED'
  | 'UNCHANGED'
  | 'REGRESSED'
  | 'ERROR'
  | 'REVIEW_REQUIRED';

export type EvaluationCaseComparison = {
  resultId: string;
  caseId: string;
  name: string;
  severity: EvaluationCaseSeverity;
  category: EvaluationComparisonCategory;
  candidateOutputSummary: string;
};

export type EvaluationExperimentComparison = {
  baselineRunId: string;
  candidateRunId: string;
  qualityAvailable: boolean;
  qualityDelta: number | null;
  costDelta: number;
  averageLatencyDeltaMs: number;
  p95LatencyDeltaMs: number;
  cases: EvaluationCaseComparison[];
};

export type EvaluationGateConclusion = 'PASS' | 'FAIL' | 'REVIEW_REQUIRED';

export type EvaluationGatePolicyPayload = {
  name: string;
  platformId: string | null;
  category: string | null;
  minPassRate: number;
  maxCostGrowthPercent: number;
  maxAverageLatencyMs: number;
  maxP95LatencyMs: number;
  requireCriticalCasesPassed: boolean;
  enabled: boolean;
};

export type EvaluationGatePolicyUpdatePayload = EvaluationGatePolicyPayload & {
  expectedVersion: number;
};

export type EvaluationGatePolicy = EvaluationGatePolicyPayload & {
  id: string;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type EvaluationGateDecision = {
  id: string;
  runId: string;
  policyId: string;
  policyVersion: number;
  conclusion: EvaluationGateConclusion;
  evidence: JsonValue;
  decidedAt: string;
};

export type EvaluationGateDecisionPayload = {
  baselineRunId: string;
  policyId: string;
};
