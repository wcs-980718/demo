package com.yiwei.midplat.evaluation.dataset;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

interface EvaluationDatasetItemRepository extends Repository<EvaluationDatasetItem, String> {

    <S extends EvaluationDatasetItem> S save(S item);

    Optional<EvaluationDatasetItem> findById(String id);

    List<EvaluationDatasetItem> findAllByVersionIdOrderByOrderNoAsc(String versionId);

    @Modifying
    @Query("delete from EvaluationDatasetItem i where i.versionId = :versionId")
    void removeAllForVersion(@Param("versionId") String versionId);
}
