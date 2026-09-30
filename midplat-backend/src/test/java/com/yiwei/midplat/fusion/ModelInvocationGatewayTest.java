package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.yiwei.midplat.common.api.*;
import com.yiwei.midplat.model.*;
import com.yiwei.midplat.platform.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"midplat.fusion.execution-enabled=true","midplat.fusion.execution-key=execution-test-key-01234567890123456789"})
@ActiveProfiles("test") @Transactional
class ModelInvocationGatewayTest {
    static final String KEY="execution-test-key-01234567890123456789";
    @Autowired ObjectMapper json;@Autowired ModelInvocationGateway gateway;@Autowired ModelService models;@Autowired PlatformService projects;
    @Autowired FusionState state;@Autowired FusionCatalog catalog;@Autowired jakarta.persistence.EntityManager entityManager;@Autowired org.springframework.jdbc.core.JdbcTemplate db;
    HttpServer server;String project,deployment,modelId,revision;String sse;
    AtomicReference<JsonNode> received=new AtomicReference<>();AtomicReference<String> credential=new AtomicReference<>();
    @BeforeEach void setup()throws Exception{
        sse="data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\"}}]}}]}\n\ndata: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"x\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}\n\ndata: [DONE]\n\n";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{received.set(json.readTree(exchange.getRequestBody()));credential.set(exchange.getRequestHeaders().getFirst("Authorization"));exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var output=exchange.getResponseBody()){byte[] bytes=sse.getBytes(StandardCharsets.UTF_8);for(int offset=0;offset<bytes.length;offset+=7){output.write(bytes,offset,Math.min(7,bytes.length-offset));output.flush();}}});server.start();
        project=projects.create(new PlatformService.CreatePlatformCmd("invocation-test",null,null,null,null,null,null)).id();entityManager.flush();
        modelId=models.create(new ModelService.CreateModelCmd("gateway-model",ModelKind.llm,"provider-model","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","only-provider-secret",null,null,new ModelService.ModelCapabilities(true,true))).id();entityManager.flush();
        revision=(String)catalog.list("model").stream().filter(row->row.get("resourceId").equals(modelId)).findFirst().orElseThrow().get("id");
        deployment=(String)state.begin(project,"development","definition","test",UUID.randomUUID().toString()).get("deployment_id");state.complete(project,"development",deployment);state.resolve(project,"development",deployment,List.of(revision),List.of());
    }
    @AfterEach void stop(){server.stop(0);}
    String ticket(String run,long expiration,String selected)throws Exception{
        ObjectNode claims=json.createObjectNode().put("aud","fusion-model-v1").put("iat",Instant.now().getEpochSecond()).put("exp",expiration).put("projectId",project).put("environment","development").put("deploymentId",deployment).put("releaseId","release").put("runId",run).put("principal","tester").put("clientId","client-"+run);claims.putObject("nodes").put("node",selected);
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return "Bearer "+body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
    }
    String ticket(String run)throws Exception{return ticket(run,Instant.now().getEpochSecond()+600,revision);}
    ObjectNode request(){ObjectNode request=json.createObjectNode().put("nodeId","node").put("sequence",0).put("stream",true).put("tool_choice","auto");ArrayNode messages=request.putArray("messages");messages.addObject().put("role","system").put("content","trusted node");messages.addObject().put("role","user").put("content","input");messages.addObject().put("role","tool").put("tool_call_id","previous-call").put("content","result");request.putArray("tools").addObject().put("type","function").putObject("function").put("name","lookup").putObject("parameters").put("type","object");request.putObject("response_format").put("type","json_object");return request;}
    @Test void forwardsMessagesToolsStructuredOutputAndFragmentedStreamWithoutExposingKey()throws Exception{
        var response=new MockHttpServletResponse();var result=gateway.invoke(ticket("fields"),request(),response);
        JsonNode recorded=json.readTree(result.output());assertEquals("call-1",recorded.path("tool_calls").get(0).path("id").asText());assertEquals("{\"q\":\"x\"}",recorded.path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals(sse,response.getContentAsString());assertEquals("provider-model",received.get().path("model").asText());assertEquals(request().path("messages"),received.get().path("messages"));assertEquals(request().path("tools"),received.get().path("tools"));assertEquals("auto",received.get().path("tool_choice").asText());assertEquals("json_object",received.get().path("response_format").path("type").asText());assertEquals("Bearer only-provider-secret",credential.get());assertFalse(response.getContentAsString().contains("only-provider-secret"));
        var usage=db.queryForMap("select status,input_tokens,output_tokens,usage_status,client_id from midplat_model_invocation where run_id='fields'");assertEquals("succeeded",usage.get("status"));assertEquals(12L,((Number)usage.get("input_tokens")).longValue());assertEquals("reported",usage.get("usage_status"));assertEquals("client-fields",usage.get("client_id"));
    }
    @Test void nodeRoutingUsesIndependentProviderEndpointsAndCredentials()throws Exception{
        AtomicReference<String> otherKey=new AtomicReference<>();AtomicReference<JsonNode> otherBody=new AtomicReference<>();
        HttpServer other=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        other.createContext("/v1/chat/completions",exchange->{otherKey.set(exchange.getRequestHeaders().getFirst("Authorization"));otherBody.set(json.readTree(exchange.getRequestBody()));exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));exchange.close();});other.start();
        try{
            String modelB=models.create(new ModelService.CreateModelCmd("provider-b",ModelKind.llm,"second-provider-model","http://127.0.0.1:"+other.getAddress().getPort()+"/v1","second-provider-private",null,null,new ModelService.ModelCapabilities(true,true))).id();entityManager.flush();
            String revisionB=(String)catalog.list("model").stream().filter(row->row.get("resourceId").equals(modelB)).findFirst().orElseThrow().get("id");
            state.resolve(project,"development",deployment,List.of(revisionB),List.of());
            gateway.invoke(ticket("provider-a"),request(),new MockHttpServletResponse());
            gateway.invoke(ticket("provider-b",Instant.now().getEpochSecond()+600,revisionB),request(),new MockHttpServletResponse());
            assertEquals("Bearer only-provider-secret",credential.get());assertEquals("provider-model",received.get().path("model").asText());
            assertEquals("Bearer second-provider-private",otherKey.get());assertEquals("second-provider-model",otherBody.get().path("model").asText());
        }finally{other.stop(0);}
    }
    @Test void rejectsExpiredForgedAndManagementCredentialsBeforeUpstream()throws Exception{
        assertThrows(UnauthorizedException.class,()->gateway.invoke(ticket("expired",Instant.now().getEpochSecond()-1,revision),request(),new MockHttpServletResponse()));
        assertThrows(UnauthorizedException.class,()->gateway.invoke(ticket("forged")+"x",request(),new MockHttpServletResponse()));assertThrows(UnauthorizedException.class,()->gateway.invoke("Bearer management-service-token",request(),new MockHttpServletResponse()));assertNull(received.get());
    }
    @Test void capabilityRevocationIsRecheckedEvenForAnOlderRelease()throws Exception{
        models.update(modelId,new ModelService.CreateModelCmd("gateway-model",ModelKind.llm,"provider-model","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",null,null,null,new ModelService.ModelCapabilities(false,false)));entityManager.flush();
        assertThrows(IllegalArgumentException.class,()->gateway.invoke(ticket("caps-revoked"),request(),new MockHttpServletResponse()));assertNull(received.get());
        assertTrue(((JsonNode)catalog.require(revision,"model").get("content")).path("capabilities").path("toolCalls").asBoolean());
    }
    @Test void checksLiveAvailabilityAndNodeScopeOnEveryCall()throws Exception{
        var body=request().put("nodeId","other");assertThrows(ForbiddenException.class,()->gateway.invoke(ticket("other"),body,new MockHttpServletResponse()));
        db.update("insert into midplat_model_availability(model_id,enabled) values(?,false)",modelId);assertThrows(ForbiddenException.class,()->gateway.invoke(ticket("revoked"),request(),new MockHttpServletResponse()));assertNull(received.get());
    }
    @Test void duplicateAndCancelledRunsCannotCallAgain()throws Exception{
        gateway.invoke(ticket("once"),request(),new MockHttpServletResponse());received.set(null);
        assertThrows(ConflictException.class,()->gateway.invoke(ticket("once"),request(),new MockHttpServletResponse()));gateway.cancel(ticket("cancelled"));assertThrows(ConflictException.class,()->gateway.invoke(ticket("cancelled"),request(),new MockHttpServletResponse()));assertNull(received.get());
    }
    @Test void stalledBodyIsClosedAtTheDeadlineAndRecordedAsFailed()throws Exception{
        server.removeContext("/v1/chat/completions");var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/chat/completions",exchange->{exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{exchange.close();}});
        org.springframework.test.util.ReflectionTestUtils.setField(gateway,"callTimeoutMs",200L);long started=System.nanoTime();
        try{assertThrows(Exception.class,()->gateway.invoke(ticket("stalled"),request(),new MockHttpServletResponse()));
            assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()<2000);
            var row=db.queryForMap("select status,error from midplat_model_invocation where run_id='stalled'");assertEquals("failed",row.get("status"));assertTrue(row.get("error").toString().contains("执行时限"));
        }finally{release.countDown();org.springframework.test.util.ReflectionTestUtils.setField(gateway,"callTimeoutMs",300000L);Thread.interrupted();}
    }
    @Test void incompleteStreamIsFailedAndUnknownUsageIsNotZero()throws Exception{
        sse="data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n";
        assertThrows(java.io.IOException.class,()->gateway.invoke(ticket("partial"),request(),new MockHttpServletResponse()));
        var row=db.queryForMap("select status,input_tokens,usage_status from midplat_model_invocation where run_id='partial'");assertEquals("failed",row.get("status"));assertNull(row.get("input_tokens"));assertEquals("unknown",row.get("usage_status"));
    }
    @Test void callerDeadlineStopsStalledStreamingBody()throws Exception { assertCallerDeadline(true); }
    @Test void completedStreamFinishesWithoutWaitingForProviderConnectionToClose()throws Exception {
        server.removeContext("/v1/chat/completions");var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/chat/completions",exchange->{
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
            try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{exchange.close();}
        });
        try {
            var response=new MockHttpServletResponse();
            var result=gateway.invoke(ticket("done-open-connection"),request(),response,System.currentTimeMillis()+600);
            assertEquals("succeeded",result.status());assertEquals(sse,response.getContentAsString());
            assertEquals(12,result.usage().path("prompt_tokens").asInt());
            assertEquals("succeeded",db.queryForObject("select status from midplat_model_invocation where run_id='done-open-connection'",String.class));
            assertTrue(((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(gateway,"active")).isEmpty());
        }finally{release.countDown();Thread.interrupted();}
    }
    @Test void callerDeadlineStopsStalledNonStreamingBody()throws Exception { assertCallerDeadline(false); }
    void assertCallerDeadline(boolean stream)throws Exception {
        server.removeContext("/v1/chat/completions");var release=new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> leakedDeadline=new AtomicReference<>();
        server.createContext("/v1/chat/completions",exchange->{
            leakedDeadline.set(exchange.getRequestHeaders().getFirst("X-Request-Deadline-Ms"));
            exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");exchange.sendResponseHeaders(200,0);
            try {release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{exchange.close();}
        });
        long started=System.nanoTime();
        try {
            FusionUpstreamFault failure=assertThrows(FusionUpstreamFault.class,()->gateway.invoke(ticket("caller-deadline"),request().put("stream",stream),new MockHttpServletResponse(),System.currentTimeMillis()+250));
            assertTrue(failure.getMessage().contains("执行时限"));
            assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()<1500);
            var row=db.queryForMap("select status,error from midplat_model_invocation where run_id='caller-deadline'");
            assertEquals("failed",row.get("status"));assertTrue(row.get("error").toString().contains("执行时限"));
            assertNull(leakedDeadline.get());
            assertTrue(((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(gateway,"active")).isEmpty());
        }finally{release.countDown();Thread.interrupted();}
    }
    @Test void deadlineCanOnlyShortenServerBudgetAndExpiredRequestsNeverReachProvider()throws Exception {
        assertEquals(300000L,gateway.remainingTimeoutMs(System.currentTimeMillis()+900000));
        assertThrows(IllegalArgumentException.class,()->gateway.remainingTimeoutMs(0L));
        assertThrows(FusionUpstreamFault.class,()->gateway.invoke(ticket("expired-caller"),request(),new MockHttpServletResponse(),System.currentTimeMillis()-1));
        assertNull(received.get());
        assertEquals(0,db.queryForObject("select count(*) from midplat_model_invocation where run_id='expired-caller'",Integer.class));
    }
    @Test void relativeBudgetUsesGatewayClockAndCannotExtendServerLimit() {
        long before=System.currentTimeMillis();long deadline=gateway.deadlineAfter(250);
        assertTrue(deadline>=before+250&&deadline<=System.currentTimeMillis()+250);
        long capped=gateway.deadlineAfter(Long.MAX_VALUE);
        assertTrue(capped>=before+300000&&capped<=System.currentTimeMillis()+300000);
        assertThrows(IllegalArgumentException.class,()->gateway.deadlineAfter(0));
        assertThrows(IllegalArgumentException.class,()->gateway.deadlineAfter(-1));
    }
    @Test void cancellationStopsAnActiveProviderBeforeAnyAnswerBytes()throws Exception {
        server.removeContext("/v1/chat/completions");var release=new java.util.concurrent.CountDownLatch(1);
        var cancelled=new java.util.concurrent.CompletableFuture<Void>();
        server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();
            try{gateway.cancelAuthorizedRun("cancel-live");cancelled.complete(null);release.await(2,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception e){cancelled.completeExceptionally(e);}finally{exchange.close();}
        });
        try {
            long started=System.nanoTime();
            assertThrows(ConflictException.class,()->gateway.invoke(ticket("cancel-live"),request(),new MockHttpServletResponse()));
            Thread.interrupted();cancelled.get(1,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()<1500);
            assertEquals("cancelled",db.queryForObject("select status from midplat_model_invocation where run_id='cancel-live'",String.class));
            assertTrue(((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(gateway,"active")).isEmpty());
        }finally{release.countDown();Thread.interrupted();}
    }
    @Test void refusesEndpointChangesInsteadOfSendingNewCredentialToOldProvider()throws Exception{
        models.update(modelId,new ModelService.CreateModelCmd("changed",ModelKind.llm,"new","http://127.0.0.1:9/v1","new-key",null,null));entityManager.flush();
        assertThrows(ConflictException.class,()->gateway.invoke(ticket("moved"),request(),new MockHttpServletResponse()));assertNull(received.get());
    }
    @Test void historicalDeniedProjectGrantDoesNotBlockConfiguredRuntime()throws Exception{
        db.update("insert into midplat_model_grant(project_id,environment,model_id,enabled) values(?,?,?,false)",project,"development",modelId);
        gateway.invoke(ticket("historical-denial"),request(),new MockHttpServletResponse());
        assertNotNull(received.get());
    }
}
