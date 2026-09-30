package com.yiwei.midplat.evaluation.target;

import java.net.URI;
import java.net.URISyntaxException;

/** Credential-free input constructed from an immutable evaluation target snapshot. */
public final class EvaluationTargetRequest {

    public static final int MAX_TOKENS_LIMIT = 32_768;
    public static final int MAX_TIMEOUT_MS = 120_000;
    private static final double MAX_TEMPERATURE = 2.0d;

    private final String modelId;
    private final String baseUrl;
    private final String modelName;
    private final String promptBody;
    private final String input;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutMs;
    private final URI chatCompletionsUri;

    public EvaluationTargetRequest(String modelId, String baseUrl, String modelName, String promptBody, String input,
            double temperature, int maxTokens, int timeoutMs) {
        if (isBlank(modelId) || isBlank(baseUrl) || isBlank(modelName) || promptBody == null || input == null
                || !Double.isFinite(temperature) || temperature < 0 || temperature > MAX_TEMPERATURE
                || maxTokens < 1 || maxTokens > MAX_TOKENS_LIMIT || timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
            throw invalidRequest();
        }
        URI parsed = parseBaseUrl(baseUrl);
        this.modelId = modelId.trim();
        this.baseUrl = parsed.toString();
        this.modelName = modelName.trim();
        this.promptBody = promptBody;
        this.input = input;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutMs = timeoutMs;
        this.chatCompletionsUri = appendChatCompletions(parsed);
    }

    public String modelId() { return modelId; }
    public String baseUrl() { return baseUrl; }
    public String modelName() { return modelName; }
    public String promptBody() { return promptBody; }
    public String input() { return input; }
    public double temperature() { return temperature; }
    public int maxTokens() { return maxTokens; }
    public int timeoutMs() { return timeoutMs; }
    URI chatCompletionsUri() { return chatCompletionsUri; }

    @Override
    public String toString() {
        return "EvaluationTargetRequest[modelId=" + modelId + ", baseUrl=" + baseUrl + ", modelName=" + modelName
                + ", promptBody=[redacted], input=[redacted], temperature=" + temperature + ", maxTokens=" + maxTokens
                + ", timeoutMs=" + timeoutMs + "]";
    }

    private static URI parseBaseUrl(String value) {
        try {
            URI uri = new URI(value.trim());
            int port = uri.getPort();
            if ((!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || port == 0 || port < -1 || port > 65_535 || hasUnsafePath(uri.getRawPath())) {
                throw invalidRequest();
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw invalidRequest();
        }
    }

    private static boolean hasUnsafePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return false;
        if (rawPath.contains("%")) return true;
        for (String segment : rawPath.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) return true;
        }
        return false;
    }

    private static URI appendChatCompletions(URI base) {
        String path = base.getPath();
        String root = path == null || path.isEmpty() ? "" : (path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), root + "/chat/completions", null, null);
        } catch (URISyntaxException ex) {
            throw invalidRequest();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static EvaluationTargetException invalidRequest() {
        return EvaluationTargetException.configuration();
    }
}
