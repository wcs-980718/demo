package com.yiwei.midplat.fusion;

import com.yiwei.midplat.model.*;
import com.yiwei.midplat.prompt.*;
import com.yiwei.midplat.platform.*;
import com.yiwei.midplat.common.api.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class FusionCatalogIntegrationTest {
 @Autowired jakarta.persistence.EntityManager entityManager;
 @Autowired org.springframework.jdbc.core.JdbcTemplate db;
 @Autowired ModelService models;@Autowired PromptService prompts;@Autowired PlatformService projects;
 @Autowired FusionCatalog catalog;@Autowired FusionState state;@Autowired ObjectMapper json;
 String project(){String id=projects.create(new PlatformService.CreatePlatformCmd("fusion-test",null,null,null,null,null,null)).id();entityManager.flush();return id;}
 ModelService.ModelView model(){return models.create(new ModelService.CreateModelCmd("fusion-model",ModelKind.llm,"model-a","http://127.0.0.1:9/v1","never-export-this-key",null,null));}
 Map<String,Object> revision(String type,String id){return catalog.list(type).stream().filter(r->r.get("resourceId").equals(id)).findFirst().orElseThrow();}
 @Test void catalogRevisionsRemainImmutableAndNeverContainCredentials(){
  var model=model();var first=revision("model",model.id());
  models.update(model.id(),new ModelService.CreateModelCmd("fusion-model-updated",ModelKind.llm,"model-b","http://127.0.0.1:10/v1","rotated-key",null,null));
  var second=revision("model",model.id());assertNotEquals(first.get("id"),second.get("id"));assertEquals(first,catalog.require((String)first.get("id"),"model"));
  assertFalse(catalog.list("model").toString().contains("never-export-this-key"));assertFalse(catalog.list("model").toString().contains("rotated-key"));
  var prompt=prompts.create(new PromptService.CreatePromptCmd("role","agent-role","v1","old-role"));var old=revision("prompt",prompt.id());
  prompts.update(prompt.id(),new PromptService.UpdatePromptCmd("role","v2","new-role"));
  assertEquals("old-role",((JsonNode)catalog.require((String)old.get("id"),"prompt").get("content")).path("body").asText());
  assertEquals(2,catalog.list("prompt").stream().filter(r->r.get("resourceId").equals(prompt.id())).count());
 }
 @Test void directCatalogResolutionNeedsNoGrantsButPreservesBindingScopeAndReferences(){
  String p=project();var model=model();var prompt=prompts.create(new PromptService.CreatePromptCmd("role","agent-role","v1","trusted-role"));
  var binding=state.begin(p,"development","general-assistant-v1","test",UUID.randomUUID().toString());String deployment=(String)binding.get("deployment_id");state.complete(p,"development",deployment);
  String mr=(String)revision("model",model.id()).get("id"),pr=(String)revision("prompt",prompt.id()).get("id");
  assertEquals(0,db.queryForObject("select count(*) from midplat_model_grant where project_id=?",Integer.class,p));
  assertEquals(1,((Map<?,?>)state.resolve(p,"development",deployment,List.of(mr),List.of(pr)).get("models")).size());
  db.update("insert into midplat_model_grant(project_id,environment,model_id,enabled) values(?,?,?,false)",p,"development",model.id());
  assertDoesNotThrow(()->state.resolve(p,"development",deployment,List.of(mr),List.of(pr)));
  assertThrows(ConflictException.class,()->models.delete(model.id()));assertThrows(ConflictException.class,()->prompts.delete(prompt.id()));
  assertThrows(ForbiddenException.class,()->state.resolve(p,"staging",deployment,List.of(mr),List.of()));
  db.update("insert into midplat_model_availability(model_id,enabled) values(?,false)",model.id());
  assertThrows(ForbiddenException.class,()->state.resolve(p,"development",deployment,List.of(mr),List.of(pr)));
 }
 @Test void bindingIsIdempotentAndLegacyConfigWritesAreRejected(){
  String p=project();String operation=UUID.randomUUID().toString();var first=state.begin(p,"development","general-assistant-v1","test",operation);
  assertEquals(first.get("deployment_id"),state.begin(p,"development","general-assistant-v1","test",operation).get("deployment_id"));
  assertThrows(ConflictException.class,()->state.begin(p,"development","other","changed",operation));
  assertThrows(ConflictException.class,()->projects.update(p,new PlatformService.UpdatePlatformCmd("fusion-test",null,null,"different-model",null,null,null)));
 }
 @Test void projectSavePreservesLegacyFieldsWithoutWritingResourceGrants(){
  var llm=model();var next=model();
  var emb=models.create(new ModelService.CreateModelCmd("emb",ModelKind.embedding,"e","http://127.0.0.1:9/v1","k",null,null));
  var rr=models.create(new ModelService.CreateModelCmd("rr",ModelKind.rerank,"r","http://127.0.0.1:9/v1","k",null,null));
  var prompt=prompts.create(new PromptService.CreatePromptCmd("role","agent-role","v1","rules"));
  String p=projects.create(new PlatformService.CreatePlatformCmd("no-grants",null,null,llm.id(),emb.id(),rr.id(),prompt.id())).id();
  entityManager.flush();
  projects.update(p,new PlatformService.UpdatePlatformCmd("updated",null,null,next.id(),emb.id(),rr.id(),prompt.id()));entityManager.flush();
  assertEquals(next.id(),projects.get(p).llmModelId());assertEquals(prompt.id(),projects.get(p).promptId());
  assertEquals(0,db.queryForObject("select count(*) from midplat_model_grant where project_id=?",Integer.class,p));
  assertEquals(0,db.queryForObject("select count(*) from midplat_capability_grant where project_id=?",Integer.class,p));
  assertTrue(state.models(false).stream().anyMatch(row->emb.id().equals(row.get("resourceId"))));
  assertTrue(state.models(true).stream().noneMatch(row->emb.id().equals(row.get("resourceId"))));
 }
 @Test void configurationRequiresDefaultModelAndRulesAndAcceptsFixedTemplateRevision()throws Exception{
  var model=model();String mr=(String)revision("model",model.id()).get("id");
  var draft=json.createObjectNode().put("defaultModelRevisionId",mr);
  draft.set("role",json.createObjectNode().put("kind","inline").put("body","rules"));
  draft.putArray("tasks").addObject().put("key","main").put("name","task").put("modelRevisionId",mr).putArray("nodes");
  assertDoesNotThrow(()->state.validateAgentConfiguration(draft));
  draft.put("defaultModelRevisionId","");assertTrue(state.configurationIssues(draft).contains("请选择默认模型"));draft.put("defaultModelRevisionId",mr);
  ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("role")).put("body","  ");assertTrue(state.configurationIssues(draft).contains("请填写规则提示词"));
  var prompt=prompts.create(new PromptService.CreatePromptCmd("role","agent-role","v1","fixed rules"));
  String pr=(String)revision("prompt",prompt.id()).get("id");assertNotEquals(prompt.id(),pr);
  draft.set("role",json.createObjectNode().put("kind","template").put("revisionId",pr));assertDoesNotThrow(()->state.validateAgentConfiguration(draft));
  ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("role")).put("revisionId",prompt.id());assertThrows(IllegalArgumentException.class,()->state.validateAgentConfiguration(draft));
  ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("role")).put("revisionId",pr);
  prompts.delete(prompt.id());entityManager.flush();assertThrows(IllegalArgumentException.class,()->state.validateAgentConfiguration(draft));
 }
 @Test void disabledAndNonLlmModelsAreNotUsableAsDefaults(){
  var model=model();String mr=(String)revision("model",model.id()).get("id");
  db.update("insert into midplat_model_availability(model_id,enabled) values(?,false)",model.id());
  assertTrue(state.models(true).stream().noneMatch(row->model.id().equals(row.get("resourceId"))));
  assertThrows(ForbiddenException.class,()->state.requireModelRevision(mr));
  var emb=models.create(new ModelService.CreateModelCmd("emb",ModelKind.embedding,"e","http://127.0.0.1:9/v1","k",null,null));
  String er=(String)revision("model",emb.id()).get("id");assertThrows(IllegalArgumentException.class,()->state.requireModelRevision(er));
 }
}
