package com.yiwei.midplat.evaluation.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.evaluation.evaluator.EvaluationEvaluatorRegistry;
import com.yiwei.midplat.evaluation.evaluator.EvaluationOutcome;
import com.yiwei.midplat.evaluation.target.EvaluationTargetException;
import com.yiwei.midplat.evaluation.target.EvaluationTargetExecutor;
import com.yiwei.midplat.evaluation.target.EvaluationTargetRequest;
import com.yiwei.midplat.evaluation.target.EvaluationTargetResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EvaluationRunExecutionService {
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal MAX_NUMERIC_20_8 = new BigDecimal("999999999999.99999999");
    private final EvaluationRunStateService state;
    private final EvaluationTargetExecutor target;
    private final ObjectMapper objectMapper;
    private final EvaluationEvaluatorRegistry evaluators;

    EvaluationRunExecutionService(EvaluationRunStateService state, EvaluationTargetExecutor target, ObjectMapper objectMapper,
            EvaluationEvaluatorRegistry evaluators) {
        this.state = state; this.target = target; this.objectMapper = objectMapper; this.evaluators = evaluators;
    }

    public void execute(String runId) {
        try {
            if (!state.start(runId)) return;
            for (String resultId : state.resultIds(runId)) {
                EvaluationRunStateService.ExecutionContext context = state.startResult(runId, resultId);
                if (context == null) continue;
                EvaluationRunStateService.Completion completion = executeCase(context);
                state.store(runId, resultId, completion);
                if ("CONFIGURATION_ERROR".equals(completion.errorCode)) { state.failSharedConfiguration(runId); break; }
                if ("COST_OVERFLOW".equals(completion.errorCode)) {
                    state.failFatal(runId, "COST_OVERFLOW", "评测成本超出范围");
                    break;
                }
            }
            state.finish(runId);
        } catch (RuntimeException unexpected) {
            state.failUnexpected(runId);
        }
    }

    private EvaluationRunStateService.Completion executeCase(EvaluationRunStateService.ExecutionContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("external execution must not run in transaction");
        EvaluationTargetResponse response = null;
        EvaluationTargetException targetError = null;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            try {
                response = target.execute(new EvaluationTargetRequest(context.target.modelId(), context.target.baseUrl(), context.target.modelName(),
                        context.target.promptBody(), context.input, context.parameters.temperature().doubleValue(),
                        context.parameters.maxTokens(), context.parameters.timeoutMs()));
                break;
            } catch (EvaluationTargetException error) {
                targetError = error;
                if (!error.retryable()) break;
            } catch (RuntimeException unexpected) {
                targetError = null;
                break;
            }
        }
        if (response == null) {
            String code = targetError == null ? "TARGET_EXECUTION_FAILED" : targetError.code();
            return new EvaluationRunStateService.Completion(EvaluationResultStatus.ERROR, null, null, null, false,
                    code, "评测目标执行失败", null, 0, 0, 0, 0, BigDecimal.ZERO, attempts);
        }
        try {
            BigDecimal cost = cost(response, context.target);
            String trace = objectMapper.writeValueAsString(response.traceSummary());
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("scoring must not run in transaction");
            EvaluationOutcome outcome = evaluators.evaluate(context.evaluatorType, context.expectedJson, response.output());
            if (outcome.errorCode() != null) {
                return new EvaluationRunStateService.Completion(EvaluationResultStatus.ERROR, response.output(), null, null, false,
                        outcome.errorCode(), "评分配置无效", trace, response.traceSummary().latencyMs(), response.promptTokens(),
                        response.completionTokens(), response.totalTokens(), cost, attempts);
            }
            if (outcome.reviewRequired()) {
                return new EvaluationRunStateService.Completion(EvaluationResultStatus.PENDING, response.output(), null, null, true,
                        null, null, trace, response.traceSummary().latencyMs(), response.promptTokens(), response.completionTokens(),
                        response.totalTokens(), cost, attempts);
            }
            boolean passed = Boolean.TRUE.equals(outcome.passed());
            return new EvaluationRunStateService.Completion(passed ? EvaluationResultStatus.PASSED : EvaluationResultStatus.FAILED,
                    response.output(), outcome.score(), passed, false, null, null, trace, response.traceSummary().latencyMs(),
                    response.promptTokens(), response.completionTokens(), response.totalTokens(), cost, attempts);
        } catch (ArithmeticException overflow) {
            return new EvaluationRunStateService.Completion(EvaluationResultStatus.ERROR, response.output(), null, null, false,
                    "COST_OVERFLOW", "评测成本超出范围", null, response.traceSummary().latencyMs(), response.promptTokens(),
                    response.completionTokens(), response.totalTokens(), BigDecimal.ZERO, attempts);
        } catch (Exception ignored) {
            return new EvaluationRunStateService.Completion(EvaluationResultStatus.ERROR, response.output(), null, null, false,
                    "RESULT_PROCESSING_FAILED", "评测结果处理失败", null, response.traceSummary().latencyMs(), response.promptTokens(),
                    response.completionTokens(), response.totalTokens(), BigDecimal.ZERO, attempts);
        }
    }

    private static BigDecimal cost(EvaluationTargetResponse response, EvaluationTargetSnapshot snapshot) {
        BigDecimal input = BigDecimal.valueOf(response.promptTokens()).multiply(snapshot.inputPricePerMillion()).divide(MILLION, 16, RoundingMode.HALF_UP);
        BigDecimal output = BigDecimal.valueOf(response.completionTokens()).multiply(snapshot.outputPricePerMillion()).divide(MILLION, 16, RoundingMode.HALF_UP);
        BigDecimal cost = input.add(output).setScale(8, RoundingMode.HALF_UP);
        if (cost.signum() < 0 || cost.compareTo(MAX_NUMERIC_20_8) > 0) throw new ArithmeticException("cost");
        return cost;
    }
}
