package com.yiwei.midplat.evaluation.evaluator;

import java.math.BigDecimal;
import java.util.Objects;

/** Body-free result with only automated, manual-review, or configuration-error shapes. */
public final class EvaluationOutcome {
    private final Kind kind;
    private final EvaluationReason reason;
    private final int firstCount;
    private final int secondCount;

    private EvaluationOutcome(Kind kind, EvaluationReason reason, int firstCount, int secondCount) {
        if (firstCount < 0 || secondCount < 0) throw new IllegalArgumentException("计数不能为负数");
        this.kind = kind;
        this.reason = reason;
        this.firstCount = firstCount;
        this.secondCount = secondCount;
    }

    static EvaluationOutcome automated(boolean passed, EvaluationReason reason) {
        if (!reason.supportsAutomated(passed)) throw new IllegalArgumentException("评分结果与原因不一致");
        return new EvaluationOutcome(passed ? Kind.AUTOMATED_PASS : Kind.AUTOMATED_FAIL, reason, 0, 0);
    }

    static EvaluationOutcome automated(boolean passed, EvaluationReason reason, int firstCount, int secondCount) {
        if (!reason.supportsAutomated(passed)) throw new IllegalArgumentException("评分结果与原因不一致");
        return new EvaluationOutcome(passed ? Kind.AUTOMATED_PASS : Kind.AUTOMATED_FAIL, reason, firstCount, secondCount);
    }

    static EvaluationOutcome manual() {
        return new EvaluationOutcome(Kind.MANUAL_REVIEW, EvaluationReason.MANUAL_REVIEW_REQUIRED, 0, 0);
    }

    static EvaluationOutcome invalidConfiguration() {
        return new EvaluationOutcome(Kind.CONFIGURATION_ERROR, EvaluationReason.EVALUATOR_CONFIG_INVALID, 0, 0);
    }

    public BigDecimal score() {
        return kind == Kind.AUTOMATED_PASS ? BigDecimal.ONE : kind == Kind.AUTOMATED_FAIL ? BigDecimal.ZERO : null;
    }

    public Boolean passed() {
        return kind == Kind.AUTOMATED_PASS ? Boolean.TRUE : kind == Kind.AUTOMATED_FAIL ? Boolean.FALSE : null;
    }

    public boolean reviewRequired() {
        return kind == Kind.MANUAL_REVIEW;
    }

    public String reasonCode() {
        return reason.name();
    }

    public String reason() {
        return reason.render(firstCount, secondCount);
    }

    public String errorCode() {
        return kind == Kind.CONFIGURATION_ERROR ? EvaluationReason.EVALUATOR_CONFIG_INVALID.name() : null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EvaluationOutcome outcome)) return false;
        return firstCount == outcome.firstCount && secondCount == outcome.secondCount && kind == outcome.kind && reason == outcome.reason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, reason, firstCount, secondCount);
    }

    @Override
    public String toString() {
        return "EvaluationOutcome[score=" + score() + ", passed=" + passed() + ", reviewRequired=" + reviewRequired()
                + ", reasonCode=" + reasonCode() + ", reason=" + reason() + ", errorCode=" + errorCode() + "]";
    }

    private enum Kind {
        AUTOMATED_PASS,
        AUTOMATED_FAIL,
        MANUAL_REVIEW,
        CONFIGURATION_ERROR
    }
}

enum EvaluationReason {
    EXACT_MATCH, EXACT_MISMATCH, ACTUAL_OUTPUT_MISSING, CONTAINS_ALL_MATCH, CONTAINS_ALL_MISSING, JSON_VALID,
    JSON_INVALID, JSON_ROOT_NOT_OBJECT, JSON_REQUIRED_FIELDS_MISSING, NUMERIC_IN_RANGE, NUMERIC_OUT_OF_RANGE,
    NUMERIC_NOT_FOUND, FORBIDDEN_TERMS_CLEAR, FORBIDDEN_TERMS_FOUND, MANUAL_REVIEW_REQUIRED, EVALUATOR_CONFIG_INVALID;

    String render(int firstCount, int secondCount) {
        return switch (this) {
            case CONTAINS_ALL_MATCH, CONTAINS_ALL_MISSING -> name() + " requiredCount=" + firstCount + " matchedCount=" + secondCount;
            case JSON_REQUIRED_FIELDS_MISSING -> name() + " requiredCount=" + firstCount + " matchedCount=" + secondCount;
            case JSON_VALID -> name() + " requiredCount=" + firstCount;
            case FORBIDDEN_TERMS_CLEAR, FORBIDDEN_TERMS_FOUND -> name() + " termCount=" + firstCount + " hitCount=" + secondCount;
            default -> name();
        };
    }

    boolean supportsAutomated(boolean passed) {
        return passed ? switch (this) {
            case EXACT_MATCH, CONTAINS_ALL_MATCH, JSON_VALID, NUMERIC_IN_RANGE, FORBIDDEN_TERMS_CLEAR -> true;
            default -> false;
        } : switch (this) {
            case EXACT_MISMATCH, ACTUAL_OUTPUT_MISSING, CONTAINS_ALL_MISSING, JSON_INVALID, JSON_ROOT_NOT_OBJECT,
                    JSON_REQUIRED_FIELDS_MISSING, NUMERIC_OUT_OF_RANGE, NUMERIC_NOT_FOUND, FORBIDDEN_TERMS_FOUND -> true;
            default -> false;
        };
    }
}
