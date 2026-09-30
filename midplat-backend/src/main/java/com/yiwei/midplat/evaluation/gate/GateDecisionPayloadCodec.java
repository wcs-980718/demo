package com.yiwei.midplat.evaluation.gate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Gate-owned safe JSON codec. There is intentionally no API that accepts raw JSON text. */
final class GateDecisionPayloadCodec {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_POLICY_BYTES = 8 * 1024;
    private static final int MAX_EVIDENCE_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> POLICY_FIELDS = Set.of("schemaVersion", "policyId", "policyVersion", "name",
            "platformId", "category", "minPassRate", "maxCostGrowthPercent", "maxAverageLatencyMs",
            "maxP95LatencyMs", "requireCriticalCasesPassed", "enabled");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("schemaVersion", "rules");
    private static final Set<String> RULE_FIELDS = Set.of("rule", "threshold", "actual", "failedCases");
    private static final Set<String> FAILED_CASE_FIELDS = Set.of("caseId", "caseName");
    private static final Set<String> RULES = Set.of("CRITICAL_CASES_PASSED", "EXECUTION_ERROR", "MANUAL_REVIEW",
            "MIN_PASS_RATE", "MAX_COST_GROWTH_PERCENT", "MAX_AVG_LATENCY_MS", "MAX_P95_LATENCY_MS");

    private GateDecisionPayloadCodec() {}

    static String policySnapshot(EvaluationGatePolicy policy) {
        if (policy == null) throw new IllegalArgumentException("策略不能为空");
        return encode(new PolicySnapshot(SCHEMA_VERSION, policy.getId(), policy.getVersion(), policy.getName(),
                policy.getPlatformId(), policy.getCategory(), policy.getMinPassRate(), policy.getMaxCostGrowthPercent(),
                policy.getMaxAverageLatencyMs(), policy.getMaxP95LatencyMs(), policy.isRequireCriticalCasesPassed(),
                policy.isEnabled()), true);
    }

    static String evidenceSkeleton() {
        return evidence(new EvidenceSnapshot(SCHEMA_VERSION, List.of()));
    }

    static String evidence(EvidenceSnapshot evidence) {
        return encode(normalizeEvidence(evidence), false);
    }

    static JsonNode decodeStoredEvidence(String storedJson) {
        if (storedJson == null || storedJson.getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
            throw new IllegalStateException("门禁证据存储无效");
        }
        try {
            JsonNode root = JSON.readTree(storedJson);
            validateEvidenceNode(root);
            return root.deepCopy();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("门禁证据存储无效", exception);
        }
    }

