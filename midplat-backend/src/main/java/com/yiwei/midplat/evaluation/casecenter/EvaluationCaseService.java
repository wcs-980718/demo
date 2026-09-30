package com.yiwei.midplat.evaluation.casecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.DomainAssertions;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.platform.PlatformService;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationCaseService {

    private static final URI SAFE_CASE_INSTANCE = URI.create("/api/evaluation/cases");

    private final EvaluationCaseRepository repository;
    private final PlatformService platformService;
    private final ObjectMapper objectMapper;
    private final EvaluationAuditService auditService;
    private final EvaluationCaseImportWriter importWriter;

    EvaluationCaseService(
            EvaluationCaseRepository repository,
            PlatformService platformService,
            ObjectMapper objectMapper,
            EvaluationAuditService auditService,
            EvaluationCaseImportWriter importWriter) {
        this.repository = repository;
        this.platformService = platformService;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.importWriter = importWriter;
    }

    @Transactional
    public CaseView create(CreateCaseCmd cmd) {
        String expectedJson = writeExpected(cmd.expected());
        validateContent(
                cmd.platformId(), cmd.name(), cmd.category(), null, cmd.inputText(), expectedJson);
        validatePlatform(cmd.platformId());
        String contentHash = EvaluationCaseHasher.hash(
                cmd.platformId(),
                cmd.name(),
                cmd.category(),
                cmd.severity(),
                CaseSourceType.MANUAL,
                null,
                cmd.inputText(),
                expectedJson,
                cmd.evaluatorType());
        EvaluationCase entity = EvaluationCase.createManual(
                Identities.newId(),
                blankToNull(cmd.platformId()),
                cmd.name(),
                cmd.category(),
                cmd.severity(),
                cmd.inputText(),
                expectedJson,
                cmd.evaluatorType(),
                contentHash);
        EvaluationCase saved = repository.save(entity);
        auditService.record("CASE_CREATED", saved, "MANUAL");
        repository.flush();
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public CaseView get(String id) {
        return toView(require(id));
    }

    @Transactional(readOnly = true)
    public CasePage list(CaseFilter filter, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page 不能小于 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        }
        CaseLifecycleStatus lifecycleStatus = filter.lifecycleStatus() == null
                ? CaseLifecycleStatus.ACTIVE
                : filter.lifecycleStatus();
        Page<EvaluationCaseSummaryProjection> result = repository.searchSummaries(
                blankToNull(filter.platformId()),
                blankToNull(filter.category()),
                filter.severity(),
                filter.reviewStatus(),
                lifecycleStatus,
                PageRequest.of(page, size));
        List<CaseSummaryView> items = result.getContent()
                .stream()
                .map(this::toSummaryView)
                .toList();
        return new CasePage(
                items,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional
    public CaseView update(String id, UpdateCaseCmd cmd) {
        EvaluationCase entity = require(id);
        String expectedJson = writeExpected(cmd.expected());
        validateContent(
                cmd.platformId(), cmd.name(), cmd.category(), entity.getSourceRef(), cmd.inputText(), expectedJson);
        validatePlatform(cmd.platformId());
        String contentHash = EvaluationCaseHasher.hash(
                cmd.platformId(),
                cmd.name(),
                cmd.category(),
                cmd.severity(),
                entity.getSourceType(),
                entity.getSourceRef(),
                cmd.inputText(),
                expectedJson,
                cmd.evaluatorType());
        entity.updateDraft(
                cmd.expectedVersion(),
                blankToNull(cmd.platformId()),
                cmd.name(),
                cmd.category(),
                cmd.severity(),
                cmd.inputText(),
                expectedJson,
                cmd.evaluatorType(),
                contentHash);
        auditService.record("CASE_UPDATED", entity, "MANUAL");
        repository.flush();
        return toView(entity);
    }

    @Transactional
    public CaseView review(String id, ReviewDecision decision, long expectedVersion) {
        EvaluationCase entity = require(id);
        entity.review(decision, expectedVersion);
        auditService.record(
                decision == ReviewDecision.APPROVE ? "CASE_REVIEWED" : "CASE_REJECTED",
                entity,
                "MANUAL");
        repository.flush();
        return toView(entity);
    }

    @Transactional
    public CaseView archive(String id, long expectedVersion) {
        EvaluationCase entity = require(id);
        entity.archive(expectedVersion);
        auditService.record("CASE_ARCHIVED", entity, "MANUAL");
        repository.flush();
        return toView(entity);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CaseView importCase(ImportCaseCmd cmd) {
        if (cmd.sourceType() == CaseSourceType.MANUAL) {
            throw new IllegalArgumentException("导入案例不能使用 MANUAL 来源");
        }
        String sourceRef = DomainAssertions.requireText(cmd.sourceRef(), "sourceRef 不能为空");
        String expectedJson = writeExpected(cmd.expected());
        validateContent(
                cmd.platformId(), cmd.name(), cmd.category(), sourceRef, cmd.inputText(), expectedJson);
        validatePlatform(cmd.platformId());
        String contentHash = EvaluationCaseHasher.hash(
                cmd.platformId(),
                cmd.name(),
                cmd.category(),
                cmd.severity(),
                cmd.sourceType(),
                sourceRef,
                cmd.inputText(),
                expectedJson,
                cmd.evaluatorType());

        Optional<EvaluationCase> existing = repository.findBySourceTypeAndSourceRef(
                cmd.sourceType(), sourceRef);
        if (existing.isPresent()) {
            return resolveImported(existing.get(), contentHash);
        }

        EvaluationCase entity = EvaluationCase.createImported(
                Identities.newId(),
                blankToNull(cmd.platformId()),
                cmd.name(),
                cmd.category(),
                cmd.severity(),
                cmd.sourceType(),
                sourceRef,
                cmd.inputText(),
                expectedJson,
                cmd.evaluatorType(),
                contentHash);
        return resolveImported(
                importWriter.insertOrGet(entity, cmd.sourceType().name()),
                contentHash);
    }

    private CaseView resolveImported(EvaluationCase existing, String contentHash) {
        if (!existing.getContentHash().equals(contentHash)) {
            throw new ConflictException("同一来源引用已存在不同内容");
        }
        return toView(existing);
    }

    private EvaluationCase require(String id) {
        return repository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("评测案例不存在", SAFE_CASE_INSTANCE));
    }

    private void validatePlatform(String platformId) {
        String normalized = blankToNull(platformId);
        if (normalized != null) {
            try {
                platformService.get(normalized);
            } catch (ResourceNotFoundException ex) {
                throw new ResourceNotFoundException("评测案例所属平台不存在", SAFE_CASE_INSTANCE);
            }
        }
    }

    private void validateContent(
            String platformId,
            String name,
            String category,
            String sourceRef,
            String inputText,
            String expectedJson) {
        EvaluationSensitiveDataGuard.requireMaxLength("platformId", platformId, 64);
        EvaluationSensitiveDataGuard.requireMaxLength("name", name, 128);
        EvaluationSensitiveDataGuard.requireMaxLength("category", category, 64);
        EvaluationSensitiveDataGuard.requireMaxLength("sourceRef", sourceRef, 256);
        EvaluationSensitiveDataGuard.requireMaxLength(
                "inputText", inputText, EvaluationSensitiveDataGuard.MAX_CASE_CONTENT_LENGTH);
        EvaluationSensitiveDataGuard.requireMaxLength(
                "expected", expectedJson, EvaluationSensitiveDataGuard.MAX_CASE_CONTENT_LENGTH);
        EvaluationSensitiveDataGuard.requireDesensitized("platformId", platformId);
        EvaluationSensitiveDataGuard.requireDesensitized("name", name);
        EvaluationSensitiveDataGuard.requireDesensitized("category", category);
        EvaluationSensitiveDataGuard.requireDesensitized("sourceRef", sourceRef);
        EvaluationSensitiveDataGuard.requireDesensitized("inputText", inputText);
        EvaluationSensitiveDataGuard.requireDesensitized("expected", expectedJson);
    }

    private String writeExpected(JsonNode expected) {
        Objects.requireNonNull(expected, "expected 不能为空");
        try {
            return objectMapper.writeValueAsString(canonicalize(expected));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("expected 不是有效 JSON", ex);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> canonical.set(name, canonicalize(node.get(name))));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        return node.deepCopy();
    }

    private JsonNode readExpected(String expectedJson) {
        try {
            return objectMapper.readTree(expectedJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("案例 expected JSON 无法解析", ex);
        }
    }

    private CaseView toView(EvaluationCase entity) {
        return new CaseView(
                entity.getId(),
                entity.getPlatformId(),
                entity.getName(),
                entity.getCategory(),
                entity.getSeverity(),
                entity.getSourceType(),
                entity.getSourceRef(),
                entity.getInputText(),
                readExpected(entity.getExpectedJson()),
                entity.getEvaluatorType(),
                entity.getReviewStatus(),
                entity.getLifecycleStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }

    private CaseSummaryView toSummaryView(EvaluationCaseSummaryProjection entity) {
        return new CaseSummaryView(
                entity.getId(),
                entity.getPlatformId(),
                entity.getName(),
                entity.getCategory(),
                entity.getSeverity(),
                entity.getSourceType(),
                entity.getSourceRef(),
                entity.getEvaluatorType(),
                entity.getReviewStatus(),
                entity.getLifecycleStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateCaseCmd(
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            String inputText,
            JsonNode expected,
            EvaluatorType evaluatorType) {
        @Override public String toString() { return "CreateCaseCmd[name=" + name + ", inputText=[redacted], expected=[redacted]]"; }
    }

    public record UpdateCaseCmd(
            long expectedVersion,
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            String inputText,
            JsonNode expected,
            EvaluatorType evaluatorType) {
        @Override public String toString() { return "UpdateCaseCmd[name=" + name + ", inputText=[redacted], expected=[redacted]]"; }
    }

    public record ImportCaseCmd(
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            CaseSourceType sourceType,
            String sourceRef,
            String inputText,
            JsonNode expected,
            EvaluatorType evaluatorType) {
        @Override public String toString() { return "ImportCaseCmd[name=" + name + ", inputText=[redacted], expected=[redacted]]"; }
    }

    public record CaseFilter(
            String platformId,
            String category,
            CaseSeverity severity,
            CaseReviewStatus reviewStatus,
            CaseLifecycleStatus lifecycleStatus) {}

    public record CaseView(
            String id,
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            CaseSourceType sourceType,
            String sourceRef,
            String inputText,
            JsonNode expected,
            EvaluatorType evaluatorType,
            CaseReviewStatus reviewStatus,
            CaseLifecycleStatus lifecycleStatus,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        @Override public String toString() { return "CaseView[id=" + id + ", name=" + name + ", inputText=[redacted], expected=[redacted]]"; }
    }

    public record CaseSummaryView(
            String id,
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            CaseSourceType sourceType,
            String sourceRef,
            EvaluatorType evaluatorType,
            CaseReviewStatus reviewStatus,
            CaseLifecycleStatus lifecycleStatus,
            Instant createdAt,
            Instant updatedAt,
            long version) {}

    public record CasePage(
            List<CaseSummaryView> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {}
}
