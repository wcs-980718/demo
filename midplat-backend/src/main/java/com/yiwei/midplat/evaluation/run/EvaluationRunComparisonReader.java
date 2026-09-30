package com.yiwei.midplat.evaluation.run;

import java.math.BigDecimal;
import java.util.List;

/** Read-only, body-free projection used by experiment and gate calculations. */
public interface EvaluationRunComparisonReader {
    RunComparisonSnapshot find(String runId);

    record RunComparisonSnapshot(String runId, String platformId, EvaluationRunStatus status, long version,
            String datasetSnapshotHash, boolean qualityAvailable,
            BigDecimal qualityRate, BigDecimal totalCost, long averageLatencyMs, long p95LatencyMs,
            List<ResultComparisonSnapshot> results) {
        public RunComparisonSnapshot(String runId, String platformId, EvaluationRunStatus status,
                String datasetSnapshotHash, boolean qualityAvailable, BigDecimal qualityRate, BigDecimal totalCost,
                long averageLatencyMs, long p95LatencyMs, List<ResultComparisonSnapshot> results) {
            this(runId, platformId, status, 0, datasetSnapshotHash, qualityAvailable, qualityRate, totalCost,
                    averageLatencyMs, p95LatencyMs, results);
        }

        public RunComparisonSnapshot(String runId, EvaluationRunStatus status, String datasetSnapshotHash,
                boolean qualityAvailable, BigDecimal qualityRate, BigDecimal totalCost,
                long averageLatencyMs, long p95LatencyMs, List<ResultComparisonSnapshot> results) {
            this(runId, null, status, 0, datasetSnapshotHash, qualityAvailable, qualityRate, totalCost,
                    averageLatencyMs, p95LatencyMs, results);
        }
    }

    record ResultComparisonSnapshot(String resultId, String caseId, int orderNo, long version,
            String name, String severity, String snapshotCategory,
            String snapshotContentHash, EvaluationResultStatus status, boolean reviewRequired,
            BigDecimal score, String outputSummary) {
        public ResultComparisonSnapshot(String resultId, String caseId, String name, String severity,
                String snapshotCategory, String snapshotContentHash, EvaluationResultStatus status,
                boolean reviewRequired, BigDecimal score, String outputSummary) {
            this(resultId, caseId, 0, 0, name, severity, snapshotCategory, snapshotContentHash, status,
                    reviewRequired, score, outputSummary);
        }

        public ResultComparisonSnapshot(String resultId, String caseId, String name, String severity,
                String snapshotContentHash, EvaluationResultStatus status, boolean reviewRequired,
                BigDecimal score, String outputSummary) {
            this(resultId, caseId, 0, 0, name, severity, null, snapshotContentHash, status,
                    reviewRequired, score, outputSummary);
        }
    }

    interface RunRow {
        String getRunId(); String getPlatformId(); EvaluationRunStatus getStatus();
        long getVersion();
        int getPassedCount(); int getFailedCount(); BigDecimal getTotalCost();
        long getAverageLatencyMs(); long getP95LatencyMs();
    }
    interface ResultRow {
        String getResultId(); String getCaseId(); int getOrderNo(); String getName(); String getSeverity();
        long getVersion();
        String getSnapshotCategory(); String getSnapshotContentHash();
        EvaluationResultStatus getStatus(); boolean getReviewRequired(); BigDecimal getScore();
    }
}
