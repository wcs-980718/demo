package com.yiwei.midplat.evaluation.target;

/** Successful target output and accounting inputs. Its diagnostic representation never exposes output. */
public record EvaluationTargetResponse(String output, long promptTokens, long completionTokens, long totalTokens,
        EvaluationTargetTraceSummary traceSummary) {

    public EvaluationTargetResponse {
        if (output == null || promptTokens < 0 || completionTokens < 0 || totalTokens < 0 || traceSummary == null
                || totalTokens != safeTotal(promptTokens, completionTokens)) {
            throw new IllegalArgumentException("invalid evaluation target response");
        }
    }

    @Override
    public String toString() {
        return "EvaluationTargetResponse[output=[redacted], promptTokens=" + promptTokens + ", completionTokens="
                + completionTokens + ", totalTokens=" + totalTokens + ", traceSummary=" + traceSummary + "]";
    }

    static long safeTotal(long promptTokens, long completionTokens) {
        try {
            return Math.addExact(promptTokens, completionTokens);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("invalid evaluation target response");
        }
    }
}
