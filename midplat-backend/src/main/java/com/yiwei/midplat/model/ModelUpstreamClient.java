package com.yiwei.midplat.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ModelUpstreamClient {

    private final ObjectMapper json;
    private final HttpClient http;

    public ModelUpstreamClient(ObjectMapper json) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean ping(AiModel model) {
        try {
            ModelService.ModelRuntimeConfiguration configuration = new ModelService.ModelRuntimeConfiguration(
                    model.getKind(), model.getModelName(), model.getBaseUrl(), model.getApiKey());
            return switch (model.getKind()) {
                case llm -> chat(configuration, null, "ping", 8).ok();
                case embedding -> embed(configuration, "ping").ok();
                case rerank -> rerank(configuration, "ping", List.of("ping")).ok();
            };
        } catch (Exception ex) {
            return false;
        }
    }

    public ChatResult chat(ModelService.ModelRuntimeConfiguration model, String systemPrompt, String userInput) {
        return chat(model, systemPrompt, userInput, 60);
    }

    public ChatResult chat(ModelService.ModelRuntimeConfiguration model, String systemPrompt, String userInput, int timeoutSeconds) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model.modelName());
        body.put("max_tokens", timeoutSeconds <= 8 ? 8 : 1024);
        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userInput);
        JsonNode response = post(join(model.baseUrl(), "/chat/completions"), model.apiKey(), body, timeoutSeconds);
        String content = textAt(response, "choices", 0, "message", "content");
        if (content == null) {
            content = textAt(response, "output_text");
        }
        return new ChatResult(true, content == null ? "" : content, model.modelName());
    }

    public EmbedResult embed(ModelService.ModelRuntimeConfiguration model, String input) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model.modelName());
        body.put("input", input);
        JsonNode response = post(join(model.baseUrl(), "/embeddings"), model.apiKey(), body, 30);
        JsonNode vector = response.path("data").path(0).path("embedding");
        return new EmbedResult(true, vector, model.modelName());
    }

    public RerankResult rerank(ModelService.ModelRuntimeConfiguration model, String query, List<String> documents) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model.modelName());
        body.put("query", query);
        ArrayNode docs = body.putArray("documents");
        documents.forEach(docs::add);
        JsonNode response = post(join(model.baseUrl(), "/rerank"), model.apiKey(), body, 30);
        JsonNode results = response.path("results");
        if (results.isMissingNode() || results.isEmpty()) {
            results = response.path("data");
        }
        return new RerankResult(true, results, model.modelName());
    }

    private JsonNode post(String url, String apiKey, ObjectNode body, int timeoutSeconds) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalArgumentException("上游模型返回 " + response.statusCode() + ": " + truncate(response.body()));
            }
            JsonNode node = json.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
            return node == null ? json.createObjectNode() : node;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("调用上游模型失败：" + ex.getMessage());
        }
    }

    private static String join(String baseUrl, String path) {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return root + path;
    }

    private static String textAt(JsonNode node, Object... path) {
        JsonNode cursor = node;
        for (Object step : path) {
            if (cursor == null || cursor.isMissingNode()) {
                return null;
            }
            cursor = step instanceof Integer index ? cursor.path(index) : cursor.path(String.valueOf(step));
        }
        return cursor == null || cursor.isMissingNode() || cursor.isNull() ? null : cursor.asText();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    public record ChatResult(boolean ok, String content, String model) {}
    public record EmbedResult(boolean ok, JsonNode embedding, String model) {}
    public record RerankResult(boolean ok, JsonNode results, String model) {}
}
