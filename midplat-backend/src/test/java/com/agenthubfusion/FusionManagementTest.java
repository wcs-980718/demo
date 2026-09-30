package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.context.ActiveProfiles("fusion")
@SpringBootTest(classes=FusionApplication.class,properties={
 "fusion.runtime-directory=${java.io.tmpdir}/midplat-fusion-management-tests",
 "spring.datasource.url=jdbc:h2:mem:fusion;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.flyway.enabled=true","spring.flyway.locations=classpath:fusion/db/migration",
 "fusion.service-token=0123456789012345678901234567890123456789","fusion.midplat-url=http://127.0.0.1:9","fusion.publication-poll-ms=3600000"})
@AutoConfigureMockMvc
class FusionManagementTest {
 @Autowired DeploymentService service;@Autowired PublicationWorker worker;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
 @MockBean EffectiveConfigCompiler compiler;
 Context a=new Context("alice","project-a","development",true),b=new Context("bob","project-b","development",true);
 @BeforeEach void setup(){
  db.update("update fusion_deployment set published_release_id=null,active_release_id=null,pending_job_id=null");
  for(String table:List.of("fusion_audit","fusion_outbox","fusion_release","fusion_publication","fusion_deployment"))db.update("delete from "+table);
  db.update("delete from fusion_definition");
  service.createDefinition(body().put("idempotencyKey","general-assistant-v1").put("name","Test definition").put("taskKey","main").put("taskName","Test task"));
  when(compiler.compile(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(inv->json.createObjectNode().set("draft",((JsonNode)inv.getArgument(4)).deepCopy()));
 }
 ObjectNode body(){return json.createObjectNode();}
 Deployment create(Context c,String id){return service.create(c,id,body().put("name",id).put("definitionId","general-assistant-v1"));}
 Deployment configure(Context c,String id,String role){
  var d=service.get(c,id);ObjectNode draft=(ObjectNode)d.draft().deepCopy();draft.put("defaultModelRevisionId","model-revision");draft.set("role",body().put("kind","inline").put("body",role));
  return service.save(c,id,body().put("expectedRevision",d.revision()).set("draft",draft));
 }
 ObjectNode publication(Deployment d,String key){return body().put("expectedRevision",d.revision()).put("note","release "+key).put("idempotencyKey",key);}
 @Test void draftsAreIsolatedAndRejectStaleEditors(){
  create(a,"a");create(b,"b");var original=service.get(a,"a");configure(a,"a","only-a");
  assertEquals("",service.get(b,"b").draft().path("role").path("body").asText());
  FusionFault error=assertThrows(FusionFault.class,()->service.save(a,"a",body().put("expectedRevision",original.revision()).set("draft",original.draft())));assertEquals(409,error.status);
  assertEquals("only-a",service.get(a,"a").draft().path("role").path("body").asText());
 }
 @Test void serviceTransportRejectsMissingCredentialReadOnlyWriteAndWrongProject()throws Exception{
  create(a,"a");var get=json.valueToTree(new Command(a,"get","a",body()));
  mvc.perform(post("/internal/fusion/command").contentType("application/json").content(get.toString())).andExpect(status().isUnauthorized());
  for(Command command:List.of(new Command(b,"get","a",body()),new Command(new Context("viewer","project-a","development",false),"save","a",body()))){
   mvc.perform(post("/internal/fusion/command").header("Authorization","Bearer 0123456789012345678901234567890123456789").contentType("application/json").content(json.writeValueAsString(command))).andExpect(status().isForbidden());
  }
 }
 @Test void fivePublicationsAndRestorePreserveHistoryAndDraft(){
  create(a,"a");for(int i=1;i<=5;i++){var d=configure(a,"a","role-"+i);var job=service.publish(a,"a",publication(d,"publish-"+i));worker.process(job.id());assertEquals("ready",service.publication(a,"a",job.id()).status());}
  var releases=service.releases(a,"a");assertEquals(5,releases.size());var first=releases.get(4);var before=service.get(a,"a");
  var restore=service.publish(a,"a",publication(before,"restore").put("restoreReleaseId",first.id()));worker.process(restore.id());
  var after=service.releases(a,"a");assertEquals(6,after.size());assertEquals(6,after.get(0).sequence());assertEquals(first.hash(),after.get(0).hash());
  assertEquals(before.draft(),service.get(a,"a").draft());assertNull(service.get(a,"a").activeReleaseId());assertEquals(first,after.get(5));
 }
 @Test void idempotencyRejectsChangedPayloadAndConcurrentPublication(){
  create(a,"a");var d=configure(a,"a","role");var request=publication(d,"same");var first=service.publish(a,"a",request);
  assertEquals(first.id(),service.publish(a,"a",request).id());
  assertEquals(409,assertThrows(FusionFault.class,()->service.publish(a,"a",request.deepCopy().put("note","different"))).status);
  assertEquals(409,assertThrows(FusionFault.class,()->service.publish(a,"a",publication(d,"second"))).status);
  worker.process(first.id());assertEquals(first.id(),service.publish(a,"a",request).id());assertEquals(1,service.releases(a,"a").size());
 }
 @Test void failedPreparationKeepsPriorPointerAndClearsPending(){
  create(a,"a");var d=configure(a,"a","role");var first=service.publish(a,"a",publication(d,"first"));worker.process(first.id());var pointer=service.get(a,"a").publishedReleaseId();
  doThrow(new FusionFault(503,"catalog unavailable")).when(compiler).compile(anyString(),anyString(),anyString(),anyString(),any());
  var failed=service.publish(a,"a",publication(d,"failed"));worker.process(failed.id());
  assertEquals("failed",service.publication(a,"a",failed.id()).status());assertEquals(pointer,service.get(a,"a").publishedReleaseId());assertNull(service.get(a,"a").pendingJobId());assertEquals(1,service.releases(a,"a").size());
 }
 @Test void expiredWorkerLeaseCanRecoverWithoutDuplicateRelease(){
  create(a,"a");var d=configure(a,"a","role");var job=service.publish(a,"a",publication(d,"restart"));
  db.update("update fusion_publication set status='validating',lease_token='lost-worker',lease_until=? where id=?",OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(60),job.id());
  worker.process(job.id());worker.process(job.id());assertEquals("ready",service.publication(a,"a",job.id()).status());assertEquals(1,service.releases(a,"a").size());assertEquals(1,db.queryForObject("select count(*) from fusion_outbox",Integer.class));
 }
 @Test void invalidDraftCannotPublishOrMixPromptSources(){
  create(a,"a");var d=service.get(a,"a");assertEquals(422,assertThrows(FusionFault.class,()->service.publish(a,"a",publication(d,"invalid"))).status);
  ObjectNode draft=(ObjectNode)d.draft().deepCopy();((ObjectNode)draft.path("role")).put("revisionId","untrusted");
  assertEquals(422,assertThrows(FusionFault.class,()->service.save(a,"a",body().put("expectedRevision",d.revision()).set("draft",draft))).status);
  assertTrue(service.releases(a,"a").isEmpty());
 }
 @Test void workflowDraftsRoundTripButGenerationRequiresCompleteEdgesAndRejectsStaleSaves(){
  create(a,"a");var d=configure(a,"a","role");ObjectNode draft=(ObjectNode)d.draft().deepCopy();
  ObjectNode task=(ObjectNode)draft.path("tasks").get(0);task.putArray("nodes").addObject().put("id","model").put("name","Model").put("kind","llm").put("instructions","");
  ObjectNode graph=task.putObject("workflow").put("version",1);graph.putArray("positions").addObject().put("id","model").put("x",123).put("y",456);
  ArrayNode edges=graph.putArray("edges");edges.addObject().put("source","__start__").put("target","model");
  var saved=service.save(a,"a",body().put("expectedRevision",d.revision()).set("draft",draft));
  assertEquals(123,saved.draft().at("/tasks/0/workflow/positions/0/x").asInt());
  assertEquals(422,assertThrows(FusionFault.class,()->service.generate(a,"a",body().put("expectedRevision",saved.revision()).set("draft",draft))).status);
  edges.addObject().put("source","model").put("target","__end__");
  var generated=service.generate(a,"a",body().put("expectedRevision",saved.revision()).set("draft",draft));
  assertEquals(saved.revision()+1,generated.revision());assertNull(generated.publishedReleaseId());assertTrue(service.releases(a,"a").isEmpty());
  assertEquals(409,assertThrows(FusionFault.class,()->service.generate(a,"a",body().put("expectedRevision",saved.revision()).set("draft",draft))).status);
  assertEquals(403,assertThrows(FusionFault.class,()->service.generate(b,"a",body().put("expectedRevision",generated.revision()).set("draft",draft))).status);
 }
}
