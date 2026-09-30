package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.yiwei.midplat.model.ModelKind;
import com.yiwei.midplat.model.ModelService;
import com.yiwei.midplat.platform.AgentConfigDeliveryService;
import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.ManagedPlatformRepository;
import com.yiwei.midplat.platform.PlatformConfigDelivery;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** 智能体配置热更新：模型与提示词只来自智能体发布版本，中台直连下发并以下游读回为准。 */
@SpringBootTest(properties = {"midplat.platform-delivery.delay-ms=3600000", "midplat.agent-config-sync.initial-delay-ms=3600000"})
@ActiveProfiles("test") @Transactional
class AgentConfigDeliveryTest {
    @Autowired AgentConfigDeliveryService service;
    @Autowired PlatformConfigDelivery delivery;
    @Autowired ManagedPlatformRepository platforms;
    @Autowired ModelService models;
    @Autowired FusionOwnership ownership;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager tm;
    @MockBean FusionAgentClient agent;

    static final String BASE_URL = "https://model.example/v1";
    HttpServer server;
    ObjectNode live;
    AtomicReference<ObjectNode> sent = new AtomicReference<>();
    AtomicInteger puts = new AtomicInteger();
    String modelId;
    String deployment;
    String activeReleaseId = "";
    ArrayNode releases;

    @BeforeEach void setup() throws Exception {
        live = json.createObjectNode();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/settings", ex -> {
            if (ex.getRequestMethod().equals("PUT")) {
                ObjectNode body = (ObjectNode) json.readTree(ex.getRequestBody());
                sent.set(body); puts.incrementAndGet(); live.setAll(body);
            }
            byte[] bytes = json.writeValueAsBytes(Map.of("success", true, "data", live));
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/settings";
        var platform = platforms.findById("plat-an").orElseGet(() ->
                new ManagedPlatform("plat-an", "annotation", endpoint, "Tags", false, null));
        platform.updateEntryUrl(endpoint);
        platforms.saveAndFlush(platform);
        modelId = models.create(new ModelService.CreateModelCmd("E2E测试-下发模型", ModelKind.llm, "qwen-x", BASE_URL, "supplier-private-value", null, null)).id();
        if (em.isJoinedToTransaction()) em.flush();
        releases = json.createArrayNode();
        Mockito.when(agent.call(ArgumentMatchers.any(), ArgumentMatchers.eq("plat-an"), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("get"), ArgumentMatchers.any())).thenAnswer(call -> {
            ObjectNode dep = json.createObjectNode();
            dep.put("id", deployment).put("activeReleaseId", activeReleaseId).put("publishedReleaseId", releases.isEmpty() ? "" : releases.get(0).path("id").asText());
            return dep;
        });
        Mockito.when(agent.call(ArgumentMatchers.any(), ArgumentMatchers.eq("plat-an"), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("releases"), ArgumentMatchers.any())).thenAnswer(call -> releases);
    }

    @AfterEach void stop() {
        server.stop(0); ReflectionTestUtils.setField(ownership, "blockOutbound", false);
    }

