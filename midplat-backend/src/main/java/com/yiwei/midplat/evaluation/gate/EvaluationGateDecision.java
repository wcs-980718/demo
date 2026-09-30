package com.yiwei.midplat.evaluation.gate;

import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "midplat_eval_gate_decision")
@Immutable
public class EvaluationGateDecision {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "policy_id", nullable = false, length = 64)
    private String policyId;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "conclusion", nullable = false, length = 16)
    private GateConclusion conclusion;

    @Column(name = "policy_snapshot_json", nullable = false, columnDefinition = "text")
    private String policySnapshotJson;

    @Column(name = "evidence_json", nullable = false, columnDefinition = "text")
    private String evidenceJson;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected EvaluationGateDecision() {}

    private EvaluationGateDecision(String id, String runId, String policyId, long policyVersion, GateConclusion conclusion,
            String policySnapshotJson, String evidenceJson, Instant decidedAt) {
        this.id = text(id, "id");
        this.runId = text(runId, "runId");
        this.policyId = text(policyId, "policyId");
        if (policyVersion < 0) throw new IllegalArgumentException("policyVersion 不能为负数");
        this.policyVersion = policyVersion;
        this.conclusion = Objects.requireNonNull(conclusion, "conclusion 不能为空");
        this.policySnapshotJson = DomainAssertions.requireText(policySnapshotJson, "policySnapshotJson 不能为空");
        this.evidenceJson = DomainAssertions.requireText(evidenceJson, "evidenceJson 不能为空");
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt 不能为空");
    }

    static EvaluationGateDecision append(String id, String runId, EvaluationGatePolicy policy,
            GateConclusion conclusion, GateDecisionPayloadCodec.EvidenceSnapshot evidence, Instant decidedAt) {
        if (policy == null) throw new IllegalArgumentException("策略不能为空");
        return new EvaluationGateDecision(id, runId, policy.getId(), policy.getVersion(), conclusion,
                GateDecisionPayloadCodec.policySnapshot(policy), GateDecisionPayloadCodec.evidence(evidence), decidedAt);
    }

    private static String text(String value, String field) {
        String normalized = DomainAssertions.requireText(value, field + " 不能为空");
        if (normalized.length() > 64) throw new IllegalArgumentException(field + " 长度超限");
        return normalized;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getPolicyId() { return policyId; }
    public long getPolicyVersion() { return policyVersion; }
    public GateConclusion getConclusion() { return conclusion; }
    public Instant getDecidedAt() { return decidedAt; }

    String policySnapshotJson() { return policySnapshotJson; }
    String evidenceJson() { return evidenceJson; }
}
