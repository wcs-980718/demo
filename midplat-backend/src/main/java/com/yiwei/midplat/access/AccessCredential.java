package com.yiwei.midplat.access;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** One authentication means of a Client; the raw secret exists only in the issue response, once. */
@Entity
@Table(name = "midplat_credential")
public class AccessCredential extends BaseEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";
    public static final String STATUS_EXPIRED = "expired";

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "key_id", nullable = false, length = 64, unique = true)
    private String keyId;

    @Column(name = "secret_digest", nullable = false, length = 128)
    private String secretDigest;

    @Column(name = "pepper_version", nullable = false)
    private int pepperVersion;

    @Column(name = "label", nullable = false, length = 128)
    private String label;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "predecessor_id", length = 64)
    private String predecessorId;

    /** false until the one-time secret has actually been returned to an operator. */
    @Column(name = "secret_revealed", nullable = false)
    private boolean secretRevealed;

    @Column(name = "policy_revision", nullable = false)
    private long policyRevision;

    protected AccessCredential() {}

    AccessCredential(String id, String clientId, String keyId, String secretDigest, int pepperVersion,
                     String label, Instant validFrom, Instant expiresAt, String predecessorId) {
        super(id);
        this.clientId = DomainAssertions.requireText(clientId, "clientId cannot be blank");
        this.keyId = DomainAssertions.requireText(keyId, "keyId cannot be blank");
        this.secretDigest = DomainAssertions.requireText(secretDigest, "secretDigest cannot be blank");
        this.pepperVersion = pepperVersion;
        this.label = DomainAssertions.requireText(label, "label cannot be blank");
        this.status = STATUS_ACTIVE;
        this.validFrom = validFrom;
        this.expiresAt = expiresAt;
        this.predecessorId = predecessorId;
        this.policyRevision = 1;
    }

    public String getClientId() { return clientId; }
    public String getKeyId() { return keyId; }
    public String getSecretDigest() { return secretDigest; }
    public int getPepperVersion() { return pepperVersion; }
    public String getLabel() { return label; }
    public String getStatus() { return status; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public String getPredecessorId() { return predecessorId; }
    public boolean isSecretRevealed() { return secretRevealed; }
    public long getPolicyRevision() { return policyRevision; }

    void markSecretRevealed() {
        this.secretRevealed = true;
    }

    long bumpPolicyRevision() {
        return ++policyRevision;
    }

    void markUsed(Instant at) {
        this.lastUsedAt = at;
    }

    /** Irreversible by design; repeated revoke is idempotent. */
    void revoke(Instant at) {
        if (STATUS_REVOKED.equals(status)) {
            return;
        }
        this.status = STATUS_REVOKED;
        this.revokedAt = at;
    }

    public boolean usableAt(Instant now) {
        if (!STATUS_ACTIVE.equals(status)) {
            return false;
        }
        if (validFrom != null && now.isBefore(validFrom)) {
            return false;
        }
        return expiresAt == null || now.isBefore(expiresAt);
    }
}
