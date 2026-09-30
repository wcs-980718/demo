package com.yiwei.midplat.evaluation.experiment;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yiwei.midplat.evaluation.run.EvaluationResultStatus;
import com.yiwei.midplat.evaluation.run.EvaluationRunComparisonReader;
import com.yiwei.midplat.evaluation.run.EvaluationRunDecisionStateFingerprint;
import com.yiwei.midplat.evaluation.run.EvaluationRunStatus;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationExperimentService {
    private final EvaluationRunComparisonReader reader;

    public EvaluationExperimentService(EvaluationRunComparisonReader reader) { this.reader = reader; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Comparison compare(String baselineRunId, String candidateRunId) {
        if (baselineRunId == null || candidateRunId == null || baselineRunId.isBlank() || candidateRunId.isBlank()
                || baselineRunId.length() > 64 || candidateRunId.length() > 64 || baselineRunId.equals(candidateRunId)) throw new IllegalArgumentException("实验比较请求无效");
        var baseline = reader.find(baselineRunId);
        var candidate = reader.find(candidateRunId);
        requireTerminal(baseline.status());
        requireTerminal(candidate.status());
        if (!baseline.datasetSnapshotHash().equals(candidate.datasetSnapshotHash())) throw new SnapshotConflictException();
        Map<String, EvaluationRunComparisonReader.ResultComparisonSnapshot> baselineByCase = index(baseline.results());
        Map<String, EvaluationRunComparisonReader.ResultComparisonSnapshot> candidateByCase = index(candidate.results());
        if (!baselineByCase.keySet().equals(candidateByCase.keySet())) throw new AlignmentConflictException();
        List<CaseComparison> cases = baselineByCase.entrySet().stream().map(entry -> {
            var left = entry.getValue();
            var right = candidateByCase.get(entry.getKey());
            if (!left.snapshotContentHash().equals(right.snapshotContentHash())) throw new AlignmentConflictException();
            return new CaseComparison(right.resultId(), right.caseId(), right.name(), right.severity(), classify(left, right),
                    right.outputSummary(), right.status(), right.reviewRequired(), right.snapshotCategory());
        }).sorted(caseOrder()).toList();
        cases.forEach(value -> severity(value.severity()));
        boolean qualityAvailable = baseline.qualityAvailable() && candidate.qualityAvailable();
        return new Comparison(baseline.runId(), candidate.runId(), qualityAvailable,
                qualityAvailable ? candidate.qualityRate().subtract(baseline.qualityRate()) : null,
                candidate.totalCost().subtract(baseline.totalCost()), Math.subtractExact(candidate.averageLatencyMs(), baseline.averageLatencyMs()),
                Math.subtractExact(candidate.p95LatencyMs(), baseline.p95LatencyMs()), cases,
                metrics(baseline), metrics(candidate), EvaluationRunDecisionStateFingerprint.from(candidate));
    }

    private static GateRunMetrics metrics(EvaluationRunComparisonReader.RunComparisonSnapshot snapshot) {
        return new GateRunMetrics(snapshot.platformId(), snapshot.qualityAvailable(), snapshot.qualityRate(),
                snapshot.totalCost(), snapshot.averageLatencyMs(), snapshot.p95LatencyMs());
    }

    private static Map<String, EvaluationRunComparisonReader.ResultComparisonSnapshot> index(
            List<EvaluationRunComparisonReader.ResultComparisonSnapshot> results) {
        Map<String, EvaluationRunComparisonReader.ResultComparisonSnapshot> indexed = new HashMap<>();
        for (var result : results) if (indexed.putIfAbsent(result.caseId(), result) != null) throw new AlignmentConflictException();
        return indexed;
    }

    private static void requireTerminal(EvaluationRunStatus status) {
        if (status != EvaluationRunStatus.COMPLETED && status != EvaluationRunStatus.PARTIAL
                && status != EvaluationRunStatus.FAILED && status != EvaluationRunStatus.CANCELLED) throw new IllegalArgumentException("只有终态运行可比较");
    }

    private static ComparisonCategory classify(EvaluationRunComparisonReader.ResultComparisonSnapshot baseline,
            EvaluationRunComparisonReader.ResultComparisonSnapshot candidate) {
        if (isError(baseline) || isError(candidate)) return ComparisonCategory.ERROR;
        if (isReviewRequired(baseline) || isReviewRequired(candidate)) return ComparisonCategory.REVIEW_REQUIRED;
        boolean baselinePassed = baseline.status() == EvaluationResultStatus.PASSED;
        boolean candidatePassed = candidate.status() == EvaluationResultStatus.PASSED;
        if (!baselinePassed && candidatePassed) return ComparisonCategory.IMPROVED;
        if (baselinePassed && !candidatePassed) return ComparisonCategory.REGRESSED;
        if (baseline.score() != null && candidate.score() != null) {
            int score = candidate.score().compareTo(baseline.score());
            if (score > 0) return ComparisonCategory.IMPROVED;
            if (score < 0) return ComparisonCategory.REGRESSED;
        }
        return ComparisonCategory.UNCHANGED;
    }

    private static boolean isError(EvaluationRunComparisonReader.ResultComparisonSnapshot result) {
        return result.status() == EvaluationResultStatus.ERROR || result.status() == EvaluationResultStatus.CANCELLED;
    }
    private static boolean isReviewRequired(EvaluationRunComparisonReader.ResultComparisonSnapshot result) {
        return result.status() == EvaluationResultStatus.PENDING && result.reviewRequired();
    }
    private static Comparator<CaseComparison> caseOrder() {
        return Comparator.comparingInt((CaseComparison value) -> severity(value.severity())).reversed()
                .thenComparingInt(value -> category(value.category())).thenComparing(CaseComparison::name).thenComparing(CaseComparison::caseId);
    }
    private static int severity(String value) { return switch (value) { case "CRITICAL" -> 4; case "HIGH" -> 3; case "MEDIUM" -> 2; case "LOW" -> 1; default -> throw new IllegalArgumentException("案例严重度无效"); }; }
    private static int category(ComparisonCategory value) { return switch (value) { case REGRESSED -> 0; case ERROR -> 1; case REVIEW_REQUIRED -> 2; case IMPROVED -> 3; case UNCHANGED -> 4; }; }

    public record Comparison(String baselineRunId, String candidateRunId, boolean qualityAvailable, BigDecimal qualityDelta, BigDecimal costDelta,
            long averageLatencyDeltaMs, long p95LatencyDeltaMs, List<CaseComparison> cases,
            @JsonIgnore GateRunMetrics baselineMetrics, @JsonIgnore GateRunMetrics candidateMetrics,
            @JsonIgnore String candidateDecisionStateFingerprint) {
        public Comparison(String baselineRunId, String candidateRunId, boolean qualityAvailable,
                BigDecimal qualityDelta, BigDecimal costDelta, long averageLatencyDeltaMs, long p95LatencyDeltaMs,
                List<CaseComparison> cases, GateRunMetrics baselineMetrics, GateRunMetrics candidateMetrics) {
            this(baselineRunId, candidateRunId, qualityAvailable, qualityDelta, costDelta, averageLatencyDeltaMs,
                    p95LatencyDeltaMs, cases, baselineMetrics, candidateMetrics, null);
        }

        public Comparison(String baselineRunId, String candidateRunId, boolean qualityAvailable, BigDecimal qualityDelta,
                BigDecimal costDelta, long averageLatencyDeltaMs, long p95LatencyDeltaMs, List<CaseComparison> cases) {
            this(baselineRunId, candidateRunId, qualityAvailable, qualityDelta, costDelta, averageLatencyDeltaMs,
                    p95LatencyDeltaMs, cases, null, null, null);
        }
    }
    public record CaseComparison(String resultId, String caseId, String name, String severity, ComparisonCategory category,
            String candidateOutputSummary, @JsonIgnore EvaluationResultStatus candidateStatus,
            @JsonIgnore boolean candidateReviewRequired, @JsonIgnore String candidateSnapshotCategory) {
        public CaseComparison(String resultId, String caseId, String name, String severity, ComparisonCategory category,
                String candidateOutputSummary, EvaluationResultStatus candidateStatus,
                boolean candidateReviewRequired) {
            this(resultId, caseId, name, severity, category, candidateOutputSummary, candidateStatus,
                    candidateReviewRequired, null);
        }
        public CaseComparison(String resultId, String caseId, String name, String severity, ComparisonCategory category,
                String candidateOutputSummary) {
            this(resultId, caseId, name, severity, category, candidateOutputSummary, null, false, null);
        }
    }
    public record GateRunMetrics(String platformId, boolean qualityAvailable, BigDecimal qualityRate,
            BigDecimal totalCost, long averageLatencyMs, long p95LatencyMs) {
        public GateRunMetrics(boolean qualityAvailable, BigDecimal qualityRate, BigDecimal totalCost,
                long averageLatencyMs, long p95LatencyMs) {
            this(null, qualityAvailable, qualityRate, totalCost, averageLatencyMs, p95LatencyMs);
        }
    }
    public static final class SnapshotConflictException extends RuntimeException {}
    public static final class AlignmentConflictException extends RuntimeException {}
}
