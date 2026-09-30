package com.yiwei.midplat.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.fusion.FusionOwnership;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class RootCauseAgentPromptService {

    @org.springframework.beans.factory.annotation.Autowired private com.yiwei.midplat.fusion.FusionRuntimeBridge fusion;

    private static final Map<String, PromptSpec> SPECS = Map.of(
            "plat-root-cause", new PromptSpec(
                    "report_generation",
                    "skill-root-cause-report-generation",
                    "根因报告生成技能"),
            "plat-fishbone", new PromptSpec(
                    "fishbone_analysis",
                    "skill-root-cause-fishbone-analysis",
                    "鱼骨图分析技能"));

    private final FusionOwnership fusionOwnership;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
            })
            .build();

    @Value("${midplat.agent.root-cause.base-url:}")
    private String baseUrl;

    @Value("${midplat.agent.root-cause.api-key:}")
    private String apiKey;

    RootCauseAgentPromptService(ObjectMapper objectMapper, FusionOwnership fusionOwnership) {
        this.fusionOwnership = fusionOwnership;
        this.objectMapper = objectMapper;
    }

    AgentPromptBundle get(String platformId) {
        PromptSpec spec = requireSpec(platformId);
        if (fusionOwnership.manages(platformId)) {
            var configuration = fusion.configuration(platformId);
            JsonNode snapshot = configuration.release().path("snapshot");
            JsonNode task = fusionTask(snapshot, spec.task());
            StringBuilder skill = new StringBuilder();
            JsonNode rules = task.path("systemInstructions");
            for (int i = 1; i < rules.size(); i++) { if (skill.length() > 0) skill.append("\n\n"); skill.append(rules.get(i).asText()); }
            return new AgentPromptBundle(platformId, configuration.deployment().path("name").asText(), task.path("key").asText(),
                    snapshot.path("effectiveRole").asText(), spec.skillKey(), task.path("name").asText(), skill.toString(), false);
        }
        HttpRequest request = requestBuilder(resourcesUri()).GET().build();
        return send(platformId, spec, request, supportsResourceUpdates());
    }

    AgentRuntimeView runtime(String platformId) {
        PromptSpec spec = requireSpec(platformId);
        if (fusionOwnership.manages(platformId)) {
            var configuration = fusion.configuration(platformId);
            JsonNode snapshot = configuration.release().path("snapshot"), task = fusionTask(snapshot, spec.task());
            String model = snapshot.path("models").path(task.path("modelRevisionId").asText()).path("content").path("model").asText();
            return new AgentRuntimeView(platformId, "configured", configuration.deployment().path("name").asText(), model,
                    task.path("assetRevisionIds").size(), task.path("key").asText(), spec.skillKey(), task.path("name").asText(),
                    "agenthub:" + configuration.release().path("id").asText());
        }
        HttpRequest request = requestBuilder(healthUri(), false).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("智能体健康接口返回 " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return new AgentRuntimeView(
                    platformId,
                    root.path("status").asText("unknown"),
                    root.path("agent").asText(),
                    root.path("model").asText(),
                    root.path("skills").asInt(),
                    spec.task(),
                    spec.skillKey(),
                    spec.skillLabel(),
                    normalizedBaseUrl());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取智能体运行状态被中断", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("无法连接根因智能体健康接口", ex);
        }
    }

    AgentPromptBundle update(String platformId, AgentPromptUpdate request) {
        fusionOwnership.requireLegacyWritable(platformId);
        PromptSpec spec = requireSpec(platformId);
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("soul", request.soulContent());
        resources.put(spec.skillKey(), request.skillContent());
        try {
            String body = objectMapper.writeValueAsString(Map.of("resources", resources));
            HttpRequest httpRequest = requestBuilder(resourcesUri())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return send(platformId, spec, httpRequest, true);
        } catch (IOException ex) {
            throw new IllegalStateException("无法序列化智能体提示词", ex);
        }
    }

    private AgentPromptBundle send(String platformId, PromptSpec spec, HttpRequest request, boolean writable) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("智能体资源接口返回 " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode soul = findResource(root, "soul");
            JsonNode skill = findResource(root, spec.skillKey());
            return new AgentPromptBundle(
                    platformId,
                    root.path("agent").asText(),
                    spec.task(),
                    soul.path("content").asText(),
                    spec.skillKey(),
                    spec.skillLabel(),
                    skill.path("content").asText(),
                    writable);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取智能体提示词被中断", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("无法连接根因智能体资源接口", ex);
        }
    }

    private boolean supportsResourceUpdates() {
        try {
            HttpRequest request = requestBuilder(resourcesUri())
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 400
                    && response.headers().allValues("Allow").stream()
                    .anyMatch(value -> List.of(value.toUpperCase().split("[, ]+" )).contains("PUT"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private JsonNode findResource(JsonNode root, String key) {
        for (JsonNode resource : root.path("resources")) {
            if (key.equals(resource.path("key").asText())) {
                String content = resource.path("content").asText();
                if (content.isBlank()) {
                    throw new ResourceNotFoundException("智能体资源内容为空: " + key);
                }
                return resource;
            }
        }
        throw new ResourceNotFoundException("智能体资源不存在: " + key);
    }

    private static JsonNode fusionTask(JsonNode snapshot, String taskKey) {
        for (JsonNode task : snapshot.path("tasks")) if (taskKey.equals(task.path("key").asText())) return task;
        if (snapshot.path("tasks").size() == 1) return snapshot.path("tasks").get(0);
        throw new ResourceNotFoundException("发布版本没有对应的根因任务: " + taskKey);
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        return requestBuilder(uri, true);
    }

    private HttpRequest.Builder requestBuilder(URI uri, boolean apiKeyRequired) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("未配置根因智能体资源地址");
        }
        if (apiKeyRequired && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("未配置根因智能体 API Key");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        return builder;
    }

    private URI resourcesUri() {
        String normalized = normalizedBaseUrl();
        if (normalized.endsWith("/agent/resources")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        return URI.create(normalized + "/agent/resources");
    }

    private URI healthUri() {
        String normalized = normalizedBaseUrl();
        if (normalized.endsWith("/agent/resources")) {
            normalized = normalized.substring(0, normalized.length() - "/agent/resources".length());
        }
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - "/v1".length());
        }
        return URI.create(normalized + "/health");
    }

    private String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("未配置根因智能体资源地址");
        }
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private PromptSpec requireSpec(String platformId) {
        PromptSpec spec = SPECS.get(platformId);
        if (spec == null) {
            throw new ResourceNotFoundException("该项目没有独立的智能体提示词配置: " + platformId);
        }
        return spec;
    }

    private record PromptSpec(String task, String skillKey, String skillLabel) {}
}
