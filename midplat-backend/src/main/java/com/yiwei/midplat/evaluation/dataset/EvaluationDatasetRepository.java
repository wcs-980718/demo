package com.yiwei.midplat.evaluation.dataset;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

interface EvaluationDatasetRepository extends Repository<EvaluationDataset, String> {

    <S extends EvaluationDataset> S save(S dataset);

    Optional<EvaluationDataset> findById(String id);

    List<EvaluationDataset> findAllByOrderByUpdatedAtDesc();

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from EvaluationDataset d where d.id = :id")
    Optional<EvaluationDataset> findByIdForUpdate(@Param("id") String id);
}
