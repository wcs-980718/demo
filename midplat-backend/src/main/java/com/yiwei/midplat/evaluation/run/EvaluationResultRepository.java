package com.yiwei.midplat.evaluation.run;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface EvaluationResultRepository extends Repository<EvaluationResult, String> {
    <S extends EvaluationResult> S save(S result);
    Optional<EvaluationResult> findById(String id);
    List<EvaluationResult> findAllByRunIdOrderByOrderNoAsc(String runId);
    @Query("""
            select r.id as resultId, r.caseId as caseId, r.orderNo as orderNo, r.snapshotName as name, r.snapshotSeverity as severity,
                   r.snapshotCategory as snapshotCategory,
                   r.snapshotContentHash as snapshotContentHash, r.status as status, r.reviewRequired as reviewRequired,
                   r.score as score, r.version as version
              from EvaluationResult r where r.runId = :runId order by r.orderNo asc
            """)
    List<EvaluationRunComparisonReader.ResultRow> findComparisonRowsByRunId(@Param("runId") String runId);
    List<EvaluationResult> findAllByRunIdAndStatusOrderByOrderNoAsc(String runId, EvaluationResultStatus status);
    long countByRunIdAndStatus(String runId, EvaluationResultStatus status);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EvaluationResult r where r.id = :id")
    Optional<EvaluationResult> findByIdForUpdate(@Param("id") String id);
}
