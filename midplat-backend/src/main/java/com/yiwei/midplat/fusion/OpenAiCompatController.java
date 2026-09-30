package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yiwei.midplat.platform.PlatformCredentialService;
import com.yiwei.midplat.platform.PlatformCredentialService.CallerPlatform;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 闭环根因/鱼骨业务的 OpenAI 兼容入口，替代原外部 runtime-java（214:8083）的位置。
 * close_loop 前端按 runtime 协议调用：请求体用 agent_task 区分任务、可选 thinking/
 * reasoning_effort 扩展字段、SSE 中混入 {"agenthub":{"trace":…}} 过程事件、并通过
 * GET agent/resources 展示 SOUL 与技能文档。本端点把请求适配到中台已发布版本与模型
 * 网关：提示词/模型统一由中台下发，业务方无需感知上游差异，外部运行时不再需要部署。
 */
@RestController
@RequestMapping("/api/runtime/agent/openai/v1")
public class OpenAiCompatController {

    private final PlatformCredentialService credentials;
    private final BusinessModelService service;
    private final FusionRuntimeBridge bridge;
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiCompatController(PlatformCredentialService credentials, BusinessModelService service, FusionRuntimeBridge bridge) {
        this.credentials = credentials;
        this.service = service;
        this.bridge = bridge;
    }

    private static final Set<String> TASKS = Set.of("fishbone_analysis", "report_generation");

    @PostMapping("/chat/completions")
    public void chat(@RequestHeader(value = "Authorization", required = false) String authorization,
                     @RequestBody JsonNode request,
                     HttpServletResponse response) throws IOException {
        CallerPlatform caller = credentials.requireCallerManagedPlatform(authorization);
        String task = resolveTask(request);
        ObjectNode payload = sanitize(request);
        boolean stream = payload.path("stream").asBoolean();
        // 业务端点要求每次调用带固定请求标识做幂等与审计；兼容入口按次生成。
        String requestId = "compat-" + UUID.randomUUID();
        if (stream) {
            response.setStatus(200);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            emitPreamble(response, task, payload);
        }
        try {
            service.chat(caller.platform(), caller.clientId(), task, requestId, payload, response, null, null);
        } catch (RuntimeException | IOException failure) {
            if (stream && response.isCommitted()) {
                emitStreamTail(response, failure.getMessage());
                return;
            }
            throw failure;
        }
        if (stream) {
            emitTrace(response, "model", "调用模型", "模型输出完成", "done", "reasoning", "finished", List.of());
            emitTrace(response, "output", "流式输出", "已将模型生成内容返回给业务页面", "done", "result", "finished", List.of());
        }
    }

    /** runtime 语义：agent_task 优先，缺失时按用户消息内容推断（与 runtime-java resolveTask 一致）。 */
    private static String resolveTask(JsonNode request) {
        String explicit = request == null ? "" : request.path("agent_task").asText("");
        if (TASKS.contains(explicit)) return explicit;
        StringBuilder text = new StringBuilder();
        for (JsonNode message : request == null ? List.<JsonNode>of() : request.path("messages")) {
            if ("user".equalsIgnoreCase(message.path("role").asText())) {
                text.append(message.path("content").asText("")).append('\n');
            }
        }
        return text.toString().contains("根因报告生成") || text.toString().contains("已确认的鱼骨图分析结果")
                || text.toString().contains("当前鱼骨图")
                ? "report_generation" : "fishbone_analysis";
    }

    /** 仅保留中台业务网关白名单字段；thinking 对象/布尔统一映射为 enable_thinking。 */
    private static ObjectNode sanitize(JsonNode request) {
        if (request == null || !request.isObject()) throw new IllegalArgumentException("模型请求必须为对象");
        ObjectNode copy = request.deepCopy();
        copy.remove(List.of("agent_task", "model", "reasoning_effort", "nodeId", "sequence"));
        JsonNode thinking = copy.remove("thinking");
        if (thinking != null) copy.put("enable_thinking", thinkingEnabled(thinking));
        // 对齐原 runtime-java 默认输出上限（8192）；缺省时网关兜底 2048 不足以容纳推理+正文。
        if (!copy.has("max_tokens") || !copy.path("max_tokens").isIntegralNumber()) copy.put("max_tokens", 8192);
        return copy;
    }

