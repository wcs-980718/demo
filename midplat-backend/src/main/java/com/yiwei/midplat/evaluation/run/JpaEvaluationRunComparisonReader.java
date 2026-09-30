package com.yiwei.midplat.evaluation.run;

import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.evaluation.dataset.DatasetSnapshotHasher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class JpaEvaluationRunComparisonReader implements EvaluationRunComparisonReader {
    private final EvaluationRunRepository runs;
    private final EvaluationResultRepository results;

    JpaEvaluationRunComparisonReader(EvaluationRunRepository runs, EvaluationResultRepository results) {
        this.runs = runs;
        this.results = results;
    }

    @Override
    public RunComparisonSnapshot find(String runId) {
        EvaluationRunComparisonReader.RunRow run = runs.findComparisonRowById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("evaluation run not found"));
        List<EvaluationRunComparisonReader.ResultRow> rows = results.findComparisonRowsByRunId(runId).stream()
                .sorted(Comparator.comparingInt(EvaluationRunComparisonReader.ResultRow::getOrderNo))
                .toList();
        List<ResultComparisonSnapshot> snapshots = rows.stream()
                .map(this::result).toList();
        int decided = run.getPassedCount() + run.getFailedCount();
        BigDecimal quality = decided == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(run.getPassedCount()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(decided), 4, RoundingMode.HALF_UP);
        String frozenSnapshotHash = DatasetSnapshotHasher.hash(rows.stream()
                .map(row -> new DatasetSnapshotHasher.Entry(row.getCaseId(), row.getSnapshotContentHash())).toList());
        return new RunComparisonSnapshot(run.getRunId(), run.getPlatformId(), run.getStatus(), run.getVersion(),
                frozenSnapshotHash, decided > 0, quality, run.getTotalCost(), run.getAverageLatencyMs(),
                run.getP95LatencyMs(), snapshots);
    }

    private ResultComparisonSnapshot result(EvaluationRunComparisonReader.ResultRow result) {
        return new ResultComparisonSnapshot(result.getResultId(), result.getCaseId(), result.getOrderNo(),
                result.getVersion(), result.getName(), result.getSeverity(), result.getSnapshotCategory(),
                result.getSnapshotContentHash(), result.getStatus(), result.getReviewRequired(), result.getScore(),
                outputSummary());
    }

    private static String outputSummary() { return "输出已脱敏"; }
}
