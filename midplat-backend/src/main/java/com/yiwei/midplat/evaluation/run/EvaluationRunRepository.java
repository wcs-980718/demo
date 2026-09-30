package com.yiwei.midplat.evaluation.run;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

public interface EvaluationRunRepository extends Repository<EvaluationRun, String> {
    <S extends EvaluationRun> S save(S run);
    Optional<EvaluationRun> findById(String id);
    @Query("""
            select r.id as runId, r.platformId as platformId, r.status as status, r.version as version,
                   r.passedCount as passedCount, r.failedCount as failedCount, r.totalCost as totalCost,
                   r.avgLatencyMs as averageLatencyMs, r.p95LatencyMs as p95LatencyMs
              from EvaluationRun r
             where r.id = :id
            """)
    Optional<EvaluationRunComparisonReader.RunRow> findComparisonRowById(@Param("id") String id);
    List<EvaluationRun> findAllByStatusInOrderByCreatedAtAsc(Collection<EvaluationRunStatus> statuses);
    List<EvaluationRun> findAllByOrderByCreatedAtDesc();
    Page<EvaluationRun> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByStatus(EvaluationRunStatus status);
    boolean existsByModelId(String modelId);
    boolean existsByPromptId(String promptId);
    boolean existsByPlatformId(String platformId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EvaluationRun r where r.id = :id")
    Optional<EvaluationRun> findByIdForUpdate(@Param("id") String id);
}
