package com.yiwei.midplat.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.fusion.FusionUpstreamFault;
import java.util.LinkedHashMap;
import java.util.Map;

/** Retrieval controls remain local to the knowledge service after agent ownership transfers. */
final class KnowledgeRuntimeSettings {
    private KnowledgeRuntimeSettings() {}

    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("searchMode", "defaultSearchMode"),
            Map.entry("searchTopK", "defaultSearchTopK"),
            Map.entry("ragTopK", "defaultRagTopK"),
            Map.entry("maxContextLength", "maxContextLength"),
            Map.entry("statsListingLimit", "ragStatsListingLimit"),
            Map.entry("dataQueryDefaultLimit", "ragDataQueryDefaultLimit"),
            Map.entry("dataQueryMaxLimit", "ragDataQueryMaxLimit"),
            Map.entry("rewriteEnabled", "ragQueryRewriteEnabled"),
            Map.entry("rewriteMaxQueries", "ragQueryRewriteMaxQueries"),
            Map.entry("rerankEnabled", "rerankerEnabled"),
            Map.entry("searchEnableRerank", "defaultSearchEnableRerank"),
            Map.entry("ragEnableRerank", "defaultRagEnableRerank"),
            Map.entry("rerankEvidenceThreshold", "rerankEvidenceThreshold"));

    static RuntimeSettings read(JsonNode live, ObjectMapper json) {
        var local = json.createObjectNode();
        FIELDS.forEach((key, remote) -> {
            if (live == null || !live.hasNonNull(remote))
                throw new FusionUpstreamFault(502, "知识库未返回完整检索参数，请检查业务服务版本");
            local.set(key, live.get(remote));
        });
        return json.convertValue(local, RuntimeSettings.class);
    }

    static Map<String, Object> patch(RuntimeSettingsPatch patch, ObjectMapper json) {
        JsonNode values = json.valueToTree(patch);
        Map<String, Object> payload = new LinkedHashMap<>();
        FIELDS.forEach((key, remote) -> {
            if (values != null && values.hasNonNull(key)) {
                JsonNode value = values.get(key);
                if (!value.isTextual() || !value.asText().isBlank())
                    payload.put(remote, json.convertValue(value, Object.class));
            }
        });
        return payload;
    }

    static boolean isRetrievalOnly(Map<String, Object> payload, Map<String, String> refs) {
        return refs.isEmpty() && !payload.isEmpty() && FIELDS.values().containsAll(payload.keySet());
    }
}
