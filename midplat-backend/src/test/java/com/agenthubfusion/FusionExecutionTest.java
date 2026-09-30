package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.springframework.test.context.ActiveProfiles("fusion")
@SpringBootTest(classes=FusionApplication.class,properties={
 "fusion.runtime-directory=${java.io.tmpdir}/midplat-fusion-execution-tests",
 "spring.datasource.url=jdbc:h2:mem:fusion_execution;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.flyway.enabled=true","spring.flyway.locations=classpath:fusion/db/migration",
 "fusion.service-token=0123456789012345678901234567890123456789","fusion.midplat-url=http://127.0.0.1:9",
 "fusion.execution-enabled=true","fusion.execution-key=execution-test-key-01234567890123456789",
 "fusion.publication-poll-ms=3600000","fusion.run-poll-ms=3600000"})
class FusionExecutionTest {
 @Autowired DeploymentService service;@Autowired PublicationWorker publication;@Autowired ActivationService activation;@Autowired RunService runs;
 @Autowired BusinessRunService businessRuns;@Autowired ExecutionSigner signer;
 @Autowired RunWorker worker;@Autowired RuntimeUnits units;@Autowired AssetService assets;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
 @MockBean EffectiveConfigCompiler compiler;
 HttpServer upstream;ExecutorService httpPool;Queue<JsonNode> captured=new ConcurrentLinkedQueue<>();AtomicInteger cancelled=new AtomicInteger();
 Context a=new Context("alice","project-a","development",true),b=new Context("bob","project-b","development",true);
 Context clientA=new Context("service:project-a","project-a","development",true,"client-A"),clientB=new Context("service:project-a","project-a","development",true,"client-B");
 @BeforeEach void setup()throws Exception{
  units.stop();
  for(String table:List.of("fusion_tool_call","fusion_run_event","fusion_run","fusion_session","fusion_activation","fusion_runtime_unit"))db.update("delete from "+table);
  db.update("update fusion_deployment set published_release_id=null,active_release_id=null,pending_job_id=null");
  for(String table:List.of("fusion_audit","fusion_outbox","fusion_release","fusion_publication","fusion_deployment","fusion_definition","fusion_asset_revision","fusion_asset"))db.update("delete from "+table);
  service.createDefinition(body().put("idempotencyKey","definition").put("name","Definition").put("taskKey","general").put("taskName","Generic task"));
  when(compiler.compile(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(inv->{JsonNode draft=inv.getArgument(4);ObjectNode snapshot=body().put("effectiveRole",draft.path("role").path("body").asText());snapshot.set("draft",draft.deepCopy());snapshot.putObject("models");snapshot.putObject("assets");ArrayNode tasks=snapshot.putArray("tasks");for(JsonNode task:draft.path("tasks")){ObjectNode t=tasks.addObject().put("key",task.path("key").asText()).put("name",task.path("name").asText()).put("modelRevisionId",draft.path("defaultModelRevisionId").asText());t.putArray("systemInstructions").add(draft.path("role").path("body").asText()).add(task.path("instructions").asText());t.putArray("nodes");}return EffectiveConfigCompiler.canonical(snapshot);});
  upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);httpPool=Executors.newCachedThreadPool();upstream.setExecutor(httpPool);
  upstream.createContext("/api/fusion-internal/model-invocations/cancel",exchange->{cancelled.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));exchange.close();});
  upstream.createContext("/api/fusion-internal/model-invocations",exchange->{
   JsonNode request=json.readTree(exchange.getRequestBody());captured.add(request);String text=request.path("messages").get(0).path("content").asText();
   exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
   try(var output=exchange.getResponseBody()){
    String delta=json.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("content",text)))));output.write(("data: "+delta+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();
    if(request.path("messages").toString().contains("slow-input"))Thread.sleep(1500);
    output.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
   }catch(Exception ignored){}finally{exchange.close();}
  });upstream.start();
  String endpoint="http://127.0.0.1:"+upstream.getAddress().getPort();ReflectionTestUtils.setField(units,"gateway",endpoint);ReflectionTestUtils.setField(units,"selfUrl",endpoint);
 }
 @AfterEach void close(){units.stop();upstream.stop(0);httpPool.shutdownNow();}
 ObjectNode body(){return json.createObjectNode();}
 Release release(Context c,String id,String role){
  if(db.queryForObject("select count(*) from fusion_deployment where id=?",Long.class,id)==0)service.create(c,id,body().put("name",id).put("definitionId","definition"));
  Deployment d=service.get(c,id);ObjectNode draft=(ObjectNode)d.draft().deepCopy();draft.put("defaultModelRevisionId","model-"+id);draft.set("role",body().put("kind","inline").put("body",role));((ObjectNode)draft.path("tasks").get(0)).put("instructions","generic task rules");
  d=service.save(c,id,body().put("expectedRevision",d.revision()).set("draft",draft));var job=service.publish(c,id,body().put("expectedRevision",d.revision()).put("note","release").put("idempotencyKey",UUID.randomUUID().toString()));publication.process(job.id());assertEquals("ready",service.publication(c,id,job.id()).status());return service.releases(c,id).get(0);
 }
 void activate(Context c,String id,Release release){activation.activate(c,id,body().put("releaseId",release.id()).put("expectedActivationRevision",service.get(c,id).activationRevision()));}
 Map<String,Object> start(Context c,String id,String input,String session){ObjectNode request=body().put("input",input).put("taskKey","general").put("idempotencyKey",UUID.randomUUID().toString());if(session!=null)request.put("sessionId",session);return runs.start(c,id,request);}
 Map<String,Object> await(Context c,String id,String run)throws Exception{for(int i=0;i<400;i++){var value=runs.get(c,id,run);if(Set.of("succeeded","failed","cancelled").contains(value.get("status")))return value;Thread.sleep(25);}fail("Run did not terminate");return null;}
 @Test void credentialWorkflowRunsExecuteAndAppearInTheirProjectWithOwnedSessions() throws Exception {
  activate(a,"a",release(a,"a","api-workflow-role"));release(b,"b","private-role");
  Context caller=new Context("service:project-a","project-a","development",true);
  ObjectNode request=body().put("input","synthetic API input").put("taskKey","general").put("idempotencyKey","api-request-1");
  var first=runs.start(caller,"a",request);String id=(String)first.get("id"),session=(String)first.get("session_id");
  assertEquals(id,runs.start(caller,"a",request).get("id"));
  assertEquals(409,assertThrows(FusionFault.class,()->runs.start(caller,"a",request.deepCopy().put("input","different input"))).status);
  assertEquals("business",runs.get(a,"a",id).get("origin"));
  assertTrue(runs.list(a,"a").stream().anyMatch(r->id.equals(r.get("id"))));
  assertThrows(FusionFault.class,()->runs.get(b,"b",id));
  assertTrue(runs.list(b,"b").stream().noneMatch(r->id.equals(r.get("id"))));
  worker.poll();var completed=await(a,"a",id);assertEquals("succeeded",completed.get("status"));
  assertTrue(((String)completed.get("output")).contains("api-workflow-role"));
  assertFalse(((List<?>)runs.events(a,"a",id,0)).isEmpty());
  assertThrows(FusionFault.class,()->runs.closeSession(a,"a",session));
  runs.closeSession(caller,"a",session);
  assertEquals(403,assertThrows(FusionFault.class,()->start(caller,"a","followup",session)).status);
  assertEquals(422,assertThrows(FusionFault.class,()->runs.start(caller,"a",request.deepCopy().put("origin","business"))).status);
 }
 @Test void businessCallsPinReleaseRejectReplayAndAreVisibleToTheirProject() {
  Release old=release(a,"a","business-role");activate(a,"a",old);
  Context platform=new Context("service:project-a","project-a","development",true);
  ObjectNode request=body().put("idempotencyKey","business-one").put("taskKey","general");
  ObjectNode chat=body();chat.putArray("messages").addObject().put("role","user").put("content","business evidence");request.set("request",chat);
  var started=(Map<?,?>)businessRuns.start(platform,"a",request);String id=(String)started.get("runId");
  JsonNode claims=signer.verify("Bearer "+started.get("ticket"));
  assertEquals(old.id(),claims.path("releaseId").asText());assertEquals("model-a",claims.path("nodes").path("task").asText());
  assertEquals("business",runs.get(a,"a",id).get("origin"));assertTrue(runs.list(a,"a").stream().anyMatch(r->id.equals(r.get("id"))));
  assertEquals(409,assertThrows(FusionFault.class,()->businessRuns.start(platform,"a",request)).status);
  assertThrows(FusionFault.class,()->businessRuns.start(a,"a",request));assertThrows(FusionFault.class,()->runs.get(b,"b",id));
  Release newer=release(a,"a","new-role");activate(a,"a",newer);
  ObjectNode finish=body().put("id",id).put("status","succeeded").put("output","actual business result");
  businessRuns.finish(platform,"a",finish);
  assertEquals(old.id(),runs.get(a,"a",id).get("release_id"));assertEquals("succeeded",runs.get(a,"a",id).get("status"));
  assertEquals("actual business result",runs.get(a,"a",id).get("output"));
  assertEquals(1,db.queryForObject("select count(*) from fusion_run where origin='business'",Integer.class));
 }
 @Test void businessCompletionKeepsAnOperatorCancellation() {
  activate(a,"a",release(a,"a","business-cancel"));Context platform=new Context("service:project-a","project-a","development",true);
  ObjectNode request=body().put("idempotencyKey","business-cancel").put("taskKey","general");
  ObjectNode chat=body();chat.putArray("messages").addObject().put("role","user").put("content","test");request.set("request",chat);
  String id=(String)((Map<?,?>)businessRuns.start(platform,"a",request)).get("runId");runs.cancel(a,"a",id);
  businessRuns.finish(platform,"a",body().put("id",id).put("status","succeeded").put("output","late result"));
  assertEquals("cancelled",runs.get(a,"a",id).get("status"));assertEquals("",runs.get(a,"a",id).get("output"));
 }
 @Test void isolatedJvmsExecuteTenConcurrentRunsAndUseTheirOwnRoleAndModel()throws Exception{
  Release ar=release(a,"a","role-A"),br=release(b,"b","role-B");activate(a,"a",ar);activate(b,"b",br);
  List<Map<String,Object>> records=new ArrayList<>();for(int i=0;i<10;i++)records.add(start(i%2==0?a:b,i%2==0?"a":"b","input-"+i,null));worker.poll();
  for(int i=0;i<10;i++){var result=await(i%2==0?a:b,i%2==0?"a":"b",(String)records.get(i).get("id"));assertEquals("succeeded",result.get("status"),result.toString());assertTrue(((String)result.get("output")).contains(i%2==0?"role-A":"role-B"));assertEquals(i%2==0?ar.id():br.id(),result.get("release_id"));}
  assertEquals(10,captured.size());assertNotEquals(units.prepare(ar.id(),ar.hash(),ar.snapshot()).process().pid(),units.prepare(br.id(),br.hash(),br.snapshot()).process().pid());
  assertThrows(FusionFault.class,()->runs.get(b,"b",(String)records.get(0).get("id")));
 }
 @Test void sessionsStayPinnedAcrossActivationAndStaleActivationIsRejected()throws Exception{
  Release old=release(a,"a","old-role");activate(a,"a",old);var first=start(a,"a","first",null);worker.poll();assertEquals("succeeded",await(a,"a",(String)first.get("id")).get("status"));
  Release newer=release(a,"a","new-role");activate(a,"a",newer);
  var continued=start(a,"a","continue",(String)first.get("session_id"));var fresh=start(a,"a","new",null);worker.poll();
  assertEquals(old.id(),await(a,"a",(String)continued.get("id")).get("release_id"));assertTrue(((String)await(a,"a",(String)fresh.get("id")).get("output")).contains("new-role"));
  assertEquals(409,assertThrows(FusionFault.class,()->activation.activate(a,"a",body().put("releaseId",old.id()).put("expectedActivationRevision",0))).status);
  assertEquals(newer.id(),service.get(a,"a").activeReleaseId());
 }
 @Test void cancelStopsAnActiveStreamAndDoesNotReplayIt()throws Exception{
  activate(a,"a",release(a,"a","cancel-role"));var run=start(a,"a","slow-input",null);String id=(String)run.get("id");worker.poll();
  for(int i=0;i<200&&captured.isEmpty();i++)Thread.sleep(20);assertFalse(captured.isEmpty());runs.cancel(a,"a",id);
  assertEquals("cancelled",await(a,"a",id).get("status"));assertEquals(1,captured.size());assertTrue(cancelled.get()>0);
 }
 @Test void runDeadlineCancelsExternalWorkWithoutReplayingIt()throws Exception{
  activate(a,"a",release(a,"a","deadline-role"));var run=start(a,"a","slow-input",null);String id=(String)run.get("id");worker.poll();
  for(int i=0;i<200&&captured.isEmpty();i++)Thread.sleep(20);assertFalse(captured.isEmpty());
  db.update("update fusion_run set started_at=? where id=?",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(11),id);worker.poll();
  var result=await(a,"a",id);assertEquals("failed",result.get("status"));assertTrue(result.get("error").toString().contains("10 分钟"));assertEquals(1,captured.size());assertTrue(cancelled.get()>0);
 }
 @Test void failedReadinessKeepsActiveReleaseAndRunRequestCannotOverrideRules(){
  Release old=release(a,"a","working");activate(a,"a",old);Release broken=release(a,"a","broken");db.update("update fusion_release set snapshot='{}' where id=?",broken.id());
  assertThrows(FusionFault.class,()->activation.activate(a,"a",body().put("releaseId",broken.id()).put("expectedActivationRevision",1)));assertEquals(old.id(),service.get(a,"a").activeReleaseId());
  assertEquals(422,assertThrows(FusionFault.class,()->runs.start(a,"a",body().put("input","x").put("idempotencyKey","key").put("system","override"))).status);
 }
 @Test void assetsAreImmutableScopedAndDisabledResourcesFailClosed(){
  // W3：资产主数据写入退役；迁移通道（migration 身份）仍可写，普通管理身份被拒绝。
  assertEquals(403,assertThrows(FusionFault.class,()->assets.save(a,body().put("id","skill").put("name","Skill").put("kind","skill").put("expectedRevision",0).set("content",body().put("body","old instructions")))).status);
  Context migrator=new Context("migration-service","project-a","development",true,"",true);
  var created=(Map<?,?>)assets.save(migrator,body().put("id","skill").put("name","Skill").put("kind","skill").put("expectedRevision",0).set("content",body().put("body","old instructions")));
  String first=(String)created.get("revisionId");assets.save(migrator,body().put("id","skill").put("name","Skill").put("kind","skill").put("expectedRevision",1).set("content",body().put("body","new instructions")));
  assertEquals("old instructions",assets.resolve(a.projectId(),a.environment(),List.of(first)).path(first).path("content").path("body").asText());
  assertThrows(FusionFault.class,()->assets.resolve(b.projectId(),b.environment(),List.of(first)));assets.enabled(migrator,"skill",false);assertThrows(FusionFault.class,()->assets.resolve(a.projectId(),a.environment(),List.of(first)));
 }
 @Test void sameIdempotencyKeyUnderDifferentClientsCreatesIndependentRuns()throws Exception{
  activate(clientA,"a",release(clientA,"a","client-role"));
  ObjectNode request=body().put("input","shared input").put("taskKey","general").put("idempotencyKey","same-key");
  var runA=runs.start(clientA,"a",request);var runB=runs.start(clientB,"a",request);
  assertNotEquals(runA.get("id"),runB.get("id"));
  assertEquals(runA.get("id"),runs.start(clientA,"a",request).get("id"));
  assertEquals(runB.get("id"),runs.start(clientB,"a",request).get("id"));
  assertEquals("client-A",db.queryForObject("select client_id from fusion_run where id=?",String.class,runA.get("id")));
  assertEquals("client-B",db.queryForObject("select client_id from fusion_run where id=?",String.class,runB.get("id")));
 }
 @Test void clientsOwnTheirBusinessRunsAndSessionsAcrossReadAndMutationEntries(){
  activate(clientA,"a",release(clientA,"a","ownership-role"));
  ObjectNode request=body().put("idempotencyKey","owned-key").put("taskKey","general");
  ObjectNode chat=body();chat.putArray("messages").addObject().put("role","user").put("content","business evidence");request.set("request",chat);
  String id=(String)((Map<?,?>)businessRuns.start(clientA,"a",request)).get("runId");
  assertEquals(409,assertThrows(FusionFault.class,()->businessRuns.start(clientA,"a",request)).status);
  ObjectNode requestB=body().put("idempotencyKey","owned-key").put("taskKey","general");requestB.set("request",chat.deepCopy());
  String idB=(String)((Map<?,?>)businessRuns.start(clientB,"a",requestB)).get("runId");assertNotEquals(id,idB);
  assertEquals("client-B",db.queryForObject("select client_id from fusion_run where id=?",String.class,idB));
  String session=(String)runs.get(clientA,"a",id).get("session_id");
  assertEquals(404,assertThrows(FusionFault.class,()->runs.get(clientB,"a",id)).status);
  assertEquals(404,assertThrows(FusionFault.class,()->runs.events(clientB,"a",id,0)).status);
  assertEquals(404,assertThrows(FusionFault.class,()->runs.cancel(clientB,"a",id)).status);
  assertEquals(404,assertThrows(FusionFault.class,()->runs.closeSession(clientB,"a",session)).status);
  assertEquals(404,assertThrows(FusionFault.class,()->businessRuns.finish(clientB,"a",body().put("id",id).put("status","succeeded").put("output","stolen result"))).status);
  assertTrue(runs.list(clientA,"a").stream().anyMatch(r->id.equals(r.get("id"))));
  assertTrue(runs.list(clientB,"a").stream().noneMatch(r->id.equals(r.get("id"))));
  businessRuns.finish(clientA,"a",body().put("id",id).put("status","succeeded").put("output","actual result"));
  assertEquals("succeeded",runs.get(clientA,"a",id).get("status"));
 }
 @Test void executionTicketClaimsCarryTheOwnerClientId(){
  JsonNode claims=signer.verify("Bearer "+signer.sign(new Context("service:project-a","project-a","development",true,"client-A"),"dep","rel","run",body()));
  assertEquals("client-A",claims.path("clientId").asText());
  assertEquals("",signer.verify("Bearer "+signer.sign(new Context("service:project-a","project-a","development",true),"dep","rel","run",body())).path("clientId").asText());
 }
}
