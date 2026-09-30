package com.yiwei.midplat.access;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** External consuming organization (A/B companies); an identity档案, not a login tenant or a key. */
@Entity
@Table(name = "midplat_consumer")
public class AccessConsumer extends BaseEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_ARCHIVED = "archived";

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "owner_workspace_id", length = 64)
    private String ownerWorkspaceId;

    protected AccessConsumer() {}

    public AccessConsumer(String id, String code, String name, String type) {
        super(id);
        this.code = DomainAssertions.requireText(code, "code cannot be blank");
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.type = DomainAssertions.requireText(type, "type cannot be blank");
        this.status = STATUS_ACTIVE;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getStatus() { return status; }

    public void rename(String name) {
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
    }

    public void changeStatus(String status) {
        if (!java.util.Set.of(STATUS_ACTIVE, STATUS_SUSPENDED, STATUS_ARCHIVED).contains(status)) {
            throw new IllegalArgumentException("invalid consumer status: " + status);
        }
        this.status = status;
    }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
}
