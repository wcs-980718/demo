package com.yiwei.midplat.evaluation.run;

import java.math.BigDecimal;

/** Explicit allow-list for persisted execution parameters. */
public record EvaluationRunParameters(BigDecimal temperature, Integer maxTokens, Integer timeoutMs) {
    public EvaluationRunParameters {
        if (temperature == null || temperature.signum() < 0) throw new IllegalArgumentException("temperature 必须非负");
        if (maxTokens == null || maxTokens < 1) throw new IllegalArgumentException("maxTokens 必须大于 0");
        if (timeoutMs == null || timeoutMs < 1) throw new IllegalArgumentException("timeoutMs 必须大于 0");
    }
}
