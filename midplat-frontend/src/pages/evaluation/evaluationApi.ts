import axios, { type AxiosResponse } from 'axios';
import { getMidplatApiBaseURL } from '@/api/apiBaseURL';
import type {
  EvaluationCaseDetail,
  EvaluationCaseFilters,
  EvaluationCaseImportPayload,
  EvaluationCasePage,
  EvaluationCasePayload,
  EvaluationCaseReviewPayload,
  EvaluationCaseUpdatePayload,
  EvaluationDatasetDetail,
  EvaluationDatasetItemsPayload,
  EvaluationDatasetPayload,
  EvaluationDatasetSummary,
  EvaluationDatasetVersion,
  EvaluationExperimentComparison,
  EvaluationGateDecision,
  EvaluationGateDecisionPayload,
  EvaluationGatePolicy,
  EvaluationGatePolicyPayload,
  EvaluationGatePolicyUpdatePayload,
  EvaluationOverview,
  EvaluationResult,
  EvaluationResultReview,
  EvaluationResultReviewPayload,
  EvaluationRunPage,
  EvaluationRunPayload,
  EvaluationRunSummary,
  ExpectedVersionPayload,
} from './evaluationTypes';

type ApiResponse<T> = {
  success: boolean;
  data: T;
  message: string | null;
};

type ProblemPayload = {
  detail?: unknown;
  message?: unknown;
};

const evaluationClient = axios.create();

evaluationClient.interceptors.request.use((config) => {
  config.baseURL = getMidplatApiBaseURL();
  return config;
});

evaluationClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError<ProblemPayload>(error)) {
      const detail = error.response?.data?.detail ?? error.response?.data?.message ?? error.message;
      return Promise.reject(new Error(typeof detail === 'string' && detail ? detail : '请求失败'));
    }
    return Promise.reject(new Error(error instanceof Error && error.message ? error.message : '请求失败'));
  },
);

async function unwrap<T>(promise: Promise<AxiosResponse<ApiResponse<T>>>): Promise<T> {
  const response = await promise;
  return response.data.data;
}

function segment(value: string): string {
  return encodeURIComponent(value);
}

export const evaluationApi = {
  getOverview: () =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationOverview>>('/evaluation/overview')),

  listCases: (params: EvaluationCaseFilters = {}) =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationCasePage>>('/evaluation/cases', { params })),
  getCase: (id: string) =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationCaseDetail>>(`/evaluation/cases/${segment(id)}`)),
  createCase: (payload: EvaluationCasePayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationCaseDetail>>('/evaluation/cases', payload)),
  importCase: (payload: EvaluationCaseImportPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationCaseDetail>>('/evaluation/cases/import', payload)),
  updateCase: (id: string, payload: EvaluationCaseUpdatePayload) =>
    unwrap(evaluationClient.patch<ApiResponse<EvaluationCaseDetail>>(`/evaluation/cases/${segment(id)}`, payload)),
  reviewCase: (id: string, payload: EvaluationCaseReviewPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationCaseDetail>>(`/evaluation/cases/${segment(id)}/review`, payload)),
  archiveCase: (id: string, payload: ExpectedVersionPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationCaseDetail>>(`/evaluation/cases/${segment(id)}/archive`, payload)),

  listDatasets: () =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationDatasetSummary[]>>('/evaluation/datasets')),
  getDataset: (id: string) =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationDatasetDetail>>(`/evaluation/datasets/${segment(id)}`)),
  createDataset: (payload: EvaluationDatasetPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationDatasetDetail>>('/evaluation/datasets', payload)),
  deriveDatasetVersion: (datasetId: string, payload: ExpectedVersionPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationDatasetVersion>>(
      `/evaluation/datasets/${segment(datasetId)}/versions`,
      payload,
    )),
  replaceDatasetVersionItems: (versionId: string, payload: EvaluationDatasetItemsPayload) =>
    unwrap(evaluationClient.put<ApiResponse<EvaluationDatasetVersion>>(
      `/evaluation/dataset-versions/${segment(versionId)}/items`,
      payload,
    )),
  freezeDatasetVersion: (versionId: string, payload: ExpectedVersionPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationDatasetVersion>>(
      `/evaluation/dataset-versions/${segment(versionId)}/freeze`,
      payload,
    )),

  listRuns: (params: { page?: number; size?: number } = {}) =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationRunPage>>('/evaluation/runs', { params })),
  getRun: (id: string) =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationRunSummary>>(`/evaluation/runs/${segment(id)}`)),
  createRun: (payload: EvaluationRunPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationRunSummary>>('/evaluation/runs', payload)),
  listRunResults: (runId: string) =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationResult[]>>(`/evaluation/runs/${segment(runId)}/results`)),
  cancelRun: (id: string, payload: ExpectedVersionPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationRunSummary>>(`/evaluation/runs/${segment(id)}/cancel`, payload)),
  retryRunErrors: (id: string, payload: ExpectedVersionPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationRunSummary>>(
      `/evaluation/runs/${segment(id)}/retry-errors`,
      payload,
    )),
  reviewRunResult: (runId: string, resultId: string, payload: EvaluationResultReviewPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationResultReview>>(
      `/evaluation/runs/${segment(runId)}/results/${segment(resultId)}/review`,
      payload,
    )),

  compareRuns: (baselineRunId: string, candidateRunId: string) =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationExperimentComparison>>('/evaluation/experiments/compare', {
      params: { baselineRunId, candidateRunId },
    })),

  listGatePolicies: () =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationGatePolicy[]>>('/evaluation/gates')),
  createGatePolicy: (payload: EvaluationGatePolicyPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationGatePolicy>>('/evaluation/gates', payload)),
  updateGatePolicy: (id: string, payload: EvaluationGatePolicyUpdatePayload) =>
    unwrap(evaluationClient.patch<ApiResponse<EvaluationGatePolicy>>(`/evaluation/gates/${segment(id)}`, payload)),
  listGateDecisions: (runId: string) =>
    unwrap(evaluationClient.get<ApiResponse<EvaluationGateDecision[]>>('/evaluation/gate-decisions', {
      params: { runId },
    })),
  createGateDecision: (runId: string, payload: EvaluationGateDecisionPayload) =>
    unwrap(evaluationClient.post<ApiResponse<EvaluationGateDecision>>(
      `/evaluation/runs/${segment(runId)}/gate-decision`,
      payload,
    )),
} as const;