    private static boolean thinkingEnabled(JsonNode thinking) {
        if (thinking.isBoolean()) return thinking.asBoolean();
        return "enabled".equalsIgnoreCase(thinking.path("type").asText(""));
    }

    private void emitPreamble(HttpServletResponse response, String task, ObjectNode payload) throws IOException {
        boolean report = "report_generation".equals(task);
        emitTrace(response, "input", "解析请求", "已接收并解析用户消息", "done", "input", "accepted", List.of());
        emitTrace(response, "task-routing", "识别任务类型", "已路由到 " + task + " 流程", "done", "reasoning", "selected", List.of("任务：" + task));
        emitTrace(response, "skill", "选择执行技能", "当前任务仅使用对应业务技能", "done", "skill", "selected",
                List.of(report ? "root-cause-report-generation" : "root-cause-fishbone-analysis"));
        emitTrace(response, "model", "调用模型", "已向中台发布版本提交请求，等待首个正文片段", "running", "reasoning", "started",
                List.of("输出上限：" + payload.path("max_tokens").asLong(2048) + " tokens"));
    }

    private void emitStreamTail(HttpServletResponse response, String message) throws IOException {
        try {
            writeSse(response, Map.of("error", message == null || message.isBlank() ? "模型调用中断" : message));
            response.getOutputStream().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            response.flushBuffer();
        } catch (IOException ignored) {
        }
    }

    private void emitTrace(HttpServletResponse response, String id, String title, String detail,
                           String status, String kind, String phase, List<String> items) throws IOException {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("id", id);
        trace.put("title", title);
        trace.put("detail", detail);
        trace.put("status", status);
        trace.put("kind", kind);
        trace.put("phase", phase);
        trace.put("items", items == null ? List.of() : items.stream().filter(i -> i != null && !i.isBlank()).limit(8).toList());
        trace.put("timestamp", System.currentTimeMillis());
        writeSse(response, Map.of("agenthub", Map.of("progress", title, "trace", trace)));
    }

    private void writeSse(HttpServletResponse response, Object payload) throws IOException {
        response.getOutputStream().write(("data: " + json.writeValueAsString(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    /**
     * close_loop 系统参数-智能体中心页面以 runtime 资源协议展示 SOUL 与技能文档。
     * 托管模式下这些内容由中台已发布版本下发，此处只读映射；修改请到中台智能体配置。
     */
    @GetMapping("/agent/resources")
    public Map<String, Object> resources(@RequestHeader(value = "Authorization", required = false) String authorization) {
        CallerPlatform caller = credentials.requireCallerManagedPlatform(authorization);
        JsonNode release = bridge.configuration(caller.platform().getId(), caller.clientId()).release();
        JsonNode draft = release.path("snapshot").path("draft");
        List<Map<String, Object>> resources = new ArrayList<>();
        String role = draft.path("role").path("body").asText("");
        if (!role.isBlank()) {
            resources.add(Map.of("key", "soul", "title", "SOUL.md（中台托管，请在智能体配置中修改）",
                    "sourcePath", "profile/SOUL.md", "content", role, "type", "soul"));
        }
        for (JsonNode task : draft.path("tasks")) {
            String key = task.path("key").asText("");
            String instructions = task.path("instructions").asText("");
            if (key.isBlank() || instructions.isBlank()) continue;
            resources.add(Map.of("key", "skill-" + key,
                    "title", task.path("name").asText(key) + "（中台托管，请在智能体配置中修改）",
                    "sourcePath", "profile/skills/" + key + ".md", "content", instructions, "type", "skill"));
        }
        return Map.of("resources", resources);
    }
}
