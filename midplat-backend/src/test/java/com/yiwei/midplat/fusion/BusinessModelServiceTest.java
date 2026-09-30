package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.PlatformCredentialService.CallerPlatform;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BusinessModelServiceTest {
    ObjectMapper json=new ObjectMapper();
    CallerPlatform caller(ManagedPlatform project){return new CallerPlatform(project,"client-test",null);}
    ObjectNode request(){ObjectNode b=json.createObjectNode().put("model","caller-cannot-select-this").put("temperature",1.8).put("stream",true);b.putArray("messages").addObject().put("role","system").put("content","business tool schema");((ArrayNode)b.path("messages")).addObject().put("role","user").put("content","evidence");return b;}
    ObjectNode start(){ObjectNode b=json.createObjectNode().put("runId","run").put("releaseId","release-fixed").put("ticket","internal-ticket").put("temperature",0.1);ObjectNode task=b.putObject("task").put("responseFormat","json_object");task.putArray("systemInstructions").add("published role").add("published task");return b;}
    @Test void publishedRulesTemperatureAndFormatWinWhileToolsAndHistoryArePreserved() {
        ObjectNode payload=BusinessModelService.businessPayload(request());
        payload.putArray("tools").addObject().put("type","function").putObject("function").put("name","query_data");
        ((ArrayNode)payload.path("messages")).addObject().put("role","tool").put("tool_call_id","call-1").put("content","tool result");
        BusinessModelService.applyPublishedTask(payload,start());
        assertFalse(payload.has("model"));assertEquals("task",payload.path("nodeId").asText());assertEquals(0.1,payload.path("temperature").asDouble());
        assertEquals("json_object",payload.path("response_format").path("type").asText());assertTrue(payload.path("stream_options").path("include_usage").asBoolean());
        String rules=payload.path("messages").get(0).path("content").asText();assertTrue(rules.contains("business tool schema"));assertTrue(rules.endsWith("published role\n\npublished task"));
        assertEquals("call-1",payload.path("messages").get(2).path("tool_call_id").asText());assertEquals("query_data",payload.path("tools").get(0).path("function").path("name").asText());
        assertThrows(IllegalArgumentException.class,()->BusinessModelService.businessPayload(request().put("nodeId","override")));
    }
    @Test void modelCompletionIsRecordedAgainstTheReservedRunAndExposesOnlyTraceHeaders() throws Exception {
        FusionRuntimeBridge bridge=mock(FusionRuntimeBridge.class);ModelInvocationGateway gateway=mock(ModelInvocationGateway.class);ManagedPlatform project=mock(ManagedPlatform.class);
        when(bridge.command(eq(caller(project)),eq("startBusinessRun"),any())).thenReturn(start());
        when(gateway.invoke(eq("Bearer internal-ticket"),any(),any())).thenReturn(new ModelInvocationGateway.Result("succeeded",null,"actual answer",json.readTree("{\"prompt_tokens\":3,\"completion_tokens\":2}")));
        var service=new BusinessModelService(bridge,gateway);var response=new MockHttpServletResponse();service.chat(project,"client-test","answer","request-id",request(),response);
        assertEquals("run",response.getHeader("X-Agent-Run-Id"));assertEquals("release-fixed",response.getHeader("X-Agent-Release-Id"));assertFalse(response.getHeaderNames().toString().contains("ticket"));
        ArgumentCaptor<Object> body=ArgumentCaptor.forClass(Object.class);verify(bridge).command(eq(caller(project)),eq("finishBusinessRun"),body.capture());
        JsonNode ended=(JsonNode)body.getValue();assertEquals("run",ended.path("id").asText());assertEquals("succeeded",ended.path("status").asText());assertEquals("actual answer",ended.path("output").asText());assertEquals(3,ended.path("usage").path("prompt_tokens").asInt());
    }
    @Test void invalidRequestNeverReservesAndFailedProviderIsRecordedWithoutFallback() throws Exception {
        FusionRuntimeBridge bridge=mock(FusionRuntimeBridge.class);ModelInvocationGateway gateway=mock(ModelInvocationGateway.class);ManagedPlatform project=mock(ManagedPlatform.class);
        var service=new BusinessModelService(bridge,gateway);
        assertThrows(IllegalArgumentException.class,()->service.chat(project,"client-test","answer",null,request(),new MockHttpServletResponse()));verifyNoInteractions(bridge);
        when(bridge.command(eq(caller(project)),eq("startBusinessRun"),any())).thenReturn(start());
        when(gateway.invoke(anyString(),any(),any())).thenThrow(new FusionUpstreamFault(502,"provider unavailable"));
        assertThrows(FusionUpstreamFault.class,()->service.chat(project,"client-test","answer","failed-id",request(),new MockHttpServletResponse()));
        verify(bridge).command(eq(caller(project)),eq("finishBusinessRun"),argThat(body->body instanceof JsonNode n&&"failed".equals(n.path("status").asText())));
    }
    @Test void callerDeadlineIsPreservedAcrossRunReservationAndRecordedOnTimeout() throws Exception {
        FusionRuntimeBridge bridge=mock(FusionRuntimeBridge.class);ModelInvocationGateway gateway=mock(ModelInvocationGateway.class);ManagedPlatform project=mock(ManagedPlatform.class);
        Long deadline=System.currentTimeMillis()+5000;
        when(bridge.command(eq(caller(project)),eq("startBusinessRun"),any())).thenReturn(start());
        when(gateway.invoke(anyString(),any(),any(),eq(deadline))).thenThrow(new FusionUpstreamFault(504,"模型流超过执行时限，已关闭上游连接"));
        var service=new BusinessModelService(bridge,gateway);
        assertThrows(FusionUpstreamFault.class,()->service.chat(project,"client-test","answer","deadline-id",request(),new MockHttpServletResponse(),deadline,null));
        verify(gateway).remainingTimeoutMs(deadline);
        verify(gateway).invoke(eq("Bearer internal-ticket"),any(),any(),eq(deadline));
        verify(bridge).command(eq(caller(project)),eq("finishBusinessRun"),argThat(body->body instanceof JsonNode n&&"failed".equals(n.path("status").asText())&&n.path("error").asText().contains("执行时限")));
    }
    @Test void relativeBudgetIsConvertedBeforeReservationAndKeepsTheEarlierDeadline() throws Exception {
        FusionRuntimeBridge bridge=mock(FusionRuntimeBridge.class);ModelInvocationGateway gateway=mock(ModelInvocationGateway.class);ManagedPlatform project=mock(ManagedPlatform.class);
        long deadline=System.currentTimeMillis()+1000;
        when(gateway.deadlineAfter(1000)).thenReturn(deadline);
        when(bridge.command(eq(caller(project)),eq("startBusinessRun"),any())).thenReturn(start());
        when(gateway.invoke(anyString(),any(),any(),anyLong())).thenReturn(new ModelInvocationGateway.Result("succeeded",null,"answer",null));
        var service=new BusinessModelService(bridge,gateway);
        service.chat(project,"client-test","answer","relative-id",request(),new MockHttpServletResponse(),null,1000L);
        var order=inOrder(gateway,bridge);order.verify(gateway).deadlineAfter(1000);
        order.verify(gateway).remainingTimeoutMs(deadline);order.verify(gateway).payload(any(),eq("validation"));
        order.verify(bridge).command(eq(caller(project)),eq("startBusinessRun"),any());
        order.verify(gateway).invoke(anyString(),any(),any(),eq(deadline));
        service.chat(project,"client-test","answer","both-deadlines",request(),new MockHttpServletResponse(),deadline-500,1000L);
        verify(gateway).invoke(anyString(),any(),any(),eq(deadline-500));
        when(gateway.deadlineAfter(0)).thenThrow(new IllegalArgumentException("invalid timeout"));
        clearInvocations(bridge);
        assertThrows(IllegalArgumentException.class,()->service.chat(project,"client-test","answer","invalid-budget",request(),new MockHttpServletResponse(),null,0L));
        verifyNoInteractions(bridge);
    }
    @Test void configurationThreadsTheCallerClientIdIntoTheBridge() throws Exception {
        FusionRuntimeBridge bridge=mock(FusionRuntimeBridge.class);ModelInvocationGateway gateway=mock(ModelInvocationGateway.class);ManagedPlatform project=mock(ManagedPlatform.class);
        when(project.getId()).thenReturn("project-x");
        JsonNode release=json.readTree("{\"id\":\"rel-1\",\"sequence\":3,\"snapshot\":{\"tasks\":[{\"key\":\"answer\",\"name\":\"Answer\",\"modelRevisionId\":\"m-1\",\"systemInstructions\":[\"rule\"],\"nodes\":[],\"assetRevisionIds\":[]}],\"models\":{\"m-1\":{\"content\":{\"model\":\"provider-1\"}}}}}");
        when(bridge.configuration("project-x","client-test")).thenReturn(new FusionRuntimeBridge.Configuration(release,release));
        var view=(Map<?,?>)new BusinessModelService(bridge,gateway).configuration(project,"client-test");
        assertEquals("project-x",view.get("projectId"));assertEquals("client-test",view.get("clientId"));assertEquals("rel-1",view.get("releaseId"));
        assertEquals(3,view.get("releaseSequence"));
        var task=(Map<?,?>)((java.util.List<?>)view.get("tasks")).get(0);
        assertEquals("answer",task.get("key"));assertEquals("provider-1",task.get("model"));
        verify(bridge).configuration("project-x","client-test");
    }
}
