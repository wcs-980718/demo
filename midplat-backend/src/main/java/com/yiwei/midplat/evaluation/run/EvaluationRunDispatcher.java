package com.yiwei.midplat.evaluation.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class EvaluationRunDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EvaluationRunDispatcher.class);
    private final EvaluationRunExecutionService execution;
    private final TaskExecutor executor;

    EvaluationRunDispatcher(EvaluationRunExecutionService execution, @org.springframework.beans.factory.annotation.Qualifier("evaluationTaskExecutor") TaskExecutor executor) { this.execution = execution; this.executor = executor; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(EvaluationRunEvent event) {
        try {
            executor.execute(() -> execution.execute(event.runId()));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            log.warn("evaluation task dispatch rejected");
        }
    }
}
