package com.yiwei.midplat.evaluation.casecenter;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import com.yiwei.midplat.common.api.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity(name = "EvaluationCase")
@Table(name = "midplat_eval_case")
public class EvaluationCase extends BaseEntity {

    @Column(name = "platform_id", length = 64)
    private String platformId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private CaseSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private CaseSourceType sourceType;

    @Column(name = "source_ref", length = 256)
    private String sourceRef;

    @Column(name = "input_text", nullable = false, columnDefinition = "text")
    private String inputText;

    @Column(name = "expected_json", nullable = false, columnDefinition = "text")
    private String expectedJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluator_type", nullable = false, length = 32)
    private EvaluatorType evaluatorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 16)
    private CaseReviewStatus reviewStatus;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 16)
    private CaseLifecycleStatus lifecycleStatus;

    protected EvaluationCase() {}

    private EvaluationCase(
            String id,
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            CaseSourceType sourceType,
            String sourceRef,
            String inputText,
            String expectedJson,
            EvaluatorType evaluatorType,
            String contentHash) {
        super(id);
        this.platformId = blankToNull(platformId);
        this.name = DomainAssertions.requireText(name, "name 不能为空");
        this.category = DomainAssertions.requireText(category, "category 不能为空");
        this.severity = Objects.requireNonNull(severity, "severity 不能为空");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType 不能为空");
        this.sourceRef = blankToNull(sourceRef);
        this.inputText = DomainAssertions.requireText(inputText, "inputText 不能为空");
        this.expectedJson = DomainAssertions.requireText(expectedJson, "expected 不能为空");
        this.evaluatorType = Objects.requireNonNull(evaluatorType, "evaluatorType 不能为空");
        this.reviewStatus = CaseReviewStatus.DRAFT;
        this.contentHash = DomainAssertions.requireText(contentHash, "contentHash 不能为空");
        this.lifecycleStatus = CaseLifecycleStatus.ACTIVE;
    }

    public static EvaluationCase createManual(
            String id,
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            String inputText,
            String expectedJson,
            EvaluatorType evaluatorType,
            String contentHash) {
        return new EvaluationCase(
                id, platformId, name, category, severity,
                CaseSourceType.MANUAL, null, inputText, expectedJson,
                evaluatorType, contentHash);
    }

    public static EvaluationCase createImported(
            String id,
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            CaseSourceType sourceType,
            String sourceRef,
            String inputText,
            String expectedJson,
            EvaluatorType evaluatorType,
            String contentHash) {
        if (sourceType == CaseSourceType.MANUAL) {
            throw new IllegalArgumentException("导入案例不能使用 MANUAL 来源");
        }
        return new EvaluationCase(
                id, platformId, name, category, severity, sourceType,
                DomainAssertions.requireText(sourceRef, "sourceRef 不能为空"),
                inputText, expectedJson, evaluatorType, contentHash);
    }

    public void updateDraft(
            long expectedVersion,
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            String inputText,
            String expectedJson,
            EvaluatorType evaluatorType,
            String contentHash) {
        requireVersion(expectedVersion);
        requireActive();
        if (reviewStatus == CaseReviewStatus.REVIEWED) {
            throw new ConflictException("已审核案例不可编辑");
        }
        this.platformId = blankToNull(platformId);
        this.name = DomainAssertions.requireText(name, "name 不能为空");
        this.category = DomainAssertions.requireText(category, "category 不能为空");
        this.severity = Objects.requireNonNull(severity, "severity 不能为空");
        this.inputText = DomainAssertions.requireText(inputText, "inputText 不能为空");
        this.expectedJson = DomainAssertions.requireText(expectedJson, "expected 不能为空");
        this.evaluatorType = Objects.requireNonNull(evaluatorType, "evaluatorType 不能为空");
        this.contentHash = DomainAssertions.requireText(contentHash, "contentHash 不能为空");
        this.reviewStatus = CaseReviewStatus.DRAFT;
    }

    public void review(ReviewDecision decision, long expectedVersion) {
        requireVersion(expectedVersion);
        requireActive();
        if (reviewStatus != CaseReviewStatus.DRAFT) {
            throw new ConflictException("只有草稿案例可以审核");
        }
        reviewStatus = Objects.requireNonNull(decision, "decision 不能为空") == ReviewDecision.APPROVE
                ? CaseReviewStatus.REVIEWED
                : CaseReviewStatus.REJECTED;
    }

    public void archive(long expectedVersion) {
        requireVersion(expectedVersion);
        requireActive();
        lifecycleStatus = CaseLifecycleStatus.ARCHIVED;
    }

    private void requireVersion(long expectedVersion) {
        if (getVersion() != expectedVersion) {
            throw new ConflictException("案例版本已变化，请刷新后重试");
        }
    }

    private void requireActive() {
        if (lifecycleStatus != CaseLifecycleStatus.ACTIVE) {
            throw new ConflictException("归档案例不可变更");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String getPlatformId() {
        return platformId;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public CaseSeverity getSeverity() {
        return severity;
    }

    public CaseSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getInputText() {
        return inputText;
    }

    public String getExpectedJson() {
        return expectedJson;
    }

    public EvaluatorType getEvaluatorType() {
        return evaluatorType;
    }

    public CaseReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getContentHash() {
        return contentHash;
    }

    public CaseLifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }
}
