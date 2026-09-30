package com.yiwei.midplat.evaluation.casecenter;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EvaluationCaseRepository extends JpaRepository<EvaluationCase, String> {

    Optional<EvaluationCase> findBySourceTypeAndSourceRef(
            CaseSourceType sourceType,
            String sourceRef);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EvaluationCase c where c.id in :ids order by c.id asc")
    List<EvaluationCase> findAllByIdInForUpdate(@Param("ids") Collection<String> ids);

    @Query("select c.id from EvaluationCase c where c.id in :ids")
    Set<String> findIdsByIdIn(@Param("ids") Collection<String> ids);

    @Query(value = """
            select c.id as id,
                   c.platformId as platformId,
                   c.name as name,
                   c.category as category,
                   c.severity as severity,
                   c.sourceType as sourceType,
                   c.sourceRef as sourceRef,
                   c.evaluatorType as evaluatorType,
                   c.reviewStatus as reviewStatus,
                   c.lifecycleStatus as lifecycleStatus,
                   c.createdAt as createdAt,
                   c.updatedAt as updatedAt,
                   c.version as version
            from EvaluationCase c
            where (:platformId is null or c.platformId = :platformId)
              and (:category is null or c.category = :category)
              and (:severity is null or c.severity = :severity)
              and (:reviewStatus is null or c.reviewStatus = :reviewStatus)
              and (:lifecycleStatus is null or c.lifecycleStatus = :lifecycleStatus)
            order by c.updatedAt desc, c.id desc
            """, countQuery = """
            select count(c) from EvaluationCase c
            where (:platformId is null or c.platformId = :platformId)
              and (:category is null or c.category = :category)
              and (:severity is null or c.severity = :severity)
              and (:reviewStatus is null or c.reviewStatus = :reviewStatus)
              and (:lifecycleStatus is null or c.lifecycleStatus = :lifecycleStatus)
            """)
    Page<EvaluationCaseSummaryProjection> searchSummaries(
            @Param("platformId") String platformId,
            @Param("category") String category,
            @Param("severity") CaseSeverity severity,
            @Param("reviewStatus") CaseReviewStatus reviewStatus,
            @Param("lifecycleStatus") CaseLifecycleStatus lifecycleStatus,
            Pageable pageable);
}

interface EvaluationCaseSummaryProjection {

    String getId();

    String getPlatformId();

    String getName();

    String getCategory();

    CaseSeverity getSeverity();

    CaseSourceType getSourceType();

    String getSourceRef();

    EvaluatorType getEvaluatorType();

    CaseReviewStatus getReviewStatus();

    CaseLifecycleStatus getLifecycleStatus();

    Instant getCreatedAt();

    Instant getUpdatedAt();

    long getVersion();
}
