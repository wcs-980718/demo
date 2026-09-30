package com.yiwei.midplat.platform;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "midplat_platform")
public class ManagedPlatform extends BaseEntity {

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "entry_url", length = 512)
    private String entryUrl;

    @Column(name = "icon", nullable = false, length = 64)
    private String icon;

    @Column(name = "consume", nullable = false)
    private boolean consume;

    @Column(name = "token", length = 128)
    private String token;

    /** Protected HMAC digest of the legacy token; authentication must use this, not the plaintext column. */
    @Column(name = "legacy_token_digest", length = 128)
    private String legacyTokenDigest;

    @Column(name = "llm_model_id", length = 64)
    private String llmModelId;

    @Column(name = "embedding_model_id", length = 64)
    private String embeddingModelId;

    @Column(name = "rerank_model_id", length = 64)
    private String rerankModelId;

    @Column(name = "prompt_id", length = 64)
    private String promptId;

    /** external：项目自带运行时（SOUL / 任务技能），不在工作台编辑模型。 */
    @Column(name = "agent_mode", length = 32)
    private String agentMode;

    @Column(name = "runtime_json", columnDefinition = "text")
    private String runtimeJson;

    @Column(name = "apis_json", columnDefinition = "text")
    private String apisJson;

    protected ManagedPlatform() {}

    public ManagedPlatform(String id, String name, String entryUrl, String icon, boolean consume, String token) {
        super(id);
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.entryUrl = entryUrl;
        this.icon = icon == null || icon.isBlank() ? "Boxes" : icon.trim();
        this.consume = consume;
        this.token = token;
    }

    public void update(String name, String entryUrl, String icon, String llmModelId, String embeddingModelId, String rerankModelId, String promptId) {
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.entryUrl = entryUrl;
        if (icon != null && !icon.isBlank()) {
            this.icon = icon.trim();
        }
        this.llmModelId = blankToNull(llmModelId);
        this.embeddingModelId = blankToNull(embeddingModelId);
        this.rerankModelId = blankToNull(rerankModelId);
        this.promptId = blankToNull(promptId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public String getName() { return name; }
    public String getEntryUrl() { return entryUrl; }

    public void updateEntryUrl(String entryUrl) {
        this.entryUrl = blankToNull(entryUrl);
    }

    public String getIcon() { return icon; }
    public boolean isConsume() { return consume; }
    public String getToken() { return token; }
    public String getLegacyTokenDigest() { return legacyTokenDigest; }

    /** W1 migration backfill: record the digest of the existing plaintext token. */
    public void recordLegacyTokenDigest(String digest) {
        if (digest != null && !digest.isBlank()) {
            this.legacyTokenDigest = digest;
        }
    }

    public void assignToken(String token) {
        if (this.token != null && !this.token.isBlank()) {
            return;
        }
        this.token = DomainAssertions.requireText(token, "token cannot be blank");
    }
    public String getLlmModelId() { return llmModelId; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public String getRerankModelId() { return rerankModelId; }
    public String getPromptId() { return promptId; }
    public String getAgentMode() { return agentMode; }
    public String getRuntimeJson() { return runtimeJson; }

    public void updateRuntimeJson(String runtimeJson) {
        this.runtimeJson = runtimeJson;
    }

    public String getApisJson() { return apisJson; }

    public void updateApisJson(String apisJson) {
        this.apisJson = apisJson;
    }
}
