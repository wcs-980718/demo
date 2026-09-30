package com.yiwei.midplat.evaluation.target;

/** Deliberately small trace suitable for persistence without request or response contents. */
public record EvaluationTargetTraceSummary(int statusCode, long latencyMs) {
    public EvaluationTargetTraceSummary {
        if (statusCode < 100 || statusCode > 599 || latencyMs < 0) {
            throw new IllegalArgumentException("invalid evaluation target trace");
        }
    }
}
