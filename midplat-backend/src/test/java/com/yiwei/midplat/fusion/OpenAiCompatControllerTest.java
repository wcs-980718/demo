package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.PlatformCredentialService;
import com.yiwei.midplat.platform.PlatformCredentialService.CallerPlatform;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** close_loop 根因/鱼骨的 OpenAI 兼容入口：agent_task 路由、字段清洗、SSE trace 前缀与资源只读视图。 */
class OpenAiCompatControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private PlatformCredentialService credentials;
    private BusinessModelService service;
    private FusionRuntimeBridge bridge;
    private OpenAiCompatController controller;
    private ManagedPlatform platform;

    @BeforeEach
    void setUp() {
        credentials = mock(PlatformCredentialService.class);
        service = mock(BusinessModelService.class);
        bridge = mock(FusionRuntimeBridge.class);
        controller = new OpenAiCompatController(credentials, service, bridge);
        platform = mock(ManagedPlatform.class);
        when(platform.getId()).thenReturn("plat-root-cause");
        when(credentials.requireCallerManagedPlatform(any()))
                .thenReturn(new CallerPlatform(platform, "client-1", null));
    }

    private ObjectNode requestBody(String task, boolean stream) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("model", "root_cause");
        body.put("stream", stream);
        body.put("max_tokens", 8192);
        if (task != null) body.put("agent_task", task);
        body.putObject("thinking").put("type", "enabled");
        body.put("reasoning_effort", "high");
        body.putArray("messages").addObject().put("role", "user").put("content", "{\"basicInfo\":{\"问题名称\":\"E2E测试\"}}");
        return body;
    }

    @Test
    void streamingChatRoutesTaskSanitizesAndEmitsTracePreamble() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse res = invocation.getArgument(5);
            res.getOutputStream().write("data: {\"choices\":[{\"delta\":{\"content\":\"## 分析\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            res.flushBuffer();
            return null;
        }).when(service).chat(any(), any(), any(), any(), any(), any(), any(), any());

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.chat("Bearer k", requestBody("fishbone_analysis", true), response);

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).chat(eq(platform), eq("client-1"), eq("fishbone_analysis"), argThat(k -> k != null && !k.isBlank()),
                payload.capture(), eq(response), isNull(), isNull());
        JsonNode sent = payload.getValue();
        assertFalse(sent.has("agent_task"), "agent_task 必须剔除");
        assertFalse(sent.has("model"), "model 必须剔除");
        assertFalse(sent.has("reasoning_effort"), "reasoning_effort 必须剔除");
        assertFalse(sent.has("thinking"), "thinking 对象必须剔除");
        assertTrue(sent.path("enable_thinking").asBoolean(), "thinking.enabled 应映射为 enable_thinking");
        assertEquals(8192, sent.path("max_tokens").asInt());

        String body = response.getContentAsString();
        assertTrue(body.contains("\"agenthub\""), "应先补发 agenthub trace 事件");
        assertTrue(body.indexOf("\"agenthub\"") < body.indexOf("## 分析"), "trace 事件必须先于模型正文");
        assertTrue(body.contains("root-cause-fishbone-analysis"), "技能 trace 应指向鱼骨技能");
        assertTrue(body.contains("text/event-stream".equals(response.getContentType()) ? "" : "") || true);
        assertEquals("text/event-stream;charset=UTF-8", response.getContentType());
    }

    @Test
    void missingAgentTaskFallsBackToHeuristic() throws Exception {
        ObjectNode body = requestBody(null, false);
        body.path("messages").get(0).withObject("").put("content", "");
        ((ObjectNode) body.path("messages").get(0)).put("content", "{\"当前鱼骨图\":{\"分支\":[]}}");

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.chat("Bearer k", body, response);

        verify(service).chat(any(), any(), eq("report_generation"), any(), any(), any(), any(), any());
        assertFalse(response.getContentAsString().contains("agenthub"), "非流式不得写 trace 前缀");
    }

    @Test
    void streamFailureAfterCommitWritesErrorEvent() throws Exception {
        doThrow(new IllegalStateException("上游模型返回 HTTP 429"))
                .when(service).chat(any(), any(), any(), any(), any(), any(), any(), any());

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.chat("Bearer k", requestBody("report_generation", true), response);

        String body = response.getContentAsString();
        assertTrue(body.contains("\"error\":\"上游模型返回 HTTP 429\""), "已提交流式响应应以 SSE error 事件收尾");
        assertTrue(body.contains("[DONE]"));
    }

    @Test
    void resourcesExposePublishedRoleAndTaskInstructions() {
        ObjectNode draft = JsonNodeFactory.instance.objectNode();
        draft.putObject("role").put("kind", "inline").put("body", "你是根因分析专家");
        var tasks = draft.putArray("tasks");
        tasks.addObject().put("key", "fishbone_analysis").put("name", "鱼骨图根因分析").put("instructions", "鱼骨任务规则");
        tasks.addObject().put("key", "report_generation").put("name", "根因报告生成").put("instructions", "报告任务规则");
        ObjectNode release = JsonNodeFactory.instance.objectNode();
        release.putObject("snapshot").set("draft", draft);
        when(bridge.configuration(eq("plat-root-cause"), any()))
                .thenReturn(new FusionRuntimeBridge.Configuration(JsonNodeFactory.instance.objectNode(), release));

        Map<String, Object> view = controller.resources("Bearer k");
        var resources = (java.util.List<Map<String, Object>>) view.get("resources");
        assertEquals(3, resources.size());
        assertEquals("soul", resources.get(0).get("type"));
        assertEquals("你是根因分析专家", resources.get(0).get("content"));
        assertEquals("skill-fishbone_analysis", resources.get(1).get("key"));
        assertEquals("鱼骨任务规则", resources.get(1).get("content"));
        assertEquals("skill-report_generation", resources.get(2).get("key"));
    }
}
