package com.yiwei.midplat.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.fusion.FusionOwnership;
import com.yiwei.midplat.model.ModelService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable settings delivery. Only a matching downstream read-back counts as applied. */
@Service
@EnableScheduling
public class PlatformConfigDelivery {
    private final JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ObjectMapper json;
    private final FusionOwnership ownership;
    private final ModelService models;
    private final java.util.concurrent.ScheduledExecutorService deadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"settings-response-deadline");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy void stop(){deadlines.shutdownNow();}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public PlatformConfigDelivery(JdbcTemplate db, ObjectMapper json, FusionOwnership ownership, ModelService models, org.springframework.transaction.PlatformTransactionManager manager) {
        this.db = db; this.json = json; this.ownership = ownership; this.models = models;this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    private int write(String sql,Object...args){return tx.execute(status->db.update(sql,args));}

    /** 智能体配置（已绑定项目的模型与提示词）作为下发来源；只有它可以写入被融合部署管理的项目。 */
    public static final String ORIGIN_AGENT_CONFIG = "agent-config";

    @Transactional
    public void enqueue(String project, String endpoint, Map<String,Object> payload, Map<String,String> refs) {
        enqueue(project, endpoint, payload, refs, null);
    }

    @Transactional
    public void enqueue(String project, String endpoint, Map<String,Object> payload, Map<String,String> refs, String origin) {
        try {
            // Shared project-row lock makes revision allocation safe across writers and replicas.
            db.queryForObject("select id from midplat_platform where id=? for update", String.class, project);
            long revision = db.queryForObject("select coalesce(max(revision),0)+1 from midplat_platform_delivery where platform_id=?", Long.class, project);
            String body = json.writeValueAsString(new TreeMap<>(payload));
            String status = "pending", error = null;
            try { if (!managedRetrieval(project, payload, refs)) ownership.requireOutboundAllowed(); } catch (RuntimeException ex) {
                status = "blocked"; error = "开发环境已保存目标配置；现网配置下发保持隔离";
            }
            write("insert into midplat_platform_delivery(id,platform_id,revision,endpoint,payload_json,credential_refs_json,desired_hash,status,last_error,origin) values(?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), project, revision, endpoint, body, json.writeValueAsString(refs), hash(body), status, error, origin);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("配置无法序列化", ex); }
    }

    public Map<String,Object> status(String project) {
        List<Map<String,Object>> rows = db.queryForList("select revision,status,last_error,desired_hash,confirmed_hash,created_at,finished_at,origin from midplat_platform_delivery where platform_id=? order by revision desc limit 1", project);
        Long applied = db.queryForObject("select max(revision) from midplat_platform_delivery where platform_id=? and status='applied'", Long.class, project);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("desiredRevision", rows.isEmpty() ? null : rows.get(0).get("revision"));
        result.put("appliedRevision", applied);
        result.put("status", rows.isEmpty() ? "untracked" : rows.get(0).get("status"));
        result.put("lastError", rows.isEmpty() ? null : rows.get(0).get("last_error"));
        result.put("desiredHash", rows.isEmpty() ? null : rows.get(0).get("desired_hash"));
        result.put("confirmedHash", rows.isEmpty() ? null : rows.get(0).get("confirmed_hash"));
        result.put("origin", rows.isEmpty() ? null : rows.get(0).get("origin"));
        return result;
    }

    @Scheduled(fixedDelayString="${midplat.platform-delivery.delay-ms:1500}")
    public void dispatch() {
        write("update midplat_platform_delivery set status='failed',last_error='下发进程中断，实际状态未知，请重新保存以核对',finished_at=CURRENT_TIMESTAMP where status='sending' and lease_until<CURRENT_TIMESTAMP");
        var rows = db.queryForList("select * from midplat_platform_delivery d where status='pending' and not exists(select 1 from midplat_platform_delivery older where older.platform_id=d.platform_id and older.revision<d.revision and older.status in ('pending','sending')) order by created_at limit 4");
        for (var row : rows) {
            String id = (String) row.get("id");
            if (write("update midplat_platform_delivery set status='sending',attempts=attempts+1,lease_until=? where id=? and status='pending'",
                    Timestamp.from(Instant.now().plusSeconds(60)), id) != 1) continue;
            try {
                JsonNode expected = json.readTree((String)row.get("payload_json"));
                Map<String,Object> payload = json.convertValue(expected, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                JsonNode refs = json.readTree((String)row.get("credential_refs_json"));
                Map<String,String> credentialRefs = json.convertValue(refs, new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});
                String platformId = (String)row.get("platform_id");
                if (ORIGIN_AGENT_CONFIG.equals(row.get("origin"))) {
                    ownership.requireOutboundAllowed();
                    if (!ownership.manages(platformId)) throw new IllegalStateException("项目已解除智能体绑定，本次配置下发已取消");
                } else if (!managedRetrieval(platformId, payload, credentialRefs)) {
                    ownership.requireOutboundAllowed();
                    ownership.requireLegacyWritable(platformId);
                }
                for (Iterator<String> names = refs.fieldNames(); names.hasNext();) {
                    String prefix = names.next(), modelId = refs.path(prefix).asText();
                    var current = models.requireEvaluationSnapshot(modelId);
                    if (!Objects.equals(current.baseUrl(), expected.path(prefix+"BaseUrl").asText())
                            || !Objects.equals(current.modelName(), expected.path(prefix+"Model").asText()))
                        throw new IllegalStateException("模型连接配置已变化，请重新保存目标配置");
                    String key = models.requireCurrentApiKey(modelId);
                    if (key != null && !key.isBlank()) payload.put(prefix+"ApiKey", key);
                }
                URI endpoint = URI.create((String)row.get("endpoint"));
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
                response(http.send(request, HttpResponse.BodyHandlers.ofInputStream()));
                JsonNode actual = response(http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream()));
                for (Iterator<String> names=expected.fieldNames(); names.hasNext();) {
                    String key=names.next();
                    if (!expected.get(key).equals(actual.get(key))) throw new IllegalStateException("下游已接收，但读取结果与目标配置不一致："+key);
                }
                write("update midplat_platform_delivery set status='applied',confirmed_hash=desired_hash,last_error=null,finished_at=CURRENT_TIMESTAMP where id=? and status='sending'",id);
            } catch (InterruptedException ex) {
                try { fail(id,"下发等待中断，实际状态未知"); }
                finally { Thread.currentThread().interrupt(); }
                return;
            } catch (Exception ex) {
                // Do not persist upstream bodies, URLs or transport exceptions containing credentials.
                String detail=ex instanceof IllegalStateException ? ex.getMessage() : "配置下发或读取确认失败，请检查下游连通性后重新保存";
                fail(id, detail);
            }
        }
    }

    private boolean managedRetrieval(String project, Map<String,Object> payload, Map<String,String> refs) {
        return "plat-kb".equals(project) && ownership.manages(project)
                && KnowledgeRuntimeSettings.isRetrievalOnly(payload, refs);
    }

    public JsonNode readSettings(String endpoint) {
        try {
            return response(http.send(HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.yiwei.midplat.fusion.FusionUpstreamFault(502, "读取知识库检索参数被中断");
        } catch (Exception ex) {
            throw new com.yiwei.midplat.fusion.FusionUpstreamFault(502, "无法读取知识库当前检索参数，请检查业务服务连通性");
        }
    }

    /** 探测业务项目当前配置（含是否已由智能体接入托管），失败时不推断任何默认值。 */
    public JsonNode probeSettings(String endpoint) {
        try {
            return response(http.send(HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.yiwei.midplat.fusion.FusionUpstreamFault(502, "读取业务项目当前配置被中断");
        } catch (Exception ex) {
            throw new com.yiwei.midplat.fusion.FusionUpstreamFault(502, "无法读取业务项目当前配置，请检查入口地址与业务服务连通性");
        }
    }

    private JsonNode response(HttpResponse<java.io.InputStream> response) throws Exception {
        var deadline=deadlines.schedule(()->{try{response.body().close();}catch(Exception ignored){}},15,java.util.concurrent.TimeUnit.SECONDS);
        try (var in=response.body()) {
            if (response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("下游配置接口返回 HTTP "+response.statusCode());
            byte[] bytes=in.readNBytes(300001);
            if (bytes.length>300000) throw new IllegalStateException("下游配置响应超出限制");
            JsonNode body=bytes.length==0 ? json.createObjectNode() : json.readTree(bytes);
            if (body.has("success")&&!body.path("success").asBoolean() || body.has("code")&&body.path("code").asInt()!=200)
                throw new IllegalStateException("下游配置接口拒绝目标配置");
            return body.has("data") ? body.get("data") : body;
        } finally { deadline.cancel(false); }
    }
    private void fail(String id,String message) {
        write("update midplat_platform_delivery set status='failed',last_error=?,finished_at=CURRENT_TIMESTAMP where id=? and status='sending'",message,id);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