    private static String encode(Object payload, boolean policy) {
        try {
            String text = JSON.writeValueAsString(payload);
            int maxBytes = policy ? MAX_POLICY_BYTES : MAX_EVIDENCE_BYTES;
            if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException("门禁 JSON 超出字节上限");
            }
            JsonNode root = JSON.readTree(text);
            if (policy) validatePolicy(root); else validateEvidenceNode(root);
            return text;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("门禁快照 JSON 无效", exception);
        }
    }

    private static void validatePolicy(JsonNode root) {
        requireExactFields(root, POLICY_FIELDS, "策略快照");
        requireVersion(root);
        requireText(root, "policyId", 64);
        if (!root.path("policyVersion").canConvertToLong() || root.path("policyVersion").longValue() < 0) {
            throw new IllegalArgumentException("策略版本无效");
        }
        requireText(root, "name", 128);
        optionalText(root, "platformId", 64);
        optionalText(root, "category", 64);
        requireNonnegativeNumber(root, "minPassRate");
        requireNonnegativeNumber(root, "maxCostGrowthPercent");
        if (root.path("minPassRate").decimalValue().compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("最低通过率无效");
        }
        requireNonnegativeLong(root, "maxAverageLatencyMs");
        requireNonnegativeLong(root, "maxP95LatencyMs");
        requireBoolean(root, "requireCriticalCasesPassed");
        requireBoolean(root, "enabled");
    }

    private static EvidenceSnapshot normalizeEvidence(EvidenceSnapshot evidence) {
        if (evidence == null || evidence.schemaVersion() != SCHEMA_VERSION || evidence.rules() == null) {
            throw new IllegalArgumentException("证据结构无效");
        }
        if (evidence.rules().size() > 16) throw new IllegalArgumentException("证据规则过多");
        List<RuleEvidence> normalizedRules = new java.util.ArrayList<>(evidence.rules().size());
        for (RuleEvidence rule : evidence.rules()) {
            if (rule == null || rule.rule() == null || rule.failedCases() == null || rule.failedCases().size() > 100) {
                throw new IllegalArgumentException("证据规则无效");
            }
            String normalizedRule = text(rule.rule(), "rule", 64);
            if (!RULES.contains(normalizedRule)) throw new IllegalArgumentException("证据规则不在白名单");
            nonnegative(rule.threshold(), "证据阈值");
            if (!"MAX_COST_GROWTH_PERCENT".equals(normalizedRule)) {
                nonnegative(rule.actual(), "证据实际值");
            }
            List<FailedCaseEvidence> normalizedCases = new java.util.ArrayList<>(rule.failedCases().size());
            for (FailedCaseEvidence failedCase : rule.failedCases()) {
                if (failedCase == null) {
                    throw new IllegalArgumentException("失败案例证据无效");
                }
                normalizedCases.add(new FailedCaseEvidence(text(failedCase.caseId(), "caseId", 64),
                        text(failedCase.caseName(), "caseName", 128)));
            }
            normalizedRules.add(new RuleEvidence(normalizedRule, normalizeNumber(rule.threshold()),
                    normalizeNumber(rule.actual()), List.copyOf(normalizedCases)));
        }
        return new EvidenceSnapshot(SCHEMA_VERSION, List.copyOf(normalizedRules));
    }

    private static void validateEvidenceNode(JsonNode root) {
        requireExactFields(root, EVIDENCE_FIELDS, "证据");
        requireVersion(root);
        JsonNode rules = root.path("rules");
        if (!rules.isArray() || rules.size() > 16) throw new IllegalArgumentException("证据规则无效");
        for (JsonNode rule : rules) {
            requireExactFields(rule, RULE_FIELDS, "证据规则");
            String name = requireText(rule, "rule", 64);
            if (!RULES.contains(name)) throw new IllegalArgumentException("证据规则不在白名单");
            optionalNumber(rule, "threshold", false);
            optionalNumber(rule, "actual", "MAX_COST_GROWTH_PERCENT".equals(name));
            JsonNode failedCases = rule.path("failedCases");
            if (!failedCases.isArray() || failedCases.size() > 100) throw new IllegalArgumentException("失败案例证据无效");
            for (JsonNode failedCase : failedCases) {
                requireExactFields(failedCase, FAILED_CASE_FIELDS, "失败案例");
                requireText(failedCase, "caseId", 64);
                requireText(failedCase, "caseName", 128);
            }
        }
    }

    private static void requireVersion(JsonNode root) {
        if (!root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").intValue() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("JSON 版本无效");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String label) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || node.size() != expected.size() || !actual.equals(expected)) {
            throw new IllegalArgumentException(label + " 字段不在白名单");
        }
    }

    private static String requireText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !GateSafeMetadata.isSafe(value.textValue(), maxLength)) {
            throw new IllegalArgumentException(field + " 无效");
        }
        return value.textValue();
    }

    private static void optionalText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull()
                && (!value.isTextual() || !GateSafeMetadata.isSafe(value.textValue(), maxLength))) {
            throw new IllegalArgumentException(field + " 无效");
        }
    }

    private static void requireNonnegativeNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || value.decimalValue().signum() < 0) throw new IllegalArgumentException(field + " 无效");
    }

    private static void optionalNumber(JsonNode node, String field, boolean allowNegative) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull()
                && (!value.isNumber() || (!allowNegative && value.decimalValue().signum() < 0))) {
            throw new IllegalArgumentException(field + " 无效");
        }
    }

    private static void requireNonnegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) throw new IllegalArgumentException(field + " 无效");
    }

    private static void requireBoolean(JsonNode node, String field) {
        if (node.get(field) == null || !node.get(field).isBoolean()) throw new IllegalArgumentException(field + " 无效");
    }

    private static void nonnegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) throw new IllegalArgumentException(label + " 不能为负数");
    }

    private static String text(String value, String field, int maxLength) {
        return GateSafeMetadata.required(value, field, maxLength);
    }

    static String safeCaseName(String value) {
        return GateSafeMetadata.redactCaseName(value);
    }

    private static BigDecimal normalizeNumber(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    record EvidenceSnapshot(int schemaVersion, List<RuleEvidence> rules) {}
    record RuleEvidence(String rule, BigDecimal threshold, BigDecimal actual, List<FailedCaseEvidence> failedCases) {}
    record FailedCaseEvidence(String caseId, String caseName) {}
    private record PolicySnapshot(int schemaVersion, String policyId, long policyVersion, String name, String platformId,
            String category, BigDecimal minPassRate, BigDecimal maxCostGrowthPercent, long maxAverageLatencyMs,
            long maxP95LatencyMs, boolean requireCriticalCasesPassed, boolean enabled) {}
}
