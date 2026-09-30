package com.yiwei.midplat.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.model.ModelService;
import com.yiwei.midplat.prompt.PromptService;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformRuntimeService {

    @org.springframework.beans.factory.annotation.Autowired private PlatformConfigDelivery delivery;

    private final com.yiwei.midplat.fusion.FusionOwnership fusionOwnership;
    private final ManagedPlatformRepository repository;
    private final ObjectMapper objectMapper;
    private final ModelService modelService;
    private final PromptService promptService;
    private final String indicatorSettingsUrl;
    PlatformRuntimeService(
            ManagedPlatformRepository repository,
            com.yiwei.midplat.fusion.FusionOwnership fusionOwnership,
            ObjectMapper objectMapper,
            ModelService modelService,
            PromptService promptService,
            @Value("${midplat.platform-endpoints.indicator-settings-url:}") String indicatorSettingsUrl) {
        this.fusionOwnership = fusionOwnership;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.modelService = modelService;
        this.promptService = promptService;
        this.indicatorSettingsUrl = indicatorSettingsUrl;
    }

    @Transactional(readOnly = true)
    public RuntimeSettings get(String platformId) {
        ManagedPlatform platform = require(platformId);
        return managedKnowledge(platformId) ? liveKnowledgeSettings(platform) : read(platform);
    }

    @Transactional
    public RuntimeSettings update(String platformId, RuntimeSettingsPatch incoming) {
        ManagedPlatform platform = require(platformId);
        if (managedKnowledge(platformId)) {
            RuntimeSettings merged = liveKnowledgeSettings(platform).apply(incoming);
            Map<String, Object> payload = KnowledgeRuntimeSettings.patch(incoming, objectMapper);
            if (!payload.isEmpty()) {
                save(platform, merged);
                delivery.enqueue(platformId, settingsUrl(platform), payload, Map.of());
            }
            return merged;
        }
        fusionOwnership.requireLegacyWritable(platformId);
        RuntimeSettings merged = read(platform).apply(incoming);
        save(platform, merged);
        push(platform);
        return merged;
    }

    private boolean managedKnowledge(String platformId) {
        return "plat-kb".equals(platformId) && fusionOwnership.manages(platformId);
    }

    private RuntimeSettings liveKnowledgeSettings(ManagedPlatform platform) {
        return KnowledgeRuntimeSettings.read(delivery.readSettings(settingsUrl(platform)), objectMapper);
    }

    private void save(ManagedPlatform platform, RuntimeSettings settings) {
        try {
            platform.updateRuntimeJson(objectMapper.writeValueAsString(settings));
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法保存检索配置");
        }
    }

    public void push(ManagedPlatform platform) {
        if (platform == null || platform.isConsume() || platform.getEntryUrl() == null || platform.getEntryUrl().isBlank()) {
            return;
        }
        if ("plat-kb".equals(platform.getId())) {
            pushJson(platform, knowledgePayload(platform, read(platform)), "knowledge-base");
            return;
        }
        if ("plat-an".equals(platform.getId())) {
            pushJson(platform, annotationPayload(platform), "annotation");
            return;
        }
        if ("plat-qa".equals(platform.getId())) {
            pushJson(platform, annotationPayload(platform), "indicator-agent");
        }
    }

    private RuntimeSettings read(ManagedPlatform platform) {
        if (platform.getRuntimeJson() == null || platform.getRuntimeJson().isBlank()) {
            return RuntimeSettings.defaults();
        }
        try {
            return objectMapper.readValue(platform.getRuntimeJson(), RuntimeSettings.class);
        } catch (Exception ex) {
            return RuntimeSettings.defaults();
        }
    }

    private Map<String, Object> knowledgePayload(ManagedPlatform platform, RuntimeSettings settings) {
        Map<String, Object> body = new LinkedHashMap<>();
        putModel(body, "llm", platform.getLlmModelId());
        putModel(body, "embedding", platform.getEmbeddingModelId());
        putModel(body, "reranker", platform.getRerankModelId());
        body.put("rerankerEnabled", settings.rerankEnabled());
        body.put("defaultSearchMode", settings.searchMode());
        body.put("defaultSearchTopK", settings.searchTopK());
        body.put("defaultSearchEnableRerank", settings.searchEnableRerank());
        body.put("defaultRagTopK", settings.ragTopK());
        body.put("defaultRagEnableRerank", settings.ragEnableRerank());
        body.put("maxContextLength", settings.maxContextLength());
        body.put("rerankEvidenceThreshold", settings.rerankEvidenceThreshold());
        body.put("ragQueryRewriteEnabled", settings.rewriteEnabled());
        body.put("ragQueryRewriteMaxQueries", settings.rewriteMaxQueries());
        body.put("ragStatsListingLimit", settings.statsListingLimit());
        body.put("ragDataQueryDefaultLimit", settings.dataQueryDefaultLimit());
        body.put("ragDataQueryMaxLimit", settings.dataQueryMaxLimit());
        if (platform.getPromptId() != null && !platform.getPromptId().isBlank()) {
            body.put("systemPrompt", promptService.requireBody(platform.getPromptId()));
        }
        return body;
    }

    /**
     * 智能体配置下发：模型与提示词取自已发布的智能体版本；检索参数（知识库）与超时、并发等运行参数仍归项目自身。
     * 知识库的向量与重排模型、检索参数由业务服务自持，不在此下发。
     */
    Map<String, Object> agentConfigPayload(ManagedPlatform platform, JsonNode model, String systemPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!"plat-kb".equals(platform.getId())) {
            body.put("enabled", true);
            JsonNode runtime = runtimeNode(platform);
            copyInt(runtime, body, "timeoutMs");
            copyInt(runtime, body, "connectTimeoutMs");
            copyInt(runtime, body, "maxTokens");
            copyInt(runtime, body, "concurrency");
            if (runtime.hasNonNull("thinkingEnabled")) {
                body.put("thinkingEnabled", runtime.path("thinkingEnabled").asBoolean());
            }
        }
        body.put("llmModel", model.path("model").asText());
        body.put("llmBaseUrl", model.path("baseUrl").asText());
        body.put("systemPrompt", systemPrompt);
        return body;
    }

    private Map<String, Object> annotationPayload(ManagedPlatform platform) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        putModel(body, "llm", platform.getLlmModelId());
        if (platform.getPromptId() != null && !platform.getPromptId().isBlank()) {
            body.put("systemPrompt", promptService.requireBody(platform.getPromptId()));
        }
        JsonNode runtime = runtimeNode(platform);
        copyInt(runtime, body, "timeoutMs");
        copyInt(runtime, body, "connectTimeoutMs");
        copyInt(runtime, body, "maxTokens");
        copyInt(runtime, body, "concurrency");
        if (runtime.hasNonNull("thinkingEnabled")) {
            body.put("thinkingEnabled", runtime.path("thinkingEnabled").asBoolean());
        }
        return body;
    }

    private JsonNode runtimeNode(ManagedPlatform platform) {
        if (platform.getRuntimeJson() == null || platform.getRuntimeJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(platform.getRuntimeJson());
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static void copyInt(JsonNode runtime, Map<String, Object> body, String field) {
        if (runtime.hasNonNull(field) && runtime.path(field).canConvertToInt()) {
            body.put(field, runtime.path(field).asInt());
        }
    }

    private void pushJson(ManagedPlatform platform, Map<String, Object> body, String label) {
        Map<String, String> credentials = new LinkedHashMap<>();
        for (var entry : Map.of("llm", platform.getLlmModelId() == null ? "" : platform.getLlmModelId(),
                "embedding", platform.getEmbeddingModelId() == null ? "" : platform.getEmbeddingModelId(),
                "reranker", platform.getRerankModelId() == null ? "" : platform.getRerankModelId()).entrySet()) {
            body.remove(entry.getKey() + "ApiKey");
            if (!entry.getValue().isBlank()) credentials.put(entry.getKey(), entry.getValue());
        }
        delivery.enqueue(platform.getId(), settingsUrl(platform), body, credentials);
    }

    String settingsUrl(ManagedPlatform platform) {
        if ("plat-qa".equals(platform.getId())
                && indicatorSettingsUrl != null
                && !indicatorSettingsUrl.isBlank()) {
            return indicatorSettingsUrl.trim();
        }
        URI uri = URI.create(platform.getEntryUrl().trim());
        if (uri.getScheme() == null || uri.getHost() == null) {
            return platform.getEntryUrl().replaceAll("/+$", "") + "/api/settings";
        }
        String origin = uri.getScheme() + "://" + uri.getHost();
        if (uri.getPort() > 0) {
            origin += ":" + uri.getPort();
        }
        return origin + "/api/settings";
    }

    private void putModel(Map<String, Object> body, String prefix, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        modelService.putRuntimeSettings(body, prefix, modelId);
    }

    private ManagedPlatform require(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("platform not found: " + id));
    }
}
