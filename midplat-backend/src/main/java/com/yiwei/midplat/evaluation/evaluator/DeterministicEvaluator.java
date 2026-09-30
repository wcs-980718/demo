package com.yiwei.midplat.evaluation.evaluator;

@FunctionalInterface
public interface DeterministicEvaluator {
    EvaluationOutcome evaluate(String expectedJson, String actualOutput);
}
