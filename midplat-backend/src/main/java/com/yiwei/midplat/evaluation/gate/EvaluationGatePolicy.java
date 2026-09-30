package com.yiwei.midplat.evaluation.gate;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import com.yiwei.midplat.common.api.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@Table(name = "midplat_eval_gate_policy")
public class EvaluationGatePolicy extends BaseEntity {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "platform_id", length = 64)
    private String platformId;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "min_pass_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal minPassRate;

    @Column(name = "max_cost_growth_percent", nullable = false, precision = 20, scale = 8)
    private BigDecimal maxCostGrowthPercent;

    @Column(name = "max_avg_latency_ms", nullable = false)
    private long maxAverageLatencyMs;

    @Column(name = "max_p95_latency_ms", nullable = false)
    private long maxP95LatencyMs;

    @Column(name = "require_critical_cases_passed", nullable = false)
    private boolean requireCriticalCasesPassed;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected EvaluationGatePolicy() {}

    private EvaluationGatePolicy(String id, Draft draft) {
        super(idText(id));
        apply(validate(draft));
    }

    public static EvaluationGatePolicy create(String id, Draft draft) {
        return new EvaluationGatePolicy(id, draft);
    }

    public void revise(Draft draft, long expectedVersion) {
        if (getVersion() != expectedVersion) throw new ConflictException("门禁策略版本已变化，请刷新后重试");
        apply(validate(draft));
    }

    private static ValidatedDraft validate(Draft draft) {
        if (draft == null) throw new IllegalArgumentException("门禁策略不能为空");
        String name = GateSafeMetadata.required(draft.name(), "name", 128);
        String platformId = GateSafeMetadata.optional(draft.platformId(), "platformId", 64);
        String category = GateSafeMetadata.optional(draft.category(), "category", 64);
        BigDecimal minPassRate = decimalInRange(draft.minPassRate(), "minPassRate", 2, BigDecimal.ZERO, HUNDRED);
        BigDecimal maxCostGrowthPercent = decimalInRange(draft.maxCostGrowthPercent(), "maxCostGrowthPercent", 8,
                BigDecimal.ZERO, new BigDecimal("999999999999.99999999"));
        if (draft.maxAverageLatencyMs() < 0 || draft.maxP95LatencyMs() < 0) {
            throw new IllegalArgumentException("延迟阈值不能为负数");
        }
        return new ValidatedDraft(name, platformId, category, minPassRate, maxCostGrowthPercent,
                draft.maxAverageLatencyMs(), draft.maxP95LatencyMs(), draft.requireCriticalCasesPassed(), draft.enabled());
    }

    private void apply(ValidatedDraft draft) {
        name = draft.name();
        platformId = draft.platformId();
        category = draft.category();
        minPassRate = draft.minPassRate();
        maxCostGrowthPercent = draft.maxCostGrowthPercent();
        maxAverageLatencyMs = draft.maxAverageLatencyMs();
        maxP95LatencyMs = draft.maxP95LatencyMs();
        requireCriticalCasesPassed = draft.requireCriticalCasesPassed();
        enabled = draft.enabled();
    }

    private static String idText(String value) {
        String normalized = DomainAssertions.requireText(value, "id 不能为空");
        if (normalized.length() > 64) throw new IllegalArgumentException("id 长度超限");
        return normalized;
    }

    private static BigDecimal decimalInRange(BigDecimal value, String field, int scale, BigDecimal min, BigDecimal max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0 || value.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException(field + " 超出允许范围或精度");
        }
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    public String getName() { return name; }
    public String getPlatformId() { return platformId; }
    public String getCategory() { return category; }
    public BigDecimal getMinPassRate() { return minPassRate; }
    public BigDecimal getMaxCostGrowthPercent() { return maxCostGrowthPercent; }
    public long getMaxAverageLatencyMs() { return maxAverageLatencyMs; }
    public long getMaxP95LatencyMs() { return maxP95LatencyMs; }
    public boolean isRequireCriticalCasesPassed() { return requireCriticalCasesPassed; }
    public boolean isEnabled() { return enabled; }

    public record Draft(
            String name,
            String platformId,
            String category,
            BigDecimal minPassRate,
            BigDecimal maxCostGrowthPercent,
            long maxAverageLatencyMs,
            long maxP95LatencyMs,
            boolean requireCriticalCasesPassed,
            boolean enabled) {
        public Draft withName(String replacementName) {
            return new Draft(replacementName, platformId, category, minPassRate, maxCostGrowthPercent,
                    maxAverageLatencyMs, maxP95LatencyMs, requireCriticalCasesPassed, enabled);
        }

        public Draft withScope(String replacementPlatformId, String replacementCategory) {
            return new Draft(name, replacementPlatformId, replacementCategory, minPassRate, maxCostGrowthPercent,
                    maxAverageLatencyMs, maxP95LatencyMs, requireCriticalCasesPassed, enabled);
        }
    }

    private record ValidatedDraft(String name, String platformId, String category, BigDecimal minPassRate,
            BigDecimal maxCostGrowthPercent, long maxAverageLatencyMs, long maxP95LatencyMs,
            boolean requireCriticalCasesPassed, boolean enabled) {}
}
