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
import java.time.Instant;

@Entity(name = "EvaluationResult")
@Table(name = "midplat_eval_result")
public class EvaluationResult extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 64) private String runId;
    @Column(name = "case_id", nullable = false, length = 64) private String caseId;
    @Column(name = "order_no", nullable = false) private int orderNo;
    @Column(name = "snapshot_name", nullable = false, length = 128) private String snapshotName;
    @Column(name = "snapshot_category", nullable = false, length = 64) private String snapshotCategory;
    @Column(name = "snapshot_severity", nullable = false, length = 16) private String snapshotSeverity;
    @Column(name = "snapshot_input_text", nullable = false, columnDefinition = "text") private String snapshotInputText;
    @Column(name = "snapshot_expected_json", nullable = false, columnDefinition = "text") private String snapshotExpectedJson;
    @Column(name = "snapshot_evaluator_type", nullable = false, length = 32) private String snapshotEvaluatorType;
    @Column(name = "snapshot_content_hash", nullable = false, length = 64) private String snapshotContentHash;
    @Column(name = "actual_output", columnDefinition = "text") private String actualOutput;
    @Column(name = "score", precision = 10, scale = 4) private BigDecimal score;
    @Column(name = "passed") private Boolean passed;
    @Column(name = "review_required", nullable = false) private boolean reviewRequired;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16) private EvaluationResultStatus status;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "error_summary", length = 512) private String errorSummary;
    @Column(name = "trace_summary_json", columnDefinition = "text") private String traceSummaryJson;
    @Column(name = "latency_ms", nullable = false) private long latencyMs;
    @Column(name = "prompt_tokens", nullable = false) private long promptTokens;
    @Column(name = "completion_tokens", nullable = false) private long completionTokens;
    @Column(name = "total_tokens", nullable = false) private long totalTokens;
    @Column(name = "cost", nullable = false, precision = 20, scale = 8) private BigDecimal cost;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "review_summary", length = 512) private String reviewSummary;

    protected EvaluationResult() {}

    private EvaluationResult(String id, String runId, String caseId, int orderNo, String snapshotName, String snapshotCategory,
            String snapshotSeverity, String snapshotInputText, String snapshotExpectedJson, String snapshotEvaluatorType,
            String snapshotContentHash) {
        super(id);
        this.runId = DomainAssertions.requireText(runId, "runId 不能为空");
        this.caseId = DomainAssertions.requireText(caseId, "caseId 不能为空");
        if (orderNo < 1) throw new IllegalArgumentException("orderNo 必须大于 0");
        this.orderNo = orderNo;
        this.snapshotName = DomainAssertions.requireText(snapshotName, "snapshotName 不能为空");
        this.snapshotCategory = DomainAssertions.requireText(snapshotCategory, "snapshotCategory 不能为空");
        this.snapshotSeverity = DomainAssertions.requireText(snapshotSeverity, "snapshotSeverity 不能为空");
        this.snapshotInputText = DomainAssertions.requireText(snapshotInputText, "snapshotInputText 不能为空");
        this.snapshotExpectedJson = DomainAssertions.requireText(snapshotExpectedJson, "snapshotExpectedJson 不能为空");
        this.snapshotEvaluatorType = DomainAssertions.requireText(snapshotEvaluatorType, "snapshotEvaluatorType 不能为空");
        this.snapshotContentHash = DomainAssertions.requireText(snapshotContentHash, "snapshotContentHash 不能为空");
        this.status = EvaluationResultStatus.PENDING;
        this.cost = BigDecimal.ZERO;
    }

    static EvaluationResult pending(String id, String runId, String caseId, int orderNo, String snapshotName,
            String snapshotCategory, String snapshotSeverity, String snapshotInputText, String snapshotExpectedJson,
            String snapshotEvaluatorType, String snapshotContentHash) {
        return new EvaluationResult(id, runId, caseId, orderNo, snapshotName, snapshotCategory, snapshotSeverity,
                snapshotInputText, snapshotExpectedJson, snapshotEvaluatorType, snapshotContentHash);
    }

    public void markRunning() {
        if (status != EvaluationResultStatus.PENDING || reviewRequired) throw new ConflictException("只有未待人工复核的结果可以开始");
        status = EvaluationResultStatus.RUNNING;
    }

    public void completeAutomated(EvaluationResultStatus terminalStatus, String output, BigDecimal score, Boolean passed,
            String errorCode, String errorSummary, String traceSummaryJson, long latencyMs,
            long promptTokens, long completionTokens, long totalTokens, BigDecimal cost, int attemptCount) {
        if (terminalStatus != EvaluationResultStatus.PASSED && terminalStatus != EvaluationResultStatus.FAILED
                && terminalStatus != EvaluationResultStatus.ERROR && terminalStatus != EvaluationResultStatus.CANCELLED) {
            throw new IllegalArgumentException("必须是结果终态");
        }
        if (status != EvaluationResultStatus.RUNNING) throw new ConflictException("只有运行中结果可以完成");
        requireNonNegative(latencyMs, promptTokens, completionTokens, totalTokens, cost, attemptCount);
        status = terminalStatus;
        actualOutput = output;
        this.score = score;
        this.passed = passed;
        this.reviewRequired = false;
        this.errorCode = errorCode;
        this.errorSummary = errorSummary;
        this.traceSummaryJson = traceSummaryJson;
        this.latencyMs = latencyMs;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cost = cost;
        this.attemptCount = attemptCount;
    }

    public void recordManualExecution(String output, String traceSummaryJson, long promptTokens, long completionTokens,
            long totalTokens, BigDecimal cost, int attemptCount, long latencyMs) {
        if (status != EvaluationResultStatus.RUNNING) throw new ConflictException("只有运行中结果可等待人工评分");
        requireNonNegative(latencyMs, promptTokens, completionTokens, totalTokens, cost, attemptCount);
        status = EvaluationResultStatus.PENDING;
        reviewRequired = true;
        actualOutput = output;
        this.traceSummaryJson = traceSummaryJson;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cost = cost;
        this.attemptCount = attemptCount;
        this.latencyMs = latencyMs;
    }

    public void review(BigDecimal score, boolean passed, Instant reviewedAt, String reviewSummary, long expectedVersion) {
        if (getVersion() != expectedVersion) throw new ConflictException("结果版本已变化，请刷新后重试");
        if (status != EvaluationResultStatus.PENDING || !reviewRequired) throw new ConflictException("结果不等待人工评分");
        if (reviewedAt == null) throw new IllegalArgumentException("reviewedAt 不能为空");
        String validatedReviewSummary = DomainAssertions.requireText(reviewSummary, "reviewSummary 不能为空");
        status = passed ? EvaluationResultStatus.PASSED : EvaluationResultStatus.FAILED;
        this.score = score;
        this.passed = passed;
        this.reviewRequired = false;
        this.reviewedAt = reviewedAt;
        this.reviewSummary = validatedReviewSummary;
    }

    boolean cancelPending() {
        if (status != EvaluationResultStatus.PENDING || reviewRequired) return false;
        status = EvaluationResultStatus.CANCELLED;
        errorCode = "CANCELLED";
        errorSummary = "任务已取消";
        return true;
    }

    private static void requireNonNegative(long latencyMs, long promptTokens, long completionTokens, long totalTokens,
            BigDecimal cost, int attemptCount) {
        if (latencyMs < 0 || promptTokens < 0 || completionTokens < 0 || totalTokens < 0 || attemptCount < 0
                || cost == null || cost.signum() < 0) throw new IllegalArgumentException("执行统计不能为负数");
    }

    public String getRunId() { return runId; }
    public String getCaseId() { return caseId; }
    public int getOrderNo() { return orderNo; }
    public EvaluationResultStatus getStatus() { return status; }
    public boolean isReviewRequired() { return reviewRequired; }
    public String getActualOutput() { return actualOutput; }
    public long getTotalTokens() { return totalTokens; }
    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public long getLatencyMs() { return latencyMs; }
    public BigDecimal getCost() { return cost; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewSummary() { return reviewSummary; }
    public BigDecimal getScore() { return score; }
    public Boolean getPassed() { return passed; }
    public String getErrorCode() { return errorCode; }
    public String getErrorSummary() { return errorSummary; }
    public String getTraceSummaryJson() { return traceSummaryJson; }
    public int getAttemptCount() { return attemptCount; }
    public String getSnapshotName() { return snapshotName; }
    public String getSnapshotCategory() { return snapshotCategory; }
    public String getSnapshotSeverity() { return snapshotSeverity; }
    public String getSnapshotInputText() { return snapshotInputText; }
    public String getSnapshotExpectedJson() { return snapshotExpectedJson; }
    public String getSnapshotEvaluatorType() { return snapshotEvaluatorType; }
    public String getSnapshotContentHash() { return snapshotContentHash; }
}
