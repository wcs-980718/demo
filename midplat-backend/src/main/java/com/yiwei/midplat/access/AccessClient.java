package com.yiwei.midplat.access;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Stable machine identity of one consumer for one project + environment; survives key rotation. */
@Entity
@Table(name = "midplat_client")
public class AccessClient extends BaseEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_ARCHIVED = "archived";

    @Column(name = "consumer_id", nullable = false, length = 64)
    private String consumerId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "environment", nullable = false, length = 32)
    private String environment;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "policy_revision", nullable = false)
    private long policyRevision;

    protected AccessClient() {}

    public AccessClient(String id, String consumerId, String projectId, String environment, String name, String code) {
        super(id);
        this.consumerId = DomainAssertions.requireText(consumerId, "consumerId cannot be blank");
        this.projectId = DomainAssertions.requireText(projectId, "projectId cannot be blank");
        this.environment = DomainAssertions.requireText(environment, "environment cannot be blank");
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.code = DomainAssertions.requireText(code, "code cannot be blank");
        this.status = STATUS_ACTIVE;
        this.policyRevision = 1;
    }

    public String getConsumerId() { return consumerId; }
    public String getProjectId() { return projectId; }
    public String getEnvironment() { return environment; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getStatus() { return status; }
    public long getPolicyRevision() { return policyRevision; }

    public void rename(String name) {
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
    }

    /** project/consumer/environment are immutable after creation; rotation means a new Client. */
    public void changeStatus(String status) {
        if (!java.util.Set.of(STATUS_ACTIVE, STATUS_SUSPENDED, STATUS_ARCHIVED).contains(status)) {
            throw new IllegalArgumentException("invalid client status: " + status);
        }
        this.status = status;
    }

    public long bumpPolicyRevision() {
        return ++policyRevision;
    }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
}
