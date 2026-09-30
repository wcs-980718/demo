package com.yiwei.midplat.evaluation.target;

/** Fixed, body-free error used by the run orchestrator to decide whether a retry is safe. */
public final class EvaluationTargetException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final Integer statusCode;

    private EvaluationTargetException(String code, boolean retryable, Integer statusCode) {
        super("evaluation target error: " + code, null, false, false);
        this.code = code;
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    static EvaluationTargetException configuration() { return new EvaluationTargetException("CONFIGURATION_ERROR", false, null); }
    static EvaluationTargetException invalidResponse() { return new EvaluationTargetException("INVALID_RESPONSE", false, null); }
    static EvaluationTargetException interrupted() { return new EvaluationTargetException("INTERRUPTED", false, null); }
    static EvaluationTargetException network() { return new EvaluationTargetException("NETWORK_ERROR", true, null); }
    static EvaluationTargetException timeout() { return new EvaluationTargetException("TIMEOUT", true, null); }
    static EvaluationTargetException http(int statusCode, boolean retryable) {
        return new EvaluationTargetException("UPSTREAM_HTTP_ERROR", retryable, statusCode);
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public Integer statusCode() { return statusCode; }
}
