package com.yiwei.midplat.access;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Ceiling of what a Client may ever be granted; credential grants must stay inside this set. */
@Entity
@Table(name = "midplat_client_entitlement")
public class AccessEntitlement extends BaseEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "resource_kind", nullable = false, length = 32)
    private String resourceKind;

    @Column(name = "resource_id", nullable = false, length = 128)
    private String resourceId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /** Structured constraint whitelist, JSON with schemaVersion; never executable code or SQL. */
    @Column(name = "constraints", columnDefinition = "text")
    private String constraints;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    protected AccessEntitlement() {}

    public AccessEntitlement(String id, String clientId, String resourceKind, String resourceId, String action, String constraints) {
        super(id);
        this.clientId = DomainAssertions.requireText(clientId, "clientId cannot be blank");
        this.resourceKind = DomainAssertions.requireText(resourceKind, "resourceKind cannot be blank");
        this.resourceId = DomainAssertions.requireText(resourceId, "resourceId cannot be blank");
        this.action = DomainAssertions.requireText(action, "action cannot be blank");
        this.constraints = constraints;
        this.status = STATUS_ACTIVE;
    }

    public String getClientId() { return clientId; }
    public String getResourceKind() { return resourceKind; }
    public String getResourceId() { return resourceId; }
    public String getAction() { return action; }
    public String getConstraints() { return constraints; }
    public String getStatus() { return status; }

    public void revoke() {
        this.status = STATUS_REVOKED;
    }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
}
