package com.yiwei.midplat.evaluation.run;

import com.yiwei.midplat.common.api.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
final class JpaEvaluationRunDecisionStateLockReader implements EvaluationRunDecisionStateLockReader {

    private static final String LOCK_RUN = "select id from midplat_eval_run where id = :runId for update";
    private static final String LOCK_RESULTS = "select id from midplat_eval_result where run_id = :runId "
            + "order by order_no asc, id asc for update";

    private final EntityManager entityManager;
    private final EvaluationRunComparisonReader comparisons;

    JpaEvaluationRunDecisionStateLockReader(EntityManager entityManager,
            EvaluationRunComparisonReader comparisons) {
        this.entityManager = entityManager;
        this.comparisons = comparisons;
    }

    @Override
    public String lockAndReadFingerprint(String runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("候选决策状态锁必须在可写事务中获取");
        }
        List<?> lockedRun = entityManager.createNativeQuery(LOCK_RUN)
                .setParameter("runId", runId)
                .getResultList();
        if (lockedRun.isEmpty()) throw new ResourceNotFoundException("evaluation run not found");
        entityManager.createNativeQuery(LOCK_RESULTS)
                .setParameter("runId", runId)
                .getResultList();
        return EvaluationRunDecisionStateFingerprint.from(comparisons.find(runId));
    }
}
