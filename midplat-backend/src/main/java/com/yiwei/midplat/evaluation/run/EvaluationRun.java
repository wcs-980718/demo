package com.yiwei.midplat.evaluation.run;

import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity(name = "EvaluationRun")
@Table(name = "midplat_eval_run")
public class EvaluationRun extends BaseEntity {

    private static final String RECOVERY_FAILURE_CODE = "RUN_RECOVERY_FAILED";
    private static final String RECOVERY_FAILURE_SUMMARY = "运行恢复失败";

    @Column(name = "dataset_version_id", nullable = false, length = 64) private String datasetVersionId;
    @Column(name = "platform_id", nullable = false, length = 64) private String platformId;
    @Column(name = "model_id", nullable = false, length = 64) private String modelId;
    @Column(name = "prompt_id", nullable = false, length = 64) private String promptId;
    @Column(name = "target_snapshot_json", nullable = false, columnDefinition = "text") private String targetSnapshotJson;
    @Column(name = "parameters_json", nullable = false, columnDefinition = "text") private String parametersJson;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16) private EvaluationRunStatus status;
    @Column(name = "cancel_requested", nullable = false) private boolean cancelRequested;
    @Column(name = "worker_owner", length = 64) private String workerOwner;
    @Column(name = "heartbeat_at") private Instant heartbeatAt;
    @Column(name = "lease_expires_at") private Instant leaseExpiresAt;
    @Column(name = "retry_revision", nullable = false) private int retryRevision;
    @Column(name = "source_run_id", length = 64) private String sourceRunId;
    @Column(name = "total_count", nullable = false) private int totalCount;
    @Column(name = "completed_count", nullable = false) private int completedCount;
    @Column(name = "passed_count", nullable = false) private int passedCount;
    @Column(name = "failed_count", nullable = false) private int failedCount;
    @Column(name = "error_count", nullable = false) private int errorCount;
    @Column(name = "manual_review_count", nullable = false) private int manualReviewCount;
    @Column(name = "prompt_tokens", nullable = false) private long promptTokens;
    @Column(name = "completion_tokens", nullable = false) private long completionTokens;
    @Column(name = "total_tokens", nullable = false) private long totalTokens;
    @Column(name = "total_cost", nullable = false, precision = 20, scale = 8) private BigDecimal totalCost;
    @Column(name = "avg_latency_ms", nullable = false) private long avgLatencyMs;
    @Column(name = "p95_latency_ms", nullable = false) private long p95LatencyMs;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "error_summary", length = 512) private String errorSummary;

    protected EvaluationRun() {}

    private EvaluationRun(String id, String datasetVersionId, String platformId, String modelId, String promptId,
            EvaluationTargetSnapshot targetSnapshot, EvaluationRunParameters parameters, int totalCount) {
        super(id);
        this.datasetVersionId = DomainAssertions.requireText(datasetVersionId, "datasetVersionId 不能为空");
        this.platformId = DomainAssertions.requireText(platformId, "platformId 不能为空");
        this.modelId = DomainAssertions.requireText(modelId, "modelId 不能为空");
        this.promptId = DomainAssertions.requireText(promptId, "promptId 不能为空");
        if (!this.datasetVersionId.equals(targetSnapshot.datasetVersionId()) || !this.platformId.equals(targetSnapshot.platformId())
                || !this.modelId.equals(targetSnapshot.modelId()) || !this.promptId.equals(targetSnapshot.promptId())) {
            throw new IllegalArgumentException("运行引用必须与快照一致");
        }
        this.targetSnapshotJson = EvaluationRunSnapshotCodec.target(targetSnapshot);
        this.parametersJson = EvaluationRunSnapshotCodec.parameters(parameters);
        if (totalCount < 0) throw new IllegalArgumentException("totalCount 不能为负数");
        this.totalCount = totalCount;
        this.status = EvaluationRunStatus.QUEUED;
        this.totalCost = BigDecimal.ZERO;
    }

    public static EvaluationRun create(String id, String datasetVersionId, String platformId, String modelId, String promptId,
            EvaluationTargetSnapshot targetSnapshot, EvaluationRunParameters parameters, int totalCount) {
        return new EvaluationRun(id, datasetVersionId, platformId, modelId, promptId, targetSnapshot, parameters, totalCount);
    }
    public static EvaluationRun retry(String id, String sourceRunId, String datasetVersionId, String platformId, String modelId, String promptId,
            EvaluationTargetSnapshot targetSnapshot, EvaluationRunParameters parameters, int totalCount) {
        EvaluationRun run = new EvaluationRun(id, datasetVersionId, platformId, modelId, promptId, targetSnapshot, parameters, totalCount);
        run.sourceRunId = DomainAssertions.requireText(sourceRunId, "sourceRunId 不能为空");
        return run;
    }

    public void markRunning(Instant startedAt, String owner, Duration leaseDuration) {
        if (status != EvaluationRunStatus.QUEUED || cancelRequested) throw new ConflictException("只有未取消的排队任务可以开始");
        if (startedAt == null) throw new IllegalArgumentException("startedAt 不能为空");
        status = EvaluationRunStatus.RUNNING;
        this.startedAt = startedAt;
        renewLease(startedAt, owner, leaseDuration);
    }

    /** Compatibility helper for domain tests; production claims always provide the instance owner. */
    public void markRunning(Instant startedAt) { markRunning(startedAt, "test-worker", Duration.ofMinutes(10)); }

    public void renewLease(Instant now, String owner, Duration leaseDuration) {
        if (status != EvaluationRunStatus.RUNNING || owner == null || owner.isBlank() || leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMinutes(10)) < 0) throw new IllegalArgumentException("运行租约无效");
        workerOwner = owner;
        heartbeatAt = now;
        leaseExpiresAt = now.plus(leaseDuration);
    }

    public boolean hasExpiredLease(Instant now, Duration legacyStaleAfter) {
        if (status != EvaluationRunStatus.RUNNING) return false;
        if (leaseExpiresAt != null) return leaseExpiresAt.isBefore(now);
        return startedAt != null && startedAt.plus(legacyStaleAfter).isBefore(now);
    }

    public void requestCancellation(long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (status != EvaluationRunStatus.QUEUED && status != EvaluationRunStatus.RUNNING) throw new ConflictException("终态任务不可取消");
        if (cancelRequested) throw new ConflictException("任务已请求取消");
        cancelRequested = true;
    }

    /** Atomically consumes one retry generation for a terminal source run. */
    public void startErrorRetry(long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (status != EvaluationRunStatus.COMPLETED && status != EvaluationRunStatus.PARTIAL
                && status != EvaluationRunStatus.FAILED && status != EvaluationRunStatus.CANCELLED) {
            throw new ConflictException("只有已结束任务可以重试错误");
        }
        retryRevision++;
    }

    public void recordTerminalResult(EvaluationResultStatus resultStatus, long promptTokens, long completionTokens,
            long totalTokens, BigDecimal cost, long latencyMs) {
        if (status != EvaluationRunStatus.RUNNING) throw new ConflictException("只有运行中任务可累计结果");
        if (resultStatus != EvaluationResultStatus.PASSED && resultStatus != EvaluationResultStatus.FAILED
                && resultStatus != EvaluationResultStatus.ERROR && resultStatus != EvaluationResultStatus.CANCELLED) {
            throw new IllegalArgumentException("结果必须为终态");
        }
        recordCompletedResult(resultStatus, promptTokens, completionTokens, totalTokens, cost, latencyMs);
    }

    /** Records a completed MANUAL case, which remains pending its later human review. */
    public void recordManualResult(long promptTokens, long completionTokens, long totalTokens, BigDecimal cost, long latencyMs) {
        if (status != EvaluationRunStatus.RUNNING) throw new ConflictException("只有运行中任务可累计结果");
        recordCompletedResult(EvaluationResultStatus.PENDING, promptTokens, completionTokens, totalTokens, cost, latencyMs);
        manualReviewCount++;
    }

    public void recordManualReview(boolean passed) {
        if (status == EvaluationRunStatus.QUEUED || status == EvaluationRunStatus.RUNNING) {
            throw new ConflictException("人工评分前运行必须已完成执行");
        }
        if (manualReviewCount <= 0) throw new ConflictException("运行没有待人工复核结果");
        manualReviewCount--;
        if (passed) passedCount++;
        else failedCount++;
    }

    public void recordCancelledResult() { recordCompletedResult(EvaluationResultStatus.CANCELLED, 0, 0, 0, BigDecimal.ZERO, 0); }

    /**
     * Completes normal execution from the complete persisted result latency set. Average includes every
     * executed result (including 0ms); P95 uses nearest-rank, with 0 for an empty set.
     */
    public void complete(Instant completedAt, List<Long> completedLatenciesMs) {
        if (status != EvaluationRunStatus.RUNNING) throw new ConflictException("只有运行中任务可以正常结束");
        if (completedAt == null) throw new IllegalArgumentException("completedAt 不能为空");
        if (completedCount != totalCount) throw new ConflictException("正常结束前必须完成全部案例");
        LatencyStatistics latencyStatistics = calculateLatencyStatistics(completedLatenciesMs, completedCount);
        avgLatencyMs = latencyStatistics.averageMs();
        p95LatencyMs = latencyStatistics.p95Ms();
        status = errorCount == 0 ? EvaluationRunStatus.COMPLETED : EvaluationRunStatus.PARTIAL;
        this.completedAt = completedAt;
        this.errorCode = null;
        this.errorSummary = null;
        workerOwner = null;
        heartbeatAt = null;
        leaseExpiresAt = null;
    }

    public void cancel(Instant completedAt, String errorCode, String errorSummary) {
        if (status != EvaluationRunStatus.QUEUED && status != EvaluationRunStatus.RUNNING) {
            throw new ConflictException("任务已结束");
        }
        if (!cancelRequested) throw new ConflictException("取消前必须先请求取消");
        setTerminal(EvaluationRunStatus.CANCELLED, completedAt, errorCode, errorSummary);
    }

    public void failDuringRun(Instant completedAt, String errorCode, String errorSummary) {
        if (status != EvaluationRunStatus.RUNNING) throw new ConflictException("仅运行中任务可恢复失败");
        setTerminal(EvaluationRunStatus.FAILED, completedAt, errorCode, errorSummary);
    }

    public void recoverAsFailed(Instant completedAt, List<Long> executedLatenciesMs) {
        if (status != EvaluationRunStatus.RUNNING) throw new ConflictException("仅运行中任务可恢复失败");
        LatencyStatistics statistics = calculateLatencyStatistics(executedLatenciesMs, executedLatenciesMs.size());
        avgLatencyMs = statistics.averageMs();
        p95LatencyMs = statistics.p95Ms();
        setTerminal(EvaluationRunStatus.FAILED, completedAt, RECOVERY_FAILURE_CODE, RECOVERY_FAILURE_SUMMARY);
    }

    public void recoverAsFailed(Instant completedAt) { recoverAsFailed(completedAt, List.of()); }

    private void recordCompletedResult(EvaluationResultStatus resultStatus, long promptTokens, long completionTokens,
            long totalTokens, BigDecimal cost, long latencyMs) {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0 || latencyMs < 0 || cost == null || cost.signum() < 0) {
            throw new IllegalArgumentException("统计不能为负数");
        }
        if (completedCount >= totalCount) throw new ConflictException("完成数量不能超过总数");
        long nextPrompt;
        long nextCompletion;
        long nextTotal;
        BigDecimal nextCost;
        try {
            nextPrompt = Math.addExact(this.promptTokens, promptTokens);
            nextCompletion = Math.addExact(this.completionTokens, completionTokens);
            nextTotal = Math.addExact(this.totalTokens, totalTokens);
            nextCost = totalCost.add(cost);
            if (nextCost.compareTo(new BigDecimal("999999999999.99999999")) > 0) throw new ArithmeticException();
        } catch (ArithmeticException ex) { throw new AggregateLimitExceededException(); }
        completedCount++;
        if (resultStatus == EvaluationResultStatus.PASSED) passedCount++;
        if (resultStatus == EvaluationResultStatus.FAILED) failedCount++;
        if (resultStatus == EvaluationResultStatus.ERROR) errorCount++;
        this.promptTokens = nextPrompt;
        this.completionTokens = nextCompletion;
        this.totalTokens = nextTotal;
        totalCost = nextCost;
    }

    private static LatencyStatistics calculateLatencyStatistics(List<Long> completedLatenciesMs, int expectedSize) {
        if (completedLatenciesMs == null || completedLatenciesMs.size() != expectedSize) {
            throw new IllegalArgumentException("延迟集合必须与完成案例数一致");
        }
        if (completedLatenciesMs.isEmpty()) return new LatencyStatistics(0, 0);
        BigInteger sum = BigInteger.ZERO;
        List<Long> sorted = new ArrayList<>(completedLatenciesMs.size());
        for (Long value : completedLatenciesMs) {
            if (value == null || value < 0) throw new IllegalArgumentException("P95 延迟不能为负数");
            sum = sum.add(BigInteger.valueOf(value));
            sorted.add(value);
        }
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(sorted.size() * 0.95d) - 1;
        return new LatencyStatistics(sum.divide(BigInteger.valueOf(sorted.size())).longValueExact(), sorted.get(index));
    }

    private record LatencyStatistics(long averageMs, long p95Ms) {}

    private void setTerminal(EvaluationRunStatus terminalStatus, Instant completedAt, String errorCode, String errorSummary) {
        if (completedAt == null) throw new IllegalArgumentException("completedAt 不能为空");
        status = terminalStatus;
        this.completedAt = completedAt;
        this.errorCode = errorCode;
        this.errorSummary = errorSummary;
        workerOwner = null;
        heartbeatAt = null;
        leaseExpiresAt = null;
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (getVersion() != expectedVersion) throw new ConflictException("运行版本已变化，请刷新后重试");
    }

    public String getDatasetVersionId() { return datasetVersionId; }
    public String getPlatformId() { return platformId; }
    public String getModelId() { return modelId; }
    public String getPromptId() { return promptId; }
    public String getTargetSnapshotJson() { return targetSnapshotJson; }
    public String getParametersJson() { return parametersJson; }
    public EvaluationRunStatus getStatus() { return status; }
    public boolean isCancelRequested() { return cancelRequested; }
    public int getTotalCount() { return totalCount; }
    public int getCompletedCount() { return completedCount; }
    public int getPassedCount() { return passedCount; }
    public int getFailedCount() { return failedCount; }
    public int getErrorCount() { return errorCount; }
    public int getManualReviewCount() { return manualReviewCount; }
    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public long getTotalTokens() { return totalTokens; }
    public BigDecimal getTotalCost() { return totalCost; }
    public long getAvgLatencyMs() { return avgLatencyMs; }
    public long getP95LatencyMs() { return p95LatencyMs; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getRetryRevision() { return retryRevision; }
    public String getSourceRunId() { return sourceRunId; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
}
