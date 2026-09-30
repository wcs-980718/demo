package com.yiwei.midplat.evaluation.run;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Versioned, closed JSON envelope for persisted execution contracts. */
final class EvaluationRunSnapshotCodec {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static String target(EvaluationTargetSnapshot value) { return write(new TargetEnvelope(1, value)); }
    static String parameters(EvaluationRunParameters value) { return write(new ParametersEnvelope(1, value)); }
    static EvaluationTargetSnapshot target(String json) { try { TargetEnvelope e=JSON.readValue(json, TargetEnvelope.class); if(e.schemaVersion()!=1)throw new IllegalArgumentException(); return e.target(); } catch(Exception e){throw new IllegalArgumentException("运行快照无效");} }
    static EvaluationRunParameters parameters(String json) { try { ParametersEnvelope e=JSON.readValue(json, ParametersEnvelope.class); if(e.schemaVersion()!=1)throw new IllegalArgumentException(); return e.parameters(); } catch(Exception e){throw new IllegalArgumentException("运行快照无效");} }
    private static String write(Object value) { try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("快照序列化失败");} }
    private record TargetEnvelope(int schemaVersion, EvaluationTargetSnapshot target) {}
    private record ParametersEnvelope(int schemaVersion, EvaluationRunParameters parameters) {}
}
