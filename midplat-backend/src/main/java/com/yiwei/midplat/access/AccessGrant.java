package com.yiwei.midplat.access;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A credential's actual permissions: always a subset of the owning Client's entitlements. */
@Entity
@Table(name = "midplat_credential_grant")
public class AccessGrant extends BaseEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";

    @Column(name = "credential_id", nullable = false, length = 64)
    private String credentialId;

    @Column(name = "entitlement_id", nullable = false, length = 64)
    private String entitlementId;

    /** Optional narrowing of the entitlement constraints; never widening. */
    @Column(name = "constraints", columnDefinition = "text")
    private String constraints;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    protected AccessGrant() {}

    public AccessGrant(String id, String credentialId, String entitlementId, String constraints) {
        super(id);
        this.credentialId = DomainAssertions.requireText(credentialId, "credentialId cannot be blank");
        this.entitlementId = DomainAssertions.requireText(entitlementId, "entitlementId cannot be blank");
        this.constraints = constraints;
        this.status = STATUS_ACTIVE;
    }

    public String getCredentialId() { return credentialId; }
    public String getEntitlementId() { return entitlementId; }
    public String getConstraints() { return constraints; }
    public String getStatus() { return status; }

    public void revoke() {
        this.status = STATUS_REVOKED;
    }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
}
