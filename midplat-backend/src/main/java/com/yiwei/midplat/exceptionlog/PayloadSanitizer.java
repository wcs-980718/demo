package com.yiwei.midplat.exceptionlog;

import java.util.List;
import java.util.Locale;

/**
 * 上报内容入库前的脱敏与截断。与前端展示层同一份敏感键口径：
 * authorization/token/apiKey/secret/password/bearer 一律替换为 ***。
 */
public final class PayloadSanitizer {

    public static final int REQUEST_BODY_LIMIT = 4000;
    public static final int QUERY_PARAMS_LIMIT = 2000;
    public static final int ERROR_MESSAGE_LIMIT = 2000;

    private static final List<String> SENSITIVE_KEYS =
            List.of("authorization", "token", "apikey", "api_key", "secret", "password", "bearer");

    private PayloadSanitizer() {}

    public static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "…（已截断）";
    }

    /** JSON 体做结构化脱敏；非 JSON 文本退化为 bearer 正则脱敏。 */
    public static String sanitizeBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(trimmed);
                maskNode(node);
                return truncate(mapper.writeValueAsString(node), REQUEST_BODY_LIMIT);
            } catch (Exception ignored) {
                // 非合法 JSON，走文本脱敏
            }
        }
        return truncate(trimmed.replaceAll("(?i)(bearer\\s+)[\\w.\\-]+", "$1***"), REQUEST_BODY_LIMIT);
    }

    private static void maskNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            var fields = object.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (isSensitive(entry.getKey())) {
                    object.put(entry.getKey(), "***");
                } else {
                    maskNode(entry.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(PayloadSanitizer::maskNode);
        }
    }

    private static boolean isSensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.stream().anyMatch(lower::contains);
    }
}
