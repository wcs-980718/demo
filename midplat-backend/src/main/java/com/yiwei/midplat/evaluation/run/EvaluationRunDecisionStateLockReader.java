package com.yiwei.midplat.evaluation.run;

/** Minimal write-transaction port that locks body-free candidate state and returns its fingerprint. */
public interface EvaluationRunDecisionStateLockReader {
    String lockAndReadFingerprint(String runId);
}
