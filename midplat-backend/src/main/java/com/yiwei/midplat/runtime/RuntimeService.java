package com.yiwei.midplat.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.yiwei.midplat.access.AccessAuthentication.AuthenticatedClient;
import com.yiwei.midplat.common.api.ForbiddenException;
import com.yiwei.midplat.common.api.UnauthorizedException;
import com.yiwei.midplat.model.ModelService;
import com.yiwei.midplat.platform.LegacyCredentialAdapter.LegacyIdentity;
import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.ManagedPlatformRepository;
import com.yiwei.midplat.platform.PlatformCredentialService;
import com.yiwei.midplat.prompt.PromptService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeService {
    @org.springframework.beans.factory.annotation.Autowired private com.yiwei.midplat.fusion.FusionRuntimeBridge fusion;

    private final ManagedPlatformRepository platforms;
    private final ModelService modelService;
    private final PromptService promptService;
    private final PlatformCredentialService credentials;

    RuntimeService(
            ManagedPlatformRepository platforms,
            ModelService modelService,
            PromptService promptService,
            PlatformCredentialService credentials) {
        this.platforms = platforms;
        this.modelService = modelService;
        this.promptService = promptService;
        this.credentials = credentials;
    }

    /** Resolved machine identity: new AuthenticatedClient or legacy digest identity. */
    public record Caller(String projectId, String clientId, AuthenticatedClient client, LegacyIdentity legacy) {
        public String displayPrincipal() {
            return client != null ? "client:" + client.clientId() : legacy.clientId();
        }
    }

    private Caller requireCaller(String authorization) {
        Object who = credentials.require(authorization);
        if (who instanceof AuthenticatedClient client) {
            return new Caller(client.projectId(), client.clientId(), client, null);
        }
        LegacyIdentity legacy = (LegacyIdentity) who;
        return new Caller(legacy.projectId(), legacy.clientId(), null, legacy);
    }

    /** Legacy callers keep the direct model endpoints; new clients need model.invoke actions (W2). */
    private void requireModelAction(Caller caller, String action) {
        if (caller.client() != null) {
            throw new ForbiddenException("该凭证未获准直接调用模型接口");
        }
    }

    @Transactional(readOnly = true)
    public ProfileView profile(String authorization) {
        Caller caller = requireCaller(authorization);
        ManagedPlatform platform = platforms.findById(caller.projectId())
                .orElseThrow(() -> new UnauthorizedException("无效的中台凭证"));
        // W1: profile no longer echoes the token; clients already hold their own key material.
        return new ProfileView(
                platform.getId(),
                platform.getName(),
                null,
                caller.clientId(),
                platform.getLlmModelId() != null,
                platform.getEmbeddingModelId() != null,
                platform.getRerankModelId() != null);
    }

    public ChatView chat(String authorization, String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input 不能为空");
        }
        Caller caller = requireCaller(authorization);
        requireModelAction(caller, "chat");
        ManagedPlatform platform = requirePlatform(caller);
        if (fusion != null && fusion.manages(platform.getId())) {
            var result = fusion.chat(platform, input, caller.clientId());
            return new ChatView(result.content(), result.model(), platform.getId());
        }
        String modelId = requireBound(platform.getLlmModelId(), "该平台未绑定 LLM");
        String system = null;
        if (platform.getPromptId() != null) {
            system = promptService.requireBody(platform.getPromptId());
        }
        var result = modelService.chat(modelId, system, input);
        return new ChatView(result.content(), result.model(), platform.getId());
    }

    @Transactional(readOnly = true)
    public EmbedView embed(String authorization, String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input 不能为空");
        }
        Caller caller = requireCaller(authorization);
        requireModelAction(caller, "embed");
        ManagedPlatform platform = requirePlatform(caller);
        String modelId = requireBound(platform.getEmbeddingModelId(), "该平台未绑定 Embedding");
        var result = modelService.embed(modelId, input);
        return new EmbedView(result.embedding(), result.model(), platform.getId());
    }

    @Transactional(readOnly = true)
    public RerankView rerank(String authorization, String query, List<String> documents) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("documents 不能为空");
        }
        Caller caller = requireCaller(authorization);
        requireModelAction(caller, "rerank");
        ManagedPlatform platform = requirePlatform(caller);
        String modelId = requireBound(platform.getRerankModelId(), "该平台未绑定 Rerank");
        var result = modelService.rerank(modelId, query, documents);
        return new RerankView(result.results(), result.model(), platform.getId());
    }

    private String requireBound(String modelId, String message) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return modelId;
    }

    private ManagedPlatform requirePlatform(Caller caller) {
        return platforms.findById(caller.projectId())
                .orElseThrow(() -> new UnauthorizedException("无效的中台凭证"));
    }

    public record ProfileView(String platformId, String name, String token, String clientId, boolean chat, boolean embed, boolean rerank) {}
    public record ChatView(String content, String model, String platformId) {}
    public record EmbedView(JsonNode embedding, String model, String platformId) {}
    public record RerankView(JsonNode results, String model, String platformId) {}
}
