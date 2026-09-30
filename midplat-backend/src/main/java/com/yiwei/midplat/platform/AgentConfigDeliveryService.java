package com.yiwei.midplat.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.fusion.FusionRuntimeBridge;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 智能体配置热更新：项目绑定智能体后，模型与提示词只在智能体配置里维护，
 * 这里把当前生效的发布版本直连下发到业务项目的 /api/settings，并以下游读回一致为准。
 * 下游若已启用智能体接入（agentManaged），配置由下游主动读取，发布上线即生效，不再下发。
 */
@Service
public class AgentConfigDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(AgentConfigDeliveryService.class);
    /** 项目 → 承载“对话模型 + 系统提示词”的任务；缺失时取第一个任务。 */
    private static final Map<String, String> PRIMARY_TASK = Map.of("plat-kb", "answer", "plat-an", "prelabel", "plat-qa", "answer");

    private final ManagedPlatformRepository platforms;
    private final FusionRuntimeBridge bridge;
    private final PlatformRuntimeService runtime;
    private final PlatformConfigDelivery delivery;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String environment;
    private final Map<String, String> handled = new ConcurrentHashMap<>();
    private final Map<String, String> modes = new ConcurrentHashMap<>();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "agent-config-delivery");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService watchers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "agent-config-watch");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.Set<String> watching = ConcurrentHashMap.newKeySet();

    AgentConfigDeliveryService(
            ManagedPlatformRepository platforms,
            FusionRuntimeBridge bridge,
            PlatformRuntimeService runtime,
            PlatformConfigDelivery delivery,
            JdbcTemplate db,
            ObjectMapper json,
            @Value("${midplat.fusion.runtime-environment:development}") String environment) {
        this.platforms = platforms;
        this.bridge = bridge;
        this.runtime = runtime;
        this.delivery = delivery;
        this.db = db;
        this.json = json;
        this.environment = environment == null || environment.isBlank() ? "development" : environment;
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        worker.shutdownNow();
        watchers.shutdownNow();
    }

    public static boolean supports(String platformId) {
        return PRIMARY_TASK.containsKey(platformId);
    }

    private static boolean eligible(ManagedPlatform platform) {
        return supports(platform.getId()) && !platform.isConsume() && platform.getEntryUrl() != null && !platform.getEntryUrl().isBlank();
    }

    /** 当前项目的智能体配置与下发状态；不探测下游，下游模式取最近一次探测结果。 */
    public Map<String, Object> view(String project) {
        ManagedPlatform platform = require(project);
        Map<String, Object> view = new LinkedHashMap<>();
        if (!supports(project)) return with(view, "unsupported", "该项目不通过智能体配置下发模型与提示词");
        if (!eligible(platform)) return with(view, "unsupported", "项目未配置入口地址，无法下发配置");
        Derived derived;
        try {
            derived = derive(platform);
        } catch (RuntimeException ex) {
            return with(view, "unknown", "暂时无法读取智能体配置：" + safeMessage(ex));
        }
        if (derived.state != null) return with(view, derived.state, derived.message);
        describe(view, derived);
        view.put("delivery", delivery.status(project));
        String detected = modes.get(project);
        if ("pull".equals(detected)) return with(view, "pull", "下游已启用智能体接入，发布上线后由业务项目直接读取，无需下发");
        Map<String, Object> latest = latestDelivery(project);
        boolean inSync = latest != null && sameTarget(latest, runtime.settingsUrl(platform), derived.body);
        view.put("inSync", inSync);
        return with(view, detected == null ? "push" : detected, inSync ? "配置已提交下发，结果见下发状态" : "有新的智能体配置待下发");
    }

    /** 立即核对并下发。force 时即使内容与上次相同也重新下发（用于失败后重试）。 */
    public Map<String, Object> sync(String project, boolean force) {
        ManagedPlatform platform = require(project);
        Map<String, Object> view = new LinkedHashMap<>();
        if (!eligible(platform)) return with(view, "unsupported", "该项目不通过智能体配置下发模型与提示词");
        Derived derived = derive(platform);
        if (derived.state != null) return with(view, derived.state, derived.message);
        describe(view, derived);
        String endpoint = runtime.settingsUrl(platform);
        JsonNode live = delivery.probeSettings(endpoint);
        if (live.path("agentManaged").asBoolean(false)) {
            modes.put(project, "pull");
            view.put("delivery", delivery.status(project));
            return with(view, "pull", "下游已启用智能体接入，发布上线后由业务项目直接读取，无需下发");
        }
        modes.put(project, "push");
        Map<String, Object> latest = latestDelivery(project);
        boolean unchanged = !force && latest != null && sameTarget(latest, endpoint, derived.body);
        if (!unchanged) delivery.enqueue(project, endpoint, derived.payload, Map.of("llm", derived.modelId), PlatformConfigDelivery.ORIGIN_AGENT_CONFIG);
        view.put("delivery", delivery.status(project));
        view.put("inSync", true);
        return with(view, "push", unchanged ? "配置与上次下发一致" : "已提交下发，结果见下发状态");
    }

    /** 核对并立即下发（不等待队列轮询），用于手动重新下发与发布/上线后的即时热更新。 */
    public Map<String, Object> syncAndDispatch(String project, boolean force) {
        Map<String, Object> view = sync(project, force);
        if ("push".equals(view.get("mode"))) {
            delivery.dispatch();
            view.put("delivery", delivery.status(project));
        }
        return view;
    }

    /**
     * 智能体发布或上线后立即触发：上线是同步的，立刻下发；发布由智能体服务异步生成版本，
     * 这里短周期盯住部署摘要，版本一变化就下发，最长等待 90 秒。
     */
    public void trigger(String project) {
        if (!supports(project) || !watching.add(project)) return;
        String before = fingerprintOrNull(project);
        try {
            watchers.execute(() -> {
                try {
                    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
                    do {
                        String now = fingerprintOrNull(project);
                        if (now != null && !now.equals(before)) {
                            syncAndDispatch(project, false);
                            handled.put(project, now);
                            return;
                        }
                        Thread.sleep(500);
                    } while (System.nanoTime() < deadline);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ex) {
                    log.debug("智能体配置即时同步失败 project={} reason={}", project, safeMessage(ex));
                } finally {
                    watching.remove(project);
                }
            });
        } catch (RuntimeException ex) {
            watching.remove(project);
        }
    }

    private String fingerprintOrNull(String project) {
        try {
            ManagedPlatform platform = platforms.findById(project).orElse(null);
            if (platform == null || !eligible(platform)) return null;
            JsonNode deployment = bridge.deploymentSummary(project);
            return deployment == null ? null : fingerprint(deployment, platform);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String fingerprint(JsonNode deployment, ManagedPlatform platform) {
        return deployment.path("id").asText() + "|" + deployment.path("activeReleaseId").asText()
                + "|" + deployment.path("publishedReleaseId").asText() + "|" + runtime.settingsUrl(platform);
    }

    /** 周期核对：兜底，发布或上线的版本变化后自动把新配置直连下发到业务项目。 */
    @Scheduled(fixedDelayString = "${midplat.agent-config-sync.delay-ms:3000}", initialDelayString = "${midplat.agent-config-sync.initial-delay-ms:15000}")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    runOnce();
                } finally {
                    ticking.set(false);
                }
            });
        } catch (RuntimeException ex) {
            ticking.set(false);
        }
    }

    void runOnce() {
        List<String> projects = db.queryForList(
                "select distinct project_id from midplat_project_agent_binding where environment=? and status='READY'", String.class, environment);
        for (String project : projects) {
            if (!supports(project)) continue;
            try {
                ManagedPlatform platform = platforms.findById(project).orElse(null);
                if (platform == null || !eligible(platform)) continue;
                JsonNode deployment = bridge.deploymentSummary(project);
                if (deployment == null) continue;
                String fingerprint = fingerprint(deployment, platform);
                if (fingerprint.equals(handled.get(project))) continue;
                Map<String, Object> result = syncAndDispatch(project, false);
                if ("push".equals(result.get("mode")) || "unpublished".equals(result.get("mode"))) handled.put(project, fingerprint);
            } catch (RuntimeException ex) {
                log.debug("智能体配置同步失败 project={} reason={}", project, safeMessage(ex));
            }
        }
    }

    private record Derived(String state, String message, JsonNode release, boolean active, String taskKey, String modelName,
                           String modelId, Map<String, Object> payload, String body) {
        static Derived none(String state, String message) {
            return new Derived(state, message, null, false, null, null, null, null, null);
        }
    }

    private Derived derive(ManagedPlatform platform) {
        String project = platform.getId();
        FusionRuntimeBridge.Effective effective = bridge.effective(project);
        if (effective == null) {
            boolean bound = db.queryForObject(
                    "select count(*) from midplat_project_agent_binding where project_id=? and environment=? and status='READY'",
                    Long.class, project, environment) > 0;
            return bound ? Derived.none("unpublished", "已绑定智能体，尚未发布版本；发布后自动下发")
                    : Derived.none("unbound", "尚未绑定智能体；绑定并发布后，模型与提示词将在此热更新");
        }
        JsonNode snapshot = effective.release().path("snapshot");
        JsonNode task = primaryTask(snapshot.path("tasks"), PRIMARY_TASK.get(project));
        if (task == null) return Derived.none("unpublished", "发布版本中没有可下发的任务");
        JsonNode revision = snapshot.path("models").path(task.path("modelRevisionId").asText());
        JsonNode content = revision.path("content");
        String modelId = revision.path("resourceId").asText();
        if (modelId.isBlank() || content.path("model").asText().isBlank())
            return Derived.none("unpublished", "发布版本缺少可用的对话模型，请重新发布");
        StringJoiner prompt = new StringJoiner("\n\n");
        task.path("systemInstructions").forEach(rule -> {
            if (!rule.asText().isBlank()) prompt.add(rule.asText());
        });
        if (prompt.length() == 0) return Derived.none("unpublished", "发布版本缺少规则提示词，请重新发布");
        Map<String, Object> payload = runtime.agentConfigPayload(platform, content, prompt.toString());
        try {
            return new Derived(null, null, effective.release(), effective.active(), task.path("key").asText(),
                    content.path("model").asText(), modelId, payload, json.writeValueAsString(new TreeMap<>(payload)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("配置无法序列化", ex);
        }
    }

    private static JsonNode primaryTask(JsonNode tasks, String key) {
        for (JsonNode task : tasks) if (task.path("key").asText().equals(key)) return task;
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    private static void describe(Map<String, Object> view, Derived derived) {
        view.put("source", derived.active ? "active" : "published");
        view.put("releaseId", derived.release.path("id").asText());
        view.put("releaseSequence", derived.release.path("sequence").asLong());
        view.put("taskKey", derived.taskKey);
        view.put("model", derived.modelName);
        view.put("promptChars", ((String) derived.payload.get("systemPrompt")).length());
    }

    private Map<String, Object> latestDelivery(String project) {
        List<Map<String, Object>> rows = db.queryForList(
                "select endpoint,payload_json,origin from midplat_platform_delivery where platform_id=? order by revision desc limit 1", project);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean sameTarget(Map<String, Object> latest, String endpoint, String body) {
        return PlatformConfigDelivery.ORIGIN_AGENT_CONFIG.equals(latest.get("origin"))
                && endpoint.equals(latest.get("endpoint")) && body.equals(latest.get("payload_json"));
    }

    private static Map<String, Object> with(Map<String, Object> view, String mode, String message) {
        view.put("applicable", !"unsupported".equals(mode));
        view.put("mode", mode);
        view.put("message", message);
        return view;
    }

    private static String safeMessage(RuntimeException ex) {
        return ex instanceof com.yiwei.midplat.fusion.FusionUpstreamFault || ex instanceof com.yiwei.midplat.common.api.ConflictException
                ? ex.getMessage() : "服务暂不可用";
    }

    private ManagedPlatform require(String id) {
        return platforms.findById(id).orElseThrow(() -> new ResourceNotFoundException("platform not found: " + id));
    }
}
