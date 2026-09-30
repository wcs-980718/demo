package com.yiwei.midplat.evaluation.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.evaluation.casecenter.EvaluationAuditService;
import com.yiwei.midplat.evaluation.casecenter.EvaluatorType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Every state transition is deliberately short; no network or scoring work enters these transactions. */
@Service
public class EvaluationRunStateService {
    private final EvaluationRunRepository runs;
    private final EvaluationResultRepository results;
    private final ObjectMapper objectMapper;
    private final EvaluationAuditService audit;
    private final String workerOwner;
    private final Duration leaseDuration;

    EvaluationRunStateService(EvaluationRunRepository runs, EvaluationResultRepository results, ObjectMapper objectMapper,
            EvaluationAuditService audit, @Value("${midplat.evaluation.worker-owner:}") String configuredWorkerOwner,
            @Value("${midplat.evaluation.lease-seconds:600}") long leaseSeconds) {
        this.runs = runs; this.results = results; this.objectMapper = objectMapper; this.audit = audit;
        this.workerOwner = configuredWorkerOwner == null || configuredWorkerOwner.isBlank() ? "evaluation-" + UUID.randomUUID() : configuredWorkerOwner;
        this.leaseDuration = Duration.ofSeconds(Math.max(600, leaseSeconds));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean start(String runId) {
        EvaluationRun run = requireRunForUpdate(runId);
        if (run.getStatus() != EvaluationRunStatus.QUEUED) return false;
        if (run.isCancelRequested()) { closeCancelled(run); return false; }
        if (run.getTotalCount() == 0) { failConfiguration(run); return false; }
        run.markRunning(Instant.now(), workerOwner, leaseDuration);
        return true;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<String> resultIds(String runId) {
        return results.findAllByRunIdOrderByOrderNoAsc(runId).stream().map(EvaluationResult::getId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionContext startResult(String runId, String resultId) {
        EvaluationRun run = requireRunForUpdate(runId);
        if (run.isCancelRequested()) return null;
        if (run.getStatus() != EvaluationRunStatus.RUNNING) return null;
        run.renewLease(Instant.now(), workerOwner, leaseDuration);
        EvaluationResult result = requireResultForUpdate(resultId);
        if (!runId.equals(result.getRunId()) || result.getStatus() != EvaluationResultStatus.PENDING || result.isReviewRequired()) return null;
        result.markRunning();
        try {
            EvaluationTargetSnapshot target = EvaluationRunSnapshotCodec.target(run.getTargetSnapshotJson());
            EvaluationRunParameters parameters = EvaluationRunSnapshotCodec.parameters(run.getParametersJson());
            return new ExecutionContext(runId, resultId, target, parameters, result.getSnapshotInputText(),
                    result.getSnapshotExpectedJson(), EvaluatorType.valueOf(result.getSnapshotEvaluatorType()));
        } catch (Exception exception) {
            result.completeAutomated(EvaluationResultStatus.ERROR, null, null, null, "SNAPSHOT_INVALID", "评测快照无效", null,
                    0, 0, 0, 0, BigDecimal.ZERO, 1);
            run.recordTerminalResult(EvaluationResultStatus.ERROR, 0, 0, 0, BigDecimal.ZERO, 0);
            closePendingAndFail(run, "CONFIGURATION_ERROR", "评测配置无效");
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(String runId, String resultId, Completion completion) {
        EvaluationRun run = requireRunForUpdate(runId);
        EvaluationResult result = requireResultForUpdate(resultId);
        if (run.getStatus() != EvaluationRunStatus.RUNNING || result.getStatus() != EvaluationResultStatus.RUNNING) return;
        if (completion.reviewRequired) {
            result.recordManualExecution(completion.output, completion.traceJson, completion.promptTokens, completion.completionTokens,
                    completion.totalTokens, completion.cost, completion.attempts, completion.latencyMs);
            run.recordManualResult(completion.promptTokens, completion.completionTokens, completion.totalTokens, completion.cost, completion.latencyMs);
        } else {
            result.completeAutomated(completion.status, completion.output, completion.score, completion.passed, completion.errorCode,
                    completion.errorSummary, completion.traceJson, completion.latencyMs, completion.promptTokens,
                    completion.completionTokens, completion.totalTokens, completion.cost, completion.attempts);
            run.recordTerminalResult(completion.status, completion.promptTokens, completion.completionTokens, completion.totalTokens,
                    completion.cost, completion.latencyMs);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String runId) {
        EvaluationRun run = requireRunForUpdate(runId);
        if (run.getStatus() != EvaluationRunStatus.RUNNING && run.getStatus() != EvaluationRunStatus.QUEUED) return;
        if (run.isCancelRequested()) { closeCancelled(run); return; }
        if (run.getStatus() == EvaluationRunStatus.RUNNING && run.getCompletedCount() == run.getTotalCount()) {
            run.complete(Instant.now(), results.findAllByRunIdOrderByOrderNoAsc(runId).stream().map(EvaluationResult::getLatencyMs).toList());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failUnexpected(String runId) {
        EvaluationRun run = requireRunForUpdate(runId);
        if (run.getStatus() == EvaluationRunStatus.RUNNING) {
            for (EvaluationResult result : results.findAllByRunIdOrderByOrderNoAsc(runId)) {
                if (result.getStatus() == EvaluationResultStatus.RUNNING) {
                    result.completeAutomated(EvaluationResultStatus.ERROR, null, null, null, "RUN_EXECUTION_FAILED", "评测任务执行失败", null, 0, 0, 0, 0, BigDecimal.ZERO, 1);
                    run.recordTerminalResult(EvaluationResultStatus.ERROR, 0, 0, 0, BigDecimal.ZERO, 0);
                } else if (result.cancelPending()) run.recordCancelledResult();
            }
            run.failDuringRun(Instant.now(), "RUN_EXECUTION_FAILED", "评测任务执行失败");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failSharedConfiguration(String runId) {
        EvaluationRun run = requireRunForUpdate(runId);
        if (run.getStatus() == EvaluationRunStatus.RUNNING) closePendingAndFail(run, "CONFIGURATION_ERROR", "评测配置无效");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failFatal(String runId, String code, String summary) {
        EvaluationRun run = requireRunForUpdate(runId);
        if (run.getStatus() == EvaluationRunStatus.RUNNING) closePendingAndFail(run, code, summary);
    }

    /**
     * A previous process may have died after claiming a run.  Do not reissue its external call: make every
     * unfinished automatic result explainable, retain completed/manual work, and commit the audit atomically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverInterruptedRun(String runId, Duration legacyStaleAfter) {
        recoverInterruptedRun(runId, legacyStaleAfter, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverInterruptedRun(String runId, Duration legacyStaleAfter, Instant now) {
        EvaluationRun run = requireRunForUpdate(runId);
        if (!run.hasExpiredLease(now, legacyStaleAfter)) return;
        List<Long> executedLatencies = new java.util.ArrayList<>();
        for (EvaluationResult result : results.findAllByRunIdOrderByOrderNoAsc(runId)) {
            if (wasExecutedBeforeRecovery(result)) executedLatencies.add(result.getLatencyMs());
            if (result.getStatus() == EvaluationResultStatus.RUNNING) {
                result.completeAutomated(EvaluationResultStatus.ERROR, null, null, null,
                        "RUN_RECOVERY_FAILED", "运行恢复失败", null, 0, 0, 0, 0, BigDecimal.ZERO, 0);
                run.recordTerminalResult(EvaluationResultStatus.ERROR, 0, 0, 0, BigDecimal.ZERO, 0);
            } else if (result.cancelPending()) {
                run.recordCancelledResult();
            }
        }
        run.recoverAsFailed(now, executedLatencies);
        audit.recordResource("RUN_RECOVERED_FAILED", "EVALUATION_RUN", run.getId(), "STARTUP",
                java.util.Map.of("status", EvaluationRunStatus.FAILED.name(), "itemCount", run.getTotalCount()));
    }

    private static boolean wasExecutedBeforeRecovery(EvaluationResult result) {
        return result.getStatus() == EvaluationResultStatus.PASSED || result.getStatus() == EvaluationResultStatus.FAILED
                || result.getStatus() == EvaluationResultStatus.ERROR || (result.getStatus() == EvaluationResultStatus.PENDING
                && result.isReviewRequired());
    }

    @Transactional
    public void review(String runId, String resultId, BigDecimal score, boolean passed, long expectedVersion) {
        if ((passed && BigDecimal.ONE.compareTo(score) != 0) || (!passed && BigDecimal.ZERO.compareTo(score) != 0)) {
            throw new IllegalArgumentException("score 与 passed 不一致");
        }
        EvaluationRun run = requireRunForUpdate(runId);
        EvaluationResult result = requireResultForUpdate(resultId);
        if (!runId.equals(result.getRunId())) throw new ResourceNotFoundException("evaluation result not found");
        result.review(score, passed, Instant.now(), passed ? "MANUAL_PASSED" : "MANUAL_FAILED", expectedVersion);
        run.recordManualReview(passed);
    }

    @Transactional
    public void cancelQueuedInCurrentTransaction(EvaluationRun run) { closeCancelled(run); }

    private void closeCancelled(EvaluationRun run) {
        for (EvaluationResult result : results.findAllByRunIdOrderByOrderNoAsc(run.getId())) if (result.cancelPending()) run.recordCancelledResult();
        run.cancel(Instant.now(), "CANCELLED", "任务已取消");
    }
    private void failConfiguration(EvaluationRun run) {
        run.markRunning(Instant.now(), workerOwner, leaseDuration);
        closePendingAndFail(run, "CONFIGURATION_ERROR", "评测配置无效");
    }
    private void closePendingAndFail(EvaluationRun run, String code, String summary) {
        for (EvaluationResult result : results.findAllByRunIdOrderByOrderNoAsc(run.getId())) if (result.cancelPending()) run.recordCancelledResult();
        run.failDuringRun(Instant.now(), code, summary);
    }
    private EvaluationRun requireRunForUpdate(String id) { return runs.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("evaluation run not found")); }
    private EvaluationResult requireResultForUpdate(String id) { return results.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("evaluation result not found")); }

    public static final class ExecutionContext {
        final String runId, resultId, input, expectedJson; final EvaluationTargetSnapshot target; final EvaluationRunParameters parameters; final EvaluatorType evaluatorType;
        ExecutionContext(String runId, String resultId, EvaluationTargetSnapshot target, EvaluationRunParameters parameters,
                String input, String expectedJson, EvaluatorType evaluatorType) { this.runId=runId; this.resultId=resultId; this.target=target; this.parameters=parameters; this.input=input; this.expectedJson=expectedJson; this.evaluatorType=evaluatorType; }
        @Override public String toString() { return "ExecutionContext[runId=[redacted], resultId=[redacted], input=[redacted], expectedJson=[redacted]]"; }
    }
    public static final class Completion {
        final EvaluationResultStatus status; final String output, errorCode, errorSummary, traceJson; final BigDecimal score, cost; final Boolean passed; final boolean reviewRequired; final long latencyMs,promptTokens,completionTokens,totalTokens; final int attempts;
        Completion(EvaluationResultStatus status,String output,BigDecimal score,Boolean passed,boolean reviewRequired,String errorCode,String errorSummary,String traceJson,long latencyMs,long promptTokens,long completionTokens,long totalTokens,BigDecimal cost,int attempts) { this.status=status;this.output=output;this.score=score;this.passed=passed;this.reviewRequired=reviewRequired;this.errorCode=errorCode;this.errorSummary=errorSummary;this.traceJson=traceJson;this.latencyMs=latencyMs;this.promptTokens=promptTokens;this.completionTokens=completionTokens;this.totalTokens=totalTokens;this.cost=cost;this.attempts=attempts; }
        @Override public String toString() { return "Completion[status="+status+", output=[redacted], errorCode="+errorCode+"]"; }
    }
}
