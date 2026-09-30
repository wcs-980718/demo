package com.yiwei.midplat.evaluation.gate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Append-only persistence boundary: no generic save, update, delete, or batch surface exists. */
@Repository
class EvaluationGateDecisionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    EvaluationGateDecision append(EvaluationGateDecision decision) {
        entityManager.persist(decision);
        entityManager.flush();
        return decision;
    }

    Optional<EvaluationGateDecision> findById(String id) {
        return Optional.ofNullable(entityManager.find(EvaluationGateDecision.class, id));
    }

    List<EvaluationGateDecision> findAllByRunIdOrderByDecidedAtDescIdAsc(String runId) {
        return entityManager.createQuery("""
                select decision from EvaluationGateDecision decision
                 where decision.runId = :runId
                 order by decision.decidedAt desc, decision.id asc
                """, EvaluationGateDecision.class).setParameter("runId", runId).getResultList();
    }

    long countByRunId(String runId) {
        return entityManager.createQuery("select count(decision) from EvaluationGateDecision decision where decision.runId = :runId", Long.class)
                .setParameter("runId", runId).getSingleResult();
    }

    long count() {
        return entityManager.createQuery("select count(decision) from EvaluationGateDecision decision", Long.class).getSingleResult();
    }
}
