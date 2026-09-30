package com.yiwei.midplat.prompt;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "midplat_prompt")
public class Prompt extends BaseEntity {

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "slot", nullable = false, length = 32)
    private String slot;

    @Column(name = "version_name", nullable = false, length = 32)
    private String versionName;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    protected Prompt() {}

    public Prompt(String id, String name, String slot, String versionName, String body) {
        super(id);
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.slot = DomainAssertions.requireText(slot, "slot cannot be blank");
        this.versionName = versionName == null || versionName.isBlank() ? "v1" : versionName.trim();
        this.body = DomainAssertions.requireText(body, "body cannot be blank");
    }

    public void update(String name, String versionName, String body) {
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.versionName = versionName == null || versionName.isBlank() ? this.versionName : versionName.trim();
        this.body = DomainAssertions.requireText(body, "body cannot be blank");
    }

    public String getName() { return name; }
    public String getSlot() { return slot; }
    public String getVersionName() { return versionName; }
    public String getBody() { return body; }
}
