package com.yiwei.midplat.evaluation.dataset;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

interface EvaluationDatasetVersionRepository extends Repository<EvaluationDatasetVersion, String> {

    <S extends EvaluationDatasetVersion> S save(S version);

    Optional<EvaluationDatasetVersion> findById(String id);

    List<EvaluationDatasetVersion> findAllByDatasetIdOrderByVersionNoDesc(String datasetId);

    boolean existsByDatasetIdAndStatus(String datasetId, DatasetVersionStatus status);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EvaluationDatasetVersion v where v.id = :id")
    Optional<EvaluationDatasetVersion> findByIdForUpdate(@Param("id") String id);
}
