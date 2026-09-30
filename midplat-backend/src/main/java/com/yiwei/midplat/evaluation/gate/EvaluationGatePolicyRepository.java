package com.yiwei.midplat.evaluation.gate;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface EvaluationGatePolicyRepository extends Repository<EvaluationGatePolicy, String> {
    EvaluationGatePolicy save(EvaluationGatePolicy policy);
    Optional<EvaluationGatePolicy> findById(String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select policy from EvaluationGatePolicy policy where policy.id = :id")
    Optional<EvaluationGatePolicy> findByIdForUpdate(@Param("id") String id);
    List<EvaluationGatePolicy> findAllByOrderByNameAsc();
    long count();

    boolean existsByPlatformId(String platformId);
}
