package com.yiwei.midplat.evaluation.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.DomainAssertions;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.evaluation.casecenter.EvaluationAuditService;
import com.yiwei.midplat.evaluation.experiment.EvaluationExperimentService;
import com.yiwei.midplat.evaluation.run.EvaluationResultStatus;
import com.yiwei.midplat.evaluation.run.EvaluationRunComparisonReader;
import com.yiwei.midplat.evaluation.run.EvaluationRunDecisionStateLockReader;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationGateService {

    private static final int EVIDENCE_CASE_LIMIT = 25;

    private final EvaluationGatePolicyRepository policies;
    private final EvaluationGateDecisionRepository decisions;
    private final EvaluationExperimentService experiments;
    private final EvaluationRunComparisonReader runs;
    private final EvaluationGateDecisionWriter writer;
    private final EvaluationAuditService audit;
    private final EntityManager entityManager;

    EvaluationGateService(EvaluationGatePolicyRepository policies, EvaluationGateDecisionRepository decisions,
            EvaluationExperimentService experiments, EvaluationRunComparisonReader runs,
            EvaluationGateDecisionWriter writer, EvaluationAuditService audit, EntityManager entityManager) {
        this.policies = policies;
        this.decisions = decisions;
        this.experiments = experiments;
        this.runs = runs;
        this.writer = writer;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<PolicyView> listPolicies() {
        return policies.findAllByOrderByNameAsc().stream()
                .sorted(Comparator.comparing(EvaluationGatePolicy::getName).thenComparing(EvaluationGatePolicy::getId))
                .map(EvaluationGateService::policyView).toList();
    }

    /** 只读：是否存在作用域引用该平台的门禁策略，供平台删除保护使用。 */
    @Transactional(readOnly = true)
    public boolean referencesPlatform(String platformId) {
        return policies.existsByPlatformId(platformId);
    }

    @Transactional
    public PolicyView create(CreatePolicyCommand command) {
        if (command == null) throw new IllegalArgumentException("门禁策略请求无效");
        EvaluationGatePolicy policy = policies.save(EvaluationGatePolicy.create(Identities.newId(), command.draft()));
        entityManager.flush();
        audit.recordResource("GATE_POLICY_CREATED", "EVALUATION_GATE_POLICY", policy.getId(), "MIDDLE_PLATFORM",
                policyAudit(policy));
        entityManager.flush();
        return policyView(policy);
    }

    @Transactional
    public PolicyView revise(String id, UpdatePolicyCommand command) {
        String policyId = id(id, "门禁策略 ID 无效");
        if (command == null || command.expectedVersion() < 0) throw new IllegalArgumentException("门禁策略请求无效");
        EvaluationGatePolicy policy = policy(policyId);
        policy.revise(command.draft(), command.expectedVersion());
        entityManager.flush();
        audit.recordResource("GATE_POLICY_UPDATED", "EVALUATION_GATE_POLICY", policy.getId(), "MIDDLE_PLATFORM",
                policyAudit(policy));
        entityManager.flush();
        return policyView(policy);
    }

    @Transactional(readOnly = true)
    public List<DecisionView> listDecisions(String runId) {
        String normalizedRunId = id(runId, "运行 ID 无效");
        runs.find(normalizedRunId);
        return decisions.findAllByRunIdOrderByDecidedAtDescIdAsc(normalizedRunId).stream()
                .map(EvaluationGateService::decisionView).toList();
    }

    /**
     * Intentionally non-transactional: compare must enter its own proxied read-only REPEATABLE_READ transaction.
     * The writer then opens a separate default-isolation transaction for decision and audit append.
     */
    public DecisionView decide(String candidateRunId, DecisionCommand command) {
        String candidateId = id(candidateRunId, "候选运行 ID 无效");
        if (command == null) throw new IllegalArgumentException("门禁决策请求无效");
        String baselineId = id(command.baselineRunId(), "基线运行 ID 无效");
        String policyId = id(command.policyId(), "门禁策略 ID 无效");
        if (baselineId.equals(candidateId)) throw new IllegalArgumentException("基线与候选运行不能相同");

        EvaluationGatePolicy selected = policy(policyId);
        if (!selected.isEnabled()) throw new ConflictException("停用的门禁策略不能生成决策");
        EvaluationExperimentService.Comparison comparison = experiments.compare(baselineId, candidateId);
        if (!baselineId.equals(comparison.baselineRunId()) || !candidateId.equals(comparison.candidateRunId())) {
            throw new IllegalStateException("实验比较返回了不匹配的运行");
        }
        CandidateScope candidateScope = candidateScope(comparison);
        requireScope(selected, candidateScope);
        GateEvaluation evaluation = evaluate(selected, comparison);
        String fingerprint = comparison.candidateDecisionStateFingerprint();
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("实验比较缺少候选决策状态指纹");
        }
        return writer.append(candidateId, selected.getId(), selected.getVersion(), fingerprint,
                candidateScope, evaluation);
    }

    private EvaluationGatePolicy policy(String id) {
        return policies.findById(id).orElseThrow(() -> new ResourceNotFoundException("评测门禁策略不存在"));
    }

    private static String id(String value, String message) {
        String normalized = DomainAssertions.requireText(value, message);
        if (normalized.length() > 64) throw new IllegalArgumentException(message);
        return normalized;
    }

    private static Map<String, Object> policyAudit(EvaluationGatePolicy policy) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", policy.getName());
        summary.put("category", policy.getCategory());
        summary.put("enabled", policy.isEnabled());
        summary.put("version", policy.getVersion());
        return summary;
    }

    static PolicyView policyView(EvaluationGatePolicy policy) {
        return new PolicyView(policy.getId(), policy.getName(), policy.getPlatformId(), policy.getCategory(),
                policy.getMinPassRate(), policy.getMaxCostGrowthPercent(), policy.getMaxAverageLatencyMs(),
                policy.getMaxP95LatencyMs(), policy.isRequireCriticalCasesPassed(), policy.isEnabled(),
                policy.getVersion(), policy.getCreatedAt(), policy.getUpdatedAt());
    }

    static DecisionView decisionView(EvaluationGateDecision decision) {
        return new DecisionView(decision.getId(), decision.getRunId(), decision.getPolicyId(), decision.getPolicyVersion(),
                decision.getConclusion(), GateDecisionPayloadCodec.decodeStoredEvidence(decision.evidenceJson()),
                decision.getDecidedAt());
    }

    static GateEvaluation evaluate(EvaluationGatePolicy policy, EvaluationExperimentService.Comparison comparison) {
        if (policy == null || comparison == null || comparison.baselineMetrics() == null
                || comparison.candidateMetrics() == null || comparison.cases() == null) {
            throw new IllegalArgumentException("门禁评估输入无效");
        }
        EvaluationExperimentService.GateRunMetrics baseline = requireMetrics(comparison.baselineMetrics());
        EvaluationExperimentService.GateRunMetrics candidate = requireMetrics(comparison.candidateMetrics());
        List<EvaluationExperimentService.CaseComparison> cases = comparison.cases();
        List<EvaluationExperimentService.CaseComparison> criticalFailures = matching(cases,
                value -> "CRITICAL".equals(value.severity()) && value.candidateStatus() == EvaluationResultStatus.FAILED);
        List<EvaluationExperimentService.CaseComparison> executionFailures = matching(cases,
                value -> value.candidateStatus() == EvaluationResultStatus.ERROR
                        || value.candidateStatus() == EvaluationResultStatus.CANCELLED
                        || value.candidateStatus() == EvaluationResultStatus.RUNNING);
        List<EvaluationExperimentService.CaseComparison> pendingReviews = matching(cases,
                value -> value.candidateStatus() == EvaluationResultStatus.PENDING && value.candidateReviewRequired());
        List<EvaluationExperimentService.CaseComparison> failedCases = matching(cases,
                value -> value.candidateStatus() == EvaluationResultStatus.FAILED);

        CostGrowth costGrowth = costGrowth(baseline.totalCost(), candidate.totalCost(),
                policy.getMaxCostGrowthPercent());
        List<GateDecisionPayloadCodec.RuleEvidence> rules = new ArrayList<>();
        // 安全硬底线：该兼容字段只冻结进策略快照，不能关闭已确定的高危失败拦截。
        rules.add(rule("CRITICAL_CASES_PASSED", BigDecimal.ZERO, count(criticalFailures), criticalFailures));
        rules.add(rule("EXECUTION_ERROR", BigDecimal.ZERO, count(executionFailures), executionFailures));
        rules.add(rule("MANUAL_REVIEW", BigDecimal.ZERO, count(pendingReviews), pendingReviews));
        rules.add(rule("MIN_PASS_RATE", policy.getMinPassRate(),
                candidate.qualityAvailable() ? candidate.qualityRate() : null, failedCases));
        rules.add(rule("MAX_COST_GROWTH_PERCENT", policy.getMaxCostGrowthPercent(), costGrowth.actual(), List.of()));
        rules.add(rule("MAX_AVG_LATENCY_MS", BigDecimal.valueOf(policy.getMaxAverageLatencyMs()),
                BigDecimal.valueOf(candidate.averageLatencyMs()), List.of()));
        rules.add(rule("MAX_P95_LATENCY_MS", BigDecimal.valueOf(policy.getMaxP95LatencyMs()),
                BigDecimal.valueOf(candidate.p95LatencyMs()), List.of()));

        GateConclusion conclusion;
        if (!criticalFailures.isEmpty()) {
            conclusion = GateConclusion.FAIL;
        } else if (!executionFailures.isEmpty() || !pendingReviews.isEmpty() || !candidate.qualityAvailable()) {
            conclusion = GateConclusion.REVIEW_REQUIRED;
        } else if (candidate.qualityRate().compareTo(policy.getMinPassRate()) < 0 || costGrowth.exceeded()
                || candidate.averageLatencyMs() > policy.getMaxAverageLatencyMs()
                || candidate.p95LatencyMs() > policy.getMaxP95LatencyMs()) {
            conclusion = GateConclusion.FAIL;
        } else {
            conclusion = GateConclusion.PASS;
        }
        return new GateEvaluation(conclusion, new GateDecisionPayloadCodec.EvidenceSnapshot(1, List.copyOf(rules)));
    }

    private static CandidateScope candidateScope(EvaluationExperimentService.Comparison comparison) {
        if (comparison == null || comparison.candidateMetrics() == null || comparison.cases() == null
                || comparison.cases().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("门禁作用域投影无效");
        }
        return new CandidateScope(comparison.candidateMetrics().platformId(), comparison.cases().stream()
                .map(EvaluationExperimentService.CaseComparison::candidateSnapshotCategory).toList());
    }

    static void requireScope(EvaluationGatePolicy policy, CandidateScope candidate) {
        if (policy == null || candidate == null) throw new IllegalArgumentException("门禁作用域无效");
        if (policy.getPlatformId() != null && !policy.getPlatformId().equals(candidate.platformId())) {
            throw new ConflictException("候选运行不在门禁策略的平台作用域内");
        }
        if (policy.getCategory() != null && (candidate.categories().isEmpty()
                || candidate.categories().stream().anyMatch(value -> !policy.getCategory().equals(value)))) {
            throw new ConflictException("候选运行不在门禁策略的分类作用域内");
        }
    }

    private static EvaluationExperimentService.GateRunMetrics requireMetrics(
            EvaluationExperimentService.GateRunMetrics metrics) {
        if (metrics.qualityRate() == null || metrics.totalCost() == null || metrics.qualityRate().signum() < 0
                || metrics.qualityRate().compareTo(new BigDecimal("100")) > 0 || metrics.totalCost().signum() < 0
                || metrics.averageLatencyMs() < 0 || metrics.p95LatencyMs() < 0) {
            throw new IllegalArgumentException("门禁聚合指标无效");
        }
        return metrics;
    }

    private static CostGrowth costGrowth(BigDecimal baseline, BigDecimal candidate, BigDecimal threshold) {
        if (baseline.signum() == 0) {
            return candidate.signum() == 0
                    ? new CostGrowth(BigDecimal.ZERO.setScale(8), false)
                    : new CostGrowth(null, true);
        }
        BigDecimal numerator = candidate.subtract(baseline).multiply(BigDecimal.valueOf(100));
        boolean exceeded = numerator.compareTo(baseline.multiply(threshold)) > 0;
        BigDecimal actual = numerator.divide(baseline, 8, RoundingMode.HALF_UP);
        return new CostGrowth(actual, exceeded);
    }

    private static List<EvaluationExperimentService.CaseComparison> matching(
            List<EvaluationExperimentService.CaseComparison> cases,
            java.util.function.Predicate<EvaluationExperimentService.CaseComparison> predicate) {
        return cases.stream().filter(value -> value != null && value.candidateStatus() != null).filter(predicate).toList();
    }

    private static GateDecisionPayloadCodec.RuleEvidence rule(String name, BigDecimal threshold, BigDecimal actual,
            List<EvaluationExperimentService.CaseComparison> failedCases) {
        List<GateDecisionPayloadCodec.FailedCaseEvidence> evidenceCases = failedCases.stream()
                .limit(EVIDENCE_CASE_LIMIT)
                .map(value -> new GateDecisionPayloadCodec.FailedCaseEvidence(value.caseId(),
                        GateDecisionPayloadCodec.safeCaseName(value.name())))
                .toList();
        return new GateDecisionPayloadCodec.RuleEvidence(name, threshold, actual, evidenceCases);
    }

    private static BigDecimal count(List<?> values) {
        return BigDecimal.valueOf(values.size());
    }

    record GateEvaluation(GateConclusion conclusion, GateDecisionPayloadCodec.EvidenceSnapshot evidence) {}
    record CandidateScope(String platformId, List<String> categories) {}
    private record CostGrowth(BigDecimal actual, boolean exceeded) {}

    public record CreatePolicyCommand(String name, String platformId, String category, BigDecimal minPassRate,
            BigDecimal maxCostGrowthPercent, long maxAverageLatencyMs, long maxP95LatencyMs,
            boolean requireCriticalCasesPassed, boolean enabled) {
        EvaluationGatePolicy.Draft draft() {
            return new EvaluationGatePolicy.Draft(name, platformId, category, minPassRate, maxCostGrowthPercent,
                    maxAverageLatencyMs, maxP95LatencyMs, requireCriticalCasesPassed, enabled);
        }
    }
    public record UpdatePolicyCommand(long expectedVersion, String name, String platformId, String category,
            BigDecimal minPassRate, BigDecimal maxCostGrowthPercent, long maxAverageLatencyMs, long maxP95LatencyMs,
            boolean requireCriticalCasesPassed, boolean enabled) {
        EvaluationGatePolicy.Draft draft() {
            return new EvaluationGatePolicy.Draft(name, platformId, category, minPassRate, maxCostGrowthPercent,
                    maxAverageLatencyMs, maxP95LatencyMs, requireCriticalCasesPassed, enabled);
        }
    }
    public record DecisionCommand(String baselineRunId, String policyId) {}
    public record PolicyView(String id, String name, String platformId, String category, BigDecimal minPassRate,
            BigDecimal maxCostGrowthPercent, long maxAverageLatencyMs, long maxP95LatencyMs,
            boolean requireCriticalCasesPassed, boolean enabled, long version, Instant createdAt, Instant updatedAt) {}
    public record DecisionView(String id, String runId, String policyId, long policyVersion, GateConclusion conclusion,
            JsonNode evidence, Instant decidedAt) {}
}

