package com.yiwei.midplat.evaluation.casecenter;

import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "midplat_eval_audit_log")
@Immutable
public class EvaluationAuditLog {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 64)
    private String resourceId;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "actor_ref", nullable = false, length = 128)
    private String actorRef;

    @Column(name = "summary_json", nullable = false, columnDefinition = "text")
    private String summaryJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected EvaluationAuditLog() {}

    public EvaluationAuditLog(
            String id,
            String action,
            String resourceType,
            String resourceId,
            String source,
            String actorRef,
            String summaryJson,
            Instant occurredAt) {
        this.id = DomainAssertions.requireText(id, "id 不能为空");
        this.action = DomainAssertions.requireText(action, "action 不能为空");
        this.resourceType = DomainAssertions.requireText(resourceType, "resourceType 不能为空");
        this.resourceId = DomainAssertions.requireText(resourceId, "resourceId 不能为空");
        this.source = DomainAssertions.requireText(source, "source 不能为空");
        this.actorRef = DomainAssertions.requireText(actorRef, "actorRef 不能为空");
        this.summaryJson = DomainAssertions.requireText(summaryJson, "summaryJson 不能为空");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
    }

    public String getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getSource() {
        return source;
    }

    public String getActorRef() {
        return actorRef;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
