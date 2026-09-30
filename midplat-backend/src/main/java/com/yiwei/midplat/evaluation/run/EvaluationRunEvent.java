package com.yiwei.midplat.evaluation.run;

/** Published inside the create/cancel transaction and consumed only after commit. */
public record EvaluationRunEvent(String runId) {}