@Service
class EvaluationGateDecisionWriter {

    private final EvaluationRunDecisionStateLockReader candidateStates;
    private final EvaluationGatePolicyRepository policies;
    private final EvaluationGateDecisionRepository decisions;
    private final EvaluationAuditService audit;

    EvaluationGateDecisionWriter(EvaluationRunDecisionStateLockReader candidateStates,
            EvaluationGatePolicyRepository policies, EvaluationGateDecisionRepository decisions,
            EvaluationAuditService audit) {
        this.candidateStates = candidateStates;
        this.policies = policies;
        this.decisions = decisions;
        this.audit = audit;
    }

    @Transactional
    EvaluationGateService.DecisionView append(String runId, String policyId, long expectedPolicyVersion,
            String expectedCandidateFingerprint,
            EvaluationGateService.CandidateScope candidateScope, EvaluationGateService.GateEvaluation evaluation) {
        String currentCandidateFingerprint = candidateStates.lockAndReadFingerprint(runId);
        if (!Objects.equals(expectedCandidateFingerprint, currentCandidateFingerprint)) {
            throw new ConflictException("候选运行状态已变化，请重新生成决策");
        }
        EvaluationGatePolicy policy = policies.findByIdForUpdate(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("评测门禁策略不存在"));
        if (policy.getVersion() != expectedPolicyVersion) {
            throw new ConflictException("门禁策略已变化，请重新生成决策");
        }
        if (!policy.isEnabled()) throw new ConflictException("停用的门禁策略不能生成决策");
        EvaluationGateService.requireScope(policy, candidateScope);
        EvaluationGateDecision decision = decisions.append(EvaluationGateDecision.append(Identities.newId(), runId,
                policy, evaluation.conclusion(), evaluation.evidence(), Instant.now()));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("policyId", policy.getId());
        summary.put("policyVersion", policy.getVersion());
        summary.put("conclusion", evaluation.conclusion());
        audit.recordResource("GATE_DECISION_CREATED", "EVALUATION_GATE_DECISION", decision.getId(),
                "MIDDLE_PLATFORM", summary);
        return EvaluationGateService.decisionView(decision);
    }
}
