package com.yiwei.midplat.evaluation.casecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.common.domain.Identities;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationAuditService {

    private static final Set<String> ALLOWED_SUMMARY_KEYS = Set.of(
            "name", "category", "severity", "reviewStatus", "lifecycleStatus",
            "versionNo", "status", "itemCount", "snapshotHash", "frozenAt", "sourceVersionNo",
            "enabled", "version", "policyId", "policyVersion", "conclusion");

    private final EvaluationAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public EvaluationAuditService(
            EvaluationAuditLogRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    void record(String action, EvaluationCase entity, String source) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", entity.getName());
        summary.put("category", entity.getCategory());
        summary.put("severity", entity.getSeverity().name());
        summary.put("reviewStatus", entity.getReviewStatus().name());
        summary.put("lifecycleStatus", entity.getLifecycleStatus().name());
        recordResource(action, "EVALUATION_CASE", entity.getId(), source, summary);
    }

    public void recordResource(
            String action,
            String resourceType,
            String resourceId,
            String source,
            Map<String, Object> summary) {
        repository.save(new EvaluationAuditLog(
                Identities.newId(),
                action,
                resourceType,
                resourceId,
                source,
                "system",
                writeSummary(summary),
                Instant.now()));
    }

    private String writeSummary(Map<String, Object> summary) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            if (!ALLOWED_SUMMARY_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("审计摘要包含不允许字段");
            }
            Object value = entry.getValue();
            if (!isScalar(value)) {
                throw new IllegalArgumentException("审计摘要只能包含标量元数据");
            }
            sanitized.put(entry.getKey(), value instanceof String text
                    ? EvaluationSensitiveDataGuard.sanitizeForAudit(text, 128)
                    : value);
        }
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("评测审计摘要无法序列化", ex);
        }
    }

    private boolean isScalar(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof Instant;
    }
}

@Service
class EvaluationCaseImportWriter {

    private static final String INSERT_CASE_SQL = """
            insert into midplat_eval_case (
                id, platform_id, name, category, severity,
                source_type, source_ref, input_text, expected_json,
                evaluator_type, review_status, content_hash, lifecycle_status,
                created_at, updated_at, version
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp, 0)
            on conflict do nothing
            """;

    private final EvaluationCaseRepository repository;
    private final EvaluationAuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    EvaluationCaseImportWriter(
            EvaluationCaseRepository repository,
            EvaluationAuditService auditService,
            JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    EvaluationCase insertOrGet(EvaluationCase entity, String source) {
        int inserted = jdbcTemplate.update(
                INSERT_CASE_SQL,
                entity.getId(),
                entity.getPlatformId(),
                entity.getName(),
                entity.getCategory(),
                entity.getSeverity().name(),
                entity.getSourceType().name(),
                entity.getSourceRef(),
                entity.getInputText(),
                entity.getExpectedJson(),
                entity.getEvaluatorType().name(),
                entity.getReviewStatus().name(),
                entity.getContentHash(),
                entity.getLifecycleStatus().name());

        EvaluationCase stored = inserted == 1
                ? repository.findById(entity.getId()).orElseThrow(
                        () -> new IllegalStateException("新导入的评测案例无法读取"))
                : repository.findBySourceTypeAndSourceRef(entity.getSourceType(), entity.getSourceRef())
                        .orElseThrow(() -> new IllegalStateException("并发导入的评测案例无法读取"));
        if (inserted == 1) {
            auditService.record("CASE_IMPORTED", stored, source);
            repository.flush();
        }
        return stored;
    }
}
