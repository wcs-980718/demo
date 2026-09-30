package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W4 独立智能体与多分配、W3 资产写退役。
 * 覆盖：无项目创建智能体、不可变版本与发布幂等、按 bindingId 多部署复用完整蓝图、
 * 智能体动作禁止携带项目上下文、资产主数据写入仅迁移身份可用。
 */
@org.springframework.test.context.ActiveProfiles("fusion")
@SpringBootTest(classes=FusionApplication.class,properties={
 "fusion.runtime-directory=${java.io.tmpdir}/midplat-fusion-agent-tests",
 "spring.datasource.url=jdbc:h2:mem:fusion_agents;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.flyway.enabled=true","spring.flyway.locations=classpath:fusion/db/migration",
 "fusion.service-token=0123456789012345678901234567890123456789","fusion.midplat-url=http://127.0.0.1:9","fusion.publication-poll-ms=3600000"})
@AutoConfigureMockMvc
class AgentIndependentTest {
 @Autowired AgentService agents;@Autowired DeploymentService deployments;@Autowired AssetService assets;
 @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
 @MockBean EffectiveConfigCompiler compiler;
 Context alice=new Context("alice","project-a","development",true);
 Context projectA=new Context("service:project-a","project-a","development",true);
 Context projectB=new Context("service:project-b","project-b","development",true);
 Context migrator=new Context("migration-service","project-a","development",true,"",true);
 @BeforeEach void setup(){
  db.update("update fusion_deployment set published_release_id=null,active_release_id=null,pending_job_id=null");
  for(String table:List.of("fusion_agent_publication","fusion_agent_audit","fusion_agent_version","fusion_agent"))db.update("delete from "+table);
  for(String table:List.of("fusion_audit","fusion_outbox","fusion_release","fusion_publication","fusion_deployment","fusion_definition","fusion_asset_revision","fusion_asset"))db.update("delete from "+table);
 }
 ObjectNode body(){return json.createObjectNode();}
 /** 创建并配置一个智能体草稿，返回其 draftRevision。 */
 long createAgent(String id){
  agents.create(alice,body().put("idempotencyKey",id).put("name","质量审查智能体").put("taskKey","summary").put("taskName","结果摘要"));
  var draft=agents.draft(id);
  ObjectNode configured=(ObjectNode)((JsonNode)draft.get("draft")).deepCopy();
  configured.put("defaultModelRevisionId","model-revision");
  configured.set("role",body().put("kind","inline").put("body","shared-blueprint-role"));
  Map<?,?> saved=(Map<?,?>)agents.saveDraft(alice,id,body().put("expectedRevision",((Number)draft.get("expectedRevision")).longValue()).set("draft",configured));
  return ((Number)saved.get("expectedRevision")).longValue();
 }
 @SuppressWarnings("unchecked")
 String publishVersion(String id,long expectedRevision,String key){
  Map<String,Object> result=(Map<String,Object>)agents.publish(alice,id,body().put("expectedRevision",expectedRevision).put("note","v").put("idempotencyKey",key));
  return (String)((Map<String,Object>)result.get("publication")).get("versionId");
 }
 @Test void agentsExistWithoutProjectAndVersionsAreImmutable(){
  long revision=createAgent("quality-agent");
  var view=agents.draft("quality-agent");
  assertNull(view.get("sourceProjectId"),"独立智能体不携带业务项目");
  String v1=publishVersion("quality-agent",revision,"pub-1");
  var versions=agents.versions("quality-agent");
  assertEquals(1,versions.size());
  assertEquals(v1,versions.get(0).get("id"));
  JsonNode content=(JsonNode)versions.get(0).get("content");
  assertEquals("shared-blueprint-role",content.path("role").path("body").asText());
  // 同一幂等键重放返回同一版本，不同内容 409。
  Map<?,?> replay=(Map<?,?>)agents.publish(alice,"quality-agent",body().put("expectedRevision",revision).put("note","v").put("idempotencyKey","pub-1"));
  assertEquals(v1,((Map<?,?>)replay.get("publication")).get("versionId"));
  assertEquals(409,assertThrows(FusionFault.class,()->agents.publish(alice,"quality-agent",body().put("expectedRevision",revision).put("note","different").put("idempotencyKey","pub-1"))).status);
  // 旧草稿继续编辑不影响已发布版本；再次发布 sequence 递增且 v1 内容不变。
  var draft=agents.draft("quality-agent");
  ObjectNode configured=(ObjectNode)((JsonNode)draft.get("draft")).deepCopy();
  ((ObjectNode)configured.path("role")).put("body","evolved-role");
  Map<?,?> saved=(Map<?,?>)agents.saveDraft(alice,"quality-agent",body().put("expectedRevision",((Number)draft.get("expectedRevision")).longValue()).set("draft",configured));
  agents.publish(alice,"quality-agent",body().put("expectedRevision",((Number)saved.get("expectedRevision")).longValue()).put("note","v2").put("idempotencyKey","pub-2"));
  var after=agents.versions("quality-agent");
  assertEquals(2,after.size());
  assertEquals("shared-blueprint-role",((JsonNode)after.get(1).get("content")).path("role").path("body").asText());
  assertEquals("evolved-role",((JsonNode)after.get(0).get("content")).path("role").path("body").asText());
 }
 @Test void bindingDeploymentsReuseFullBlueprintAndAllowMultiplePerProject(){
  long revision=createAgent("quality-agent");
  String versionId=publishVersion("quality-agent",revision,"pub-1");
  Deployment depA=deployments.createForBinding(projectA,"dep-a",body().put("bindingId","bnd-a").put("agentId","quality-agent").put("agentVersionId",versionId).put("name","A 项目分配"),agents);
  Deployment depB=deployments.createForBinding(projectB,"dep-b",body().put("bindingId","bnd-b").put("agentId","quality-agent").put("agentVersionId",versionId).put("name","B 项目分配"),agents);
  // F02：分配部署复用完整蓝图（角色/模型），不再重置为空骨架。
  assertEquals("shared-blueprint-role",depA.draft().path("role").path("body").asText());
  assertEquals("shared-blueprint-role",depB.draft().path("role").path("body").asText());
  assertEquals("model-revision",depA.draft().path("defaultModelRevisionId").asText());
  // F01：同一项目可以有多个部署（不同 binding）。
  Deployment depA2=deployments.createForBinding(projectA,"dep-a2",body().put("bindingId","bnd-a2").put("agentId","quality-agent").put("agentVersionId",versionId).put("name","A 项目第二分配"),agents);
  assertNotEquals(depA.id(),depA2.id());
  // bindingId 幂等：重复到达返回同一部署，不建第二份。
  Deployment replay=deployments.createForBinding(projectA,"dep-a",body().put("bindingId","bnd-a").put("agentId","quality-agent").put("agentVersionId",versionId).put("name","A 项目分配"),agents);
  assertEquals(depA.id(),replay.id());
  assertEquals(1,db.queryForObject("select count(*) from fusion_deployment where binding_id='bnd-a'",Integer.class));
  assertEquals(409,assertThrows(FusionFault.class,()->deployments.createForBinding(projectA,"dep-other",body().put("bindingId","bnd-a").put("agentId","quality-agent").put("agentVersionId",versionId).put("name","X"),agents)).status);
 }
 @Test void agentUpdateRenamesAndMirrorsDefinition(){
  createAgent("quality-agent");
  Map<?,?> updated=(Map<?,?>)agents.update(alice,"quality-agent",body().put("name","改名后的智能体").put("description","新描述"));
  assertEquals("改名后的智能体",updated.get("name"));
  assertEquals("新描述",updated.get("description"));
  assertEquals("改名后的智能体",db.queryForObject("select name from fusion_definition where id='quality-agent'",String.class));
  assertEquals(422,assertThrows(FusionFault.class,()->agents.update(alice,"quality-agent",body().put("name","  "))).status);
  assertEquals(422,assertThrows(FusionFault.class,()->agents.update(alice,"quality-agent",body().put("name","x").put("description","d".repeat(501)))).status);
  assertEquals(404,assertThrows(FusionFault.class,()->agents.update(alice,"ghost-agent",body().put("name","x"))).status);
 }
 @Test void deleteAgentRemovesVersionsDeploymentsAndHistory(){
  long revision=createAgent("quality-agent");
  String versionId=publishVersion("quality-agent",revision,"pub-1");
  deployments.createForBinding(projectA,"dep-a",body().put("bindingId","bnd-a").put("agentId","quality-agent").put("agentVersionId",versionId).put("name","A 项目分配"),agents);
  // binding_id 不在传入集合 → 拒绝，防止误删他人生效部署
  FusionFault mismatch=assertThrows(FusionFault.class,()->agents.delete(alice,"quality-agent",body().set("deploymentIds",json.createArrayNode().add("dep-a"))));
  assertEquals(409,mismatch.status);
  assertEquals(1,db.queryForObject("select count(*) from fusion_deployment where id='dep-a'",Integer.class));
  // 正常删除：部署全套数据 + 智能体四表 + 定义镜像全部清除
  var cmd=body();
  cmd.set("bindingIds",json.createArrayNode().add("bnd-a"));cmd.set("deploymentIds",json.createArrayNode().add("dep-a"));
  agents.delete(alice,"quality-agent",cmd);
  for(String table:List.of("fusion_agent","fusion_agent_version","fusion_agent_publication","fusion_agent_audit","fusion_deployment","fusion_audit"))
   assertEquals(0,db.queryForObject("select count(*) from "+table,Integer.class),table);
  assertEquals(0,db.queryForObject("select count(*) from fusion_definition where id='quality-agent'",Integer.class));
  assertEquals(404,assertThrows(FusionFault.class,()->agents.delete(alice,"quality-agent",body())).status);
 }
 @Test void agentScopedCommandsRejectProjectContextAndDeploymentCommandsRejectBlankProject()throws Exception{
  String token="0123456789012345678901234567890123456789";
  Command withProject=new Command(new Context("alice","project-a","development",true),"agents",null,body());
  mvc.perform(post("/internal/fusion/command").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(withProject))).andExpect(status().isForbidden());
  Command blankProject=new Command(new Context("alice","","development",true),"create",null,body());
  mvc.perform(post("/internal/fusion/command").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(blankProject))).andExpect(status().isForbidden());
  Command fine=new Command(new Context("alice","","development",true),"agents",null,body());
  mvc.perform(post("/internal/fusion/command").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(fine))).andExpect(status().isOk());
 }
 @Test void assetWritesRetiredForNormalIdentitiesAndKeptForMigrationService(){
  // W3：普通管理身份的资产主数据写入被服务端拒绝（不仅是前端隐藏按钮）。
  FusionFault saveRejected=assertThrows(FusionFault.class,()->assets.save(alice,body().put("id","skill").put("name","Skill").put("kind","skill").put("expectedRevision",0).set("content",body().put("body","instructions"))));
  assertEquals(403,saveRejected.status);
  FusionFault enableRejected=assertThrows(FusionFault.class,()->assets.enabled(alice,"skill",true));
  assertEquals(403,enableRejected.status);
  // 受控迁移服务身份保留写通道，写入后普通只读解析可用。
  assets.save(migrator,body().put("id","skill").put("name","Skill").put("kind","skill").put("expectedRevision",0).set("content",body().put("body","migrated instructions")));
  var revision=assets.resolve("project-a","development",List.of(db.queryForObject("select current_revision_id from fusion_asset where id='skill'",String.class)));
  assertTrue(revision.size()==1);
  assets.enabled(migrator,"skill",false);
  FusionFault disabled=assertThrows(FusionFault.class,()->assets.resolve("project-a","development",List.of(db.queryForObject("select current_revision_id from fusion_asset where id='skill'",String.class))));
  assertEquals(403,disabled.status);
 }
 @Test void savedConfigurationRequiresDefaultModelEvenWhenTaskOverridesAndRequiresRules(){
  agents.create(alice,body().put("idempotencyKey","required-fields").put("name","required").put("taskKey","main").put("taskName","task"));
  var view=agents.draft("required-fields");ObjectNode draft=((JsonNode)view.get("draft")).deepCopy();
  draft.put("defaultModelRevisionId","");draft.set("role",body().put("kind","inline").put("body","rules"));
  ((ObjectNode)draft.path("tasks").get(0)).put("modelRevisionId","task-model");
  ObjectNode payload=body().put("expectedRevision",0);payload.set("draft",draft);
  assertEquals(422,assertThrows(FusionFault.class,()->agents.saveDraft(alice,"required-fields",payload)).status);
  assertEquals(422,assertThrows(FusionFault.class,()->deployments.validateDraft(draft,true)).status);
  draft.put("defaultModelRevisionId","default-model");((ObjectNode)draft.path("role")).put("body","  ");
  assertEquals(422,assertThrows(FusionFault.class,()->agents.saveDraft(alice,"required-fields",payload)).status);
  draft.set("role",body().put("kind","template").put("revisionId",""));
  assertEquals(422,assertThrows(FusionFault.class,()->agents.saveDraft(alice,"required-fields",payload)).status);
  draft.set("role",body().put("kind","inline").put("body","required rules"));
  assertDoesNotThrow(()->agents.saveDraft(alice,"required-fields",payload));
 }
 @Test void publicationDraftReportsAgentScopedIdempotencyHint(){
  long revision=createAgent("idempotency-a");createAgent("idempotency-b");
  assertEquals(false,agents.draft("idempotency-a","published-key").get("publicationExists"));
  publishVersion("idempotency-a",revision,"published-key");
  assertEquals(true,agents.draft("idempotency-a","published-key").get("publicationExists"));
  assertEquals(false,agents.draft("idempotency-b","published-key").get("publicationExists"));
  assertEquals(false,agents.draft("idempotency-a","new-key").get("publicationExists"));
 }
}
