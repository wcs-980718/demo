package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.model.ModelService;
import com.yiwei.midplat.model.ModelKind;
import com.yiwei.midplat.platform.PlatformService;
import com.yiwei.midplat.prompt.PromptService;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4 中台侧多分配流程：预检不自动扩权、创建分配 PENDING→READY 且幂等、
 * 默认路由显式指向并影响旧协议绑定视图；W3 能力目录随主数据登记。
 */
@SpringBootTest @ActiveProfiles("test") @Transactional
class ProjectAgentBindingIntegrationTest {
 @Autowired ProjectAgentBindingService bindings;@Autowired FusionState state;@Autowired PlatformService projects;
 @Autowired ModelService models;@Autowired PromptService prompts;@Autowired FusionCatalog catalog;
 @Autowired ObjectMapper json;
 @MockBean FusionAgentClient agent;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Autowired jakarta.persistence.EntityManager entityManager;
 String project(){String id=projects.create(new PlatformService.CreatePlatformCmd("binding-test",null,null,null,null,null,null)).id();entityManager.flush();return id;}
 String llmModelRevision(){
  var model=models.create(new ModelService.CreateModelCmd("binding-model", ModelKind.llm,"model-x","http://127.0.0.1:9/v1","k-"+UUID.randomUUID(),null,null));
  return catalog.list("model").stream().filter(r->r.get("resourceId").equals(model.id())).findFirst().orElseThrow().get("id").toString();
 }
 JsonNode versionPayload(String modelRevision)throws Exception {
  return json.readTree("{\"defaultModelRevisionId\":\""+modelRevision+"\",\"temperature\":0.4,\"role\":{\"kind\":\"inline\",\"body\":\"shared-role\"},\"tasks\":[{\"key\":\"summary\",\"name\":\"摘要\",\"instructions\":\"\",\"nodes\":[]}]}");
 }
 void stubAgentVersions(String modelRevision)throws Exception {
  var versions=json.createArrayNode();
  versions.addObject().put("id","av-1").set("content",versionPayload(modelRevision));
  when(agent.callAgent(any(),eq("agentVersions"),any())).thenReturn(versions);
 }
 void stubCreateForBinding()throws Exception {
  when(agent.call(any(),anyString(),anyString(),anyString(),eq("createForBinding"),any())).thenReturn(json.readTree("{\"id\":\"deployed\"}"));
 }
 @Test void preflightAndBindingUseResourcesWithoutWritingGrants()throws Exception {
  String p=project();String revision=llmModelRevision();stubAgentVersions(revision);stubCreateForBinding();
  var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  var result=bindings.preflight(who,p,"development","quality-agent","av-1");
  assertEquals(true,result.get("ready"));assertTrue(((Collection<?>)result.get("missing")).isEmpty());
  bindings.create(who,p,"development","quality-agent","av-1","default","no-grant-binding","agent");
  assertEquals(0,jdbc.queryForObject("select count(*) from midplat_model_grant where project_id=?",Integer.class,p));
  assertEquals(0,jdbc.queryForObject("select count(*) from midplat_capability_grant where project_id=?",Integer.class,p));
 }
 @Test void preflightAndCreateRejectIncompleteConfiguration()throws Exception {
  String p=project();stubAgentVersions("");var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  assertEquals(false,bindings.preflight(who,p,"development","quality-agent","av-1").get("ready"));
  assertThrows(IllegalArgumentException.class,()->bindings.create(who,p,"development","quality-agent","av-1","default","invalid-binding","agent"));
  assertTrue(bindings.list(p,"development").isEmpty());
 }
 @Test void preflightResolvesTemplateRevisionRatherThanResourceId()throws Exception {
  String p=project();String revision=llmModelRevision();
  var prompt=prompts.create(new PromptService.CreatePromptCmd("rules","agent-role","v1","required rules"));
  String pr=catalog.list("prompt").stream().filter(r->r.get("resourceId").equals(prompt.id())).findFirst().orElseThrow().get("id").toString();
  var content=(com.fasterxml.jackson.databind.node.ObjectNode)versionPayload(revision);
  content.set("role",json.createObjectNode().put("kind","template").put("revisionId",pr));
  var versions=json.createArrayNode();versions.addObject().put("id","av-1").set("content",content);
  when(agent.callAgent(any(),eq("agentVersions"),any())).thenReturn(versions);
  var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  assertEquals(true,bindings.preflight(who,p,"development","quality-agent","av-1").get("ready"));
  ((com.fasterxml.jackson.databind.node.ObjectNode)content.path("role")).put("revisionId",prompt.id());
  assertEquals(false,bindings.preflight(who,p,"development","quality-agent","av-1").get("ready"));
 }
 @Test void createBindingIsIdempotentAndBecomesTheLegacyDefaultRoute()throws Exception {
  String p=project();stubAgentVersions(llmModelRevision());stubCreateForBinding();
  var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  var first=bindings.create(who,p,"development","quality-agent","av-1","review","op-1","质检分配");
  assertEquals("ready",first.get("status"));
  assertEquals(true,first.get("is_default"));
  String deployment=(String)first.get("deployment_id");
  // 幂等重放：相同请求返回同一分配
  var replay=bindings.create(who,p,"development","quality-agent","av-1","review","op-1","质检分配");
  assertEquals(first.get("binding_id"),replay.get("binding_id"));
  assertEquals(1,bindings.list(p,"development").size());
  // 不同内容同幂等键 → 409
  assertThrows(ConflictException.class,()->bindings.create(who,p,"development","quality-agent","av-1","other","op-1","X"));
  // 旧协议绑定视图解析到该默认分配
  var legacy=state.binding(p,"development");
  assertNotNull(legacy);
  assertEquals(deployment,legacy.get("deployment_id"));
  assertEquals("ready",legacy.get("status"));
  // 第二个分配（不同别名）不抢占默认路由
  var second=bindings.create(who,p,"development","quality-agent","av-1","reviewer","op-2","第二分配");
  assertNotEquals(first.get("binding_id"),second.get("binding_id"));
  assertEquals(deployment,state.binding(p,"development").get("deployment_id"));
  var both=bindings.list(p,"development");
  assertEquals(2,both.size());
  assertEquals(true,both.stream().filter(row->row.get("binding_id").equals(first.get("binding_id"))).findFirst().orElseThrow().get("is_default"));
  assertEquals(false,both.stream().filter(row->row.get("binding_id").equals(second.get("binding_id"))).findFirst().orElseThrow().get("is_default"));
  // 显式切换默认路由
  bindings.setDefault(p,"development",(String)second.get("binding_id"),"legacy-agent-runs");
  assertEquals(second.get("deployment_id"),state.binding(p,"development").get("deployment_id"));
  var switched=bindings.list(p,"development");
  assertEquals(false,switched.stream().filter(row->row.get("binding_id").equals(first.get("binding_id"))).findFirst().orElseThrow().get("is_default"));
  assertEquals(true,switched.stream().filter(row->row.get("binding_id").equals(second.get("binding_id"))).findFirst().orElseThrow().get("is_default"));
  // 别名唯一
  assertThrows(ConflictException.class,()->bindings.create(who,p,"development","quality-agent","av-1","review","op-3","重复别名"));
 }
 @Test void bindingIdempotencyKeyIsScopedToItsProject()throws Exception {
  String p1=project();String p2=project();stubAgentVersions(llmModelRevision());stubCreateForBinding();
  var who=new FusionAccess.Identity("admin",Set.of(p1,p2),true,true);
  var first=bindings.create(who,p1,"development","quality-agent","av-1","review","shared-op","质检分配");
  assertEquals("ready",first.get("status"));
  // 同一幂等键在另一个项目重放：不读取第一个项目的分配记录，也不会误写其 last_error。
  assertThrows(ConflictException.class,()->bindings.create(who,p2,"development","quality-agent","av-1","review","shared-op","质检分配"));
  var unchanged=(Map<String,Object>)bindings.list(p1,"development").get(0);
  assertEquals(null,unchanged.get("last_error"));
  assertEquals(first.get("binding_id"),unchanged.get("binding_id"));
 }
 @Test void unbindFreesProjectAndRebindRestoresSameDeployment()throws Exception {
  String p=project();stubAgentVersions(llmModelRevision());stubCreateForBinding();
  var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  var first=bindings.create(who,p,"development","quality-agent","av-1","review","op-u1","质检分配");
  String bindingId=(String)first.get("binding_id");String deployment=(String)first.get("deployment_id");
  // 解绑：分配转 UNBOUND，默认路由清除，旧协议绑定视图释放
  bindings.unbind(p,"development",bindingId);
  assertNull(state.binding(p,"development"));
  var listed=bindings.list(p,"development");
  assertEquals(1,listed.size());
  assertEquals("unbound",listed.get(0).get("status"));
  assertEquals(false,listed.get(0).get("is_default"));
  // 解绑后 alias 释放，可为其他分配复用；UNBOUND 行不再被设为默认
  assertThrows(ConflictException.class,()->bindings.setDefault(p,"development",bindingId,"legacy-agent-runs"));
  // 重新绑定同智能体同版本：复活原分配与原部署，数据完整保留
  var restored=bindings.create(who,p,"development","quality-agent","av-1","review","op-u2","质检分配");
  assertEquals(bindingId,restored.get("binding_id"));
  assertEquals(deployment,restored.get("deployment_id"));
  assertEquals("ready",restored.get("status"));
  assertEquals(true,restored.get("is_default"));
  assertEquals(deployment,state.binding(p,"development").get("deployment_id"));
  // 再次解绑后再绑定不同版本：创建新分配而非复活
  bindings.unbind(p,"development",bindingId);
  var versions=json.createArrayNode();
  versions.addObject().put("id","av-2").set("content",versionPayload(llmModelRevision()));
  when(agent.callAgent(any(),eq("agentVersions"),any())).thenReturn(versions);
  var other=bindings.create(who,p,"development","quality-agent","av-2","review","op-u3","第二版");
  assertNotEquals(bindingId,other.get("binding_id"));
  assertNotEquals(deployment,other.get("deployment_id"));
  // 未知分配解绑 → 404
  assertThrows(ResourceNotFoundException.class,()->bindings.unbind(p,"development","bnd-missing"));
 }
 @Test void deleteAgentRequiresAllBindingsUnboundThenRemovesMidplatRows()throws Exception {
  String p=project();stubAgentVersions(llmModelRevision());stubCreateForBinding();
  var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  var first=bindings.create(who,p,"development","quality-agent","av-1","review","op-d1","质检分配");
  String bindingId=(String)first.get("binding_id");String deployment=(String)first.get("deployment_id");
  // 生效绑定存在 → 409，不触发智能体服务删除；报错需列出项目/环境便于定位（含界面未选中的环境）
  var conflict=assertThrows(ConflictException.class,()->bindings.deleteAgent(who,"quality-agent"));
  assertTrue(conflict.getMessage().contains(p));
  assertTrue(conflict.getMessage().contains("development"));
  // 解绑后删除：智能体服务收到完整 binding/deployment 清单，中台分配与默认路由行清除
  bindings.unbind(p,"development",bindingId);
  when(agent.callAgent(any(),eq("deleteAgent"),any())).thenReturn(json.readTree("{\"deleted\":\"quality-agent\",\"deployments\":1}"));
  bindings.deleteAgent(who,"quality-agent");
  verify(agent).callAgent(any(),eq("deleteAgent"),argThat(payload->{
   @SuppressWarnings("unchecked") var n=(Map<String,Object>)payload;
   return "quality-agent".equals(n.get("agentId"))
    && n.get("bindingIds").toString().contains(bindingId)
    && n.get("deploymentIds").toString().contains(deployment);
  }));
  assertEquals(0,jdbc.queryForObject("select count(*) from midplat_project_agent_binding where agent_id='quality-agent'",Integer.class));
  assertEquals(0,jdbc.queryForObject("select count(*) from midplat_project_runtime_default where binding_id=?",Integer.class,bindingId));
 }
 @Test void failedCreateIsUnboundAndDoesNotBlockDelete()throws Exception {
  String p=project();stubAgentVersions(llmModelRevision());
  var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  // 远端建部署失败：分配按未生效处理转 UNBOUND、释放别名、保留原因，不残留永久 PENDING
  when(agent.call(any(),anyString(),anyString(),anyString(),eq("createForBinding"),any())).thenThrow(new RuntimeException("远端不可用"));
  assertThrows(RuntimeException.class,()->bindings.create(who,p,"development","quality-agent","av-1","review","op-f1","质检分配"));
  var listed=bindings.list(p,"development");
  assertEquals(1,listed.size());
  assertEquals("unbound",listed.get(0).get("status"));
  assertEquals("远端不可用",listed.get(0).get("last_error"));
  String bindingId=(String)listed.get(0).get("binding_id");
  // 失败分配不阻断删除，且仍会随删除清单交给智能体侧清理
  when(agent.callAgent(any(),eq("deleteAgent"),any())).thenReturn(json.readTree("{\"deleted\":\"quality-agent\",\"deployments\":0}"));
  bindings.deleteAgent(who,"quality-agent");
  verify(agent).callAgent(any(),eq("deleteAgent"),argThat(payload->{
   @SuppressWarnings("unchecked") var n=(Map<String,Object>)payload;
   return n.get("bindingIds").toString().contains(bindingId);
  }));
  assertEquals(0,jdbc.queryForObject("select count(*) from midplat_project_agent_binding where agent_id='quality-agent'",Integer.class));
 }
 @Test void failedCreateRebindRevivesSameAllocation()throws Exception {
  String p=project();stubAgentVersions(llmModelRevision());
  var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  when(agent.call(any(),anyString(),anyString(),anyString(),eq("createForBinding"),any())).thenThrow(new RuntimeException("远端不可用"));
  assertThrows(RuntimeException.class,()->bindings.create(who,p,"development","quality-agent","av-1","review","op-f1","质检分配"));
  String bindingId=(String)bindings.list(p,"development").get(0).get("binding_id");
  // 同版本重绑复活原分配；别名已释放不冲突
  stubCreateForBinding();
  var retried=bindings.create(who,p,"development","quality-agent","av-1","review","op-f2","质检分配");
  assertEquals(bindingId,retried.get("binding_id"));
  assertEquals("ready",retried.get("status"));
 }
 @Test void bindingsWithoutEnvironmentCoverEveryEnvironment()throws Exception {
  String p=project();stubAgentVersions(llmModelRevision());stubCreateForBinding();
  var who=new FusionAccess.Identity("admin",Set.of(p),true,true);
  bindings.create(who,p,"development","quality-agent","av-1","review","op-a1","质检分配");
  // 非界面可选环境的分配（历史 QA 数据等）也必须出现在全量列表与删除校验口径中
  bindings.create(who,p,"qa-custom-env","quality-agent","av-1","review2","op-a2","质检分配");
  assertEquals(1,state.bindings("development").size());
  assertEquals(2,state.bindings().size());
 }
 @Test void legacyBeginFlowStillWorksAndConflictsAcrossDefinitions(){
  String p=project();
  var binding=state.begin(p,"development","general-assistant-v1","质检助手",UUID.randomUUID().toString());
  String deployment=(String)binding.get("deployment_id");
  state.complete(p,"development",deployment);
  assertEquals(deployment,state.binding(p,"development").get("deployment_id"));
  assertThrows(ConflictException.class,()->state.begin(p,"development","general-assistant-v2","质检助手",UUID.randomUUID().toString()));
  assertThrows(ConflictException.class,()->state.begin(p,"development","general-assistant-v1","改名",UUID.randomUUID().toString()));
 }
 @Test void capabilityCatalogRegistersModelsAndPrompts(){
  var model=models.create(new ModelService.CreateModelCmd("cap-model", ModelKind.llm,"model-cap","http://127.0.0.1:9/v1","k-"+UUID.randomUUID(),null,null));
  var prompt=prompts.create(new PromptService.CreatePromptCmd("示例角色","agent-role","v1","正文"));
  // 目录头由主数据服务创建时登记（存量数据走 V36 迁移回填）
  assertEquals(1,jdbc.queryForObject("select count(*) from midplat_capability where source_type='model' and source_id=?",Integer.class,model.id()));
  assertEquals(1,jdbc.queryForObject("select count(*) from midplat_capability where source_type='prompt' and source_id=?",Integer.class,prompt.id()));
 }
}