    private void bind() {
        deployment = UUID.randomUUID().toString();
        db.update("insert into midplat_project_agent_binding(id,project_id,environment,alias,agent_id,desired_agent_version_id,deployment_id,source_definition_id,source_name,status,generation,operation_id) values(?,'plat-an','development','default',?,?,?,?,'test','READY',1,?)",
                "bnd-" + deployment, "ag-" + deployment, "av-" + deployment + "-1", deployment, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        db.update("insert into midplat_project_runtime_default(project_id,environment,route_kind,binding_id) values('plat-an','development','legacy-agent-runs',?)", "bnd-" + deployment);
    }

    private void release(String id, long sequence, String rule, String baseUrl) {
        ObjectNode release = json.createObjectNode();
        release.put("id", id).put("sequence", sequence);
        ObjectNode snapshot = release.putObject("snapshot");
        ArrayNode tasks = snapshot.putArray("tasks");
        ObjectNode other = tasks.addObject();
        other.put("key", "other").put("modelRevisionId", "mr-" + id);
        other.putArray("systemInstructions").add("不应被下发");
        ObjectNode task = tasks.addObject();
        task.put("key", "prelabel").put("modelRevisionId", "mr-" + id);
        task.putArray("systemInstructions").add("角色规则").add(rule);
        ObjectNode revision = snapshot.putObject("models").putObject("mr-" + id);
        revision.put("resourceId", modelId);
        revision.putObject("content").put("model", "qwen-x").put("baseUrl", baseUrl);
        releases.insert(0, release);
    }

    private String status() { return (String) delivery.status("plat-an").get("status"); }

    private long rows() { return db.queryForObject("select count(*) from midplat_platform_delivery where platform_id='plat-an'", Long.class); }

    @Test void publishedConfigIsPushedToUnmanagedDownstreamAndReadBack() {
        bind();
        release("r1", 1, "规则一", BASE_URL);
        var result = service.sync("plat-an", false);
        assertEquals("push", result.get("mode"));
        assertEquals("published", result.get("source"));
        assertEquals("prelabel", result.get("taskKey"));
        assertEquals("pending", status());
        assertEquals(0, puts.get());
        delivery.dispatch();
        assertEquals("applied", status());
        assertEquals("agent-config", delivery.status("plat-an").get("origin"));
        assertEquals("qwen-x", sent.get().path("llmModel").asText());
        assertEquals(BASE_URL, sent.get().path("llmBaseUrl").asText());
        assertEquals("角色规则\n\n规则一", sent.get().path("systemPrompt").asText());
        assertEquals("supplier-private-value", sent.get().path("llmApiKey").asText());
        assertTrue(sent.get().path("enabled").asBoolean());
        String outbox = db.queryForObject("select payload_json from midplat_platform_delivery where platform_id='plat-an' order by revision desc limit 1", String.class);
        assertFalse(outbox.contains("supplier-private-value"));
        assertFalse(outbox.contains("不应被下发"));
    }

    @Test void unchangedConfigIsNotQueuedAgainButNewReleaseIs() {
        bind();
        release("r1", 1, "规则一", BASE_URL);
        service.sync("plat-an", false); delivery.dispatch();
        service.sync("plat-an", false);
        assertEquals(1L, rows());
        assertEquals(true, service.view("plat-an").get("inSync"));
        release("r2", 2, "规则二", BASE_URL);
        assertEquals(false, service.view("plat-an").get("inSync"));
        service.sync("plat-an", false); delivery.dispatch();
        assertEquals(2L, rows());
        assertEquals("角色规则\n\n规则二", live.path("systemPrompt").asText());
        service.sync("plat-an", true);
        assertEquals(3L, rows());
    }

    @Test void activatedReleaseWinsOverNewerPublishedOne() {
        bind();
        release("r1", 1, "上线版本", BASE_URL);
        release("r2", 2, "仅发布未上线", BASE_URL);
        activeReleaseId = "r1";
        var result = service.sync("plat-an", false);
        assertEquals("active", result.get("source"));
        assertEquals(1L, ((Number) result.get("releaseSequence")).longValue());
        delivery.dispatch();
        assertEquals("角色规则\n\n上线版本", live.path("systemPrompt").asText());
    }

    @Test void downstreamAlreadyManagedByAgentIsNeverWritten() {
        bind();
        release("r1", 1, "规则一", BASE_URL);
        live.put("agentManaged", true);
        var result = service.sync("plat-an", true);
        assertEquals("pull", result.get("mode"));
        assertEquals(0L, rows());
        assertEquals("pull", service.view("plat-an").get("mode"));
        delivery.dispatch();
        assertEquals(0, puts.get());
    }

    @Test void unboundAndUnpublishedProjectsAreExplained() {
        assertEquals("unbound", service.sync("plat-an", false).get("mode"));
        bind();
        assertEquals("unpublished", service.sync("plat-an", false).get("mode"));
        assertEquals("unpublished", service.view("plat-an").get("mode"));
        assertEquals(0L, rows());
    }

    @Test void unsupportedProjectsAreNotTouched() {
        var view = service.view("plat-root-cause");
        assertEquals("unsupported", view.get("mode"));
        assertEquals(false, view.get("applicable"));
    }

    @Test void unbindingCancelsQueuedAgentDelivery() {
        bind();
        release("r1", 1, "规则一", BASE_URL);
        service.sync("plat-an", false);
        db.update("delete from midplat_project_runtime_default where project_id='plat-an'");
        db.update("delete from midplat_project_agent_binding where project_id='plat-an'");
        delivery.dispatch();
        assertEquals("failed", status());
        assertEquals(0, puts.get());
    }

    @Test void changedModelConnectionIsRejectedInsteadOfPushingStaleAddress() {
        bind();
        release("r1", 1, "规则一", "https://old.example/v1");
        service.sync("plat-an", false);
        delivery.dispatch();
        assertEquals("failed", status());
        assertEquals(0, puts.get());
    }

    @Test void developerIsolationStillBlocksOutboundWrites() {
        ReflectionTestUtils.setField(ownership, "blockOutbound", true);
        bind();
        release("r1", 1, "规则一", BASE_URL);
        service.sync("plat-an", false);
        assertEquals("blocked", status());
        delivery.dispatch();
        assertEquals(0, puts.get());
    }

    @Test void legacyWritersCannotBypassFusionOwnership() {
        bind();
        delivery.enqueue("plat-an", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/settings", Map.of("systemPrompt", "legacy"), Map.of());
        delivery.dispatch();
        assertEquals("failed", status());
        assertEquals(0, puts.get());
    }

    @Test void syncAndDispatchAppliesImmediatelyWithoutWaitingForTheQueue() {
        bind();
        release("r1", 1, "规则一", BASE_URL);
        var result = service.syncAndDispatch("plat-an", false);
        assertEquals("push", result.get("mode"));
        assertEquals("applied", status());
        assertEquals("角色规则\n\n规则一", live.path("systemPrompt").asText());
        release("r2", 2, "规则二", BASE_URL);
        service.syncAndDispatch("plat-an", false);
        assertEquals("角色规则\n\n规则二", live.path("systemPrompt").asText());
        assertEquals(2, puts.get());
    }

    @Test @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void activationTriggerHotUpdatesDownstreamWithoutWaitingForThePoller() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(tm);
        try {
            tx.executeWithoutResult(status -> bind());
            release("r1", 1, "规则一", BASE_URL);
            service.trigger("plat-an");
            Thread.sleep(300);
            assertEquals(0, puts.get());
            activeReleaseId = "r1";
            long deadline = System.currentTimeMillis() + 10000;
            while (!"applied".equals(status()) && System.currentTimeMillis() < deadline) Thread.sleep(100);
            assertEquals(1, puts.get());
            assertEquals("角色规则\n\n规则一", live.path("systemPrompt").asText());
            assertEquals("applied", status());
        } finally {
            tx.executeWithoutResult(status -> {
                db.update("delete from midplat_platform_delivery where platform_id='plat-an'");
                db.update("delete from midplat_project_runtime_default where project_id='plat-an'");
                db.update("delete from midplat_project_agent_binding where project_id='plat-an'");
            });
        }
    }
}
