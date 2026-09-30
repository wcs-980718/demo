package com.yiwei.midplat.evaluation.casecenter;

import java.util.List;
import org.springframework.data.repository.Repository;

interface EvaluationAuditLogRepository extends Repository<EvaluationAuditLog, String> {

    EvaluationAuditLog save(EvaluationAuditLog auditLog);

    List<EvaluationAuditLog> findAllByResourceIdOrderByOccurredAtAsc(String resourceId);

    long count();
}
