package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.*;
import com.yiwei.midplat.common.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class FusionState {
 private final JdbcTemplate db;private final FusionCatalog catalog;private final ObjectMapper json;
 public FusionState(JdbcTemplate db,FusionCatalog catalog,ObjectMapper json){this.db=db;this.catalog=catalog;this.json=json;}
 private static final String BINDING_COLUMNS="b.project_id,b.environment,b.deployment_id,b.agent_id as definition_id,b.source_definition_id,b.source_name,b.alias as name,lower(b.status) as status,b.operation_id";
 private static final String DEFAULT_JOIN=" left join midplat_project_runtime_default d on d.binding_id=b.id and d.project_id=b.project_id and d.environment=b.environment and d.route_kind='legacy-agent-runs'";
 private Map<String,Object> defaultFlag(Map<String,Object> row){row.put("is_default",row.remove("default_binding_id")!=null);return row;}
 private List<Map<String,Object>> defaultFlags(List<Map<String,Object>> rows){rows.forEach(this::defaultFlag);return rows;}
 /** W4：旧“项目唯一绑定”视图解析为默认分配；无默认路由时回退第一个就绪分配，再回退第一个分配。 */
 public Map<String,Object> binding(String project,String env){
  var byDefault=db.queryForList("select "+BINDING_COLUMNS+" from midplat_project_agent_binding b join midplat_project_runtime_default d on d.binding_id=b.id where b.project_id=? and b.environment=? and d.route_kind='legacy-agent-runs'",project,env);
  if(!byDefault.isEmpty())return byDefault.get(0);
  var ready=db.queryForList("select "+BINDING_COLUMNS+" from midplat_project_agent_binding b where b.project_id=? and b.environment=? and b.status='READY' order by b.created_at",project,env);
  if(!ready.isEmpty())return ready.get(0);
  var any=db.queryForList("select "+BINDING_COLUMNS+" from midplat_project_agent_binding b where b.project_id=? and b.environment=? and b.status<>'UNBOUND' order by b.created_at",project,env);
  return any.isEmpty()?null:any.get(0);
 }
 public Map<String,Object> bindingById(String bindingId){
  var rows=db.queryForList("select b.id as binding_id,"+BINDING_COLUMNS+",b.generation,b.desired_agent_version_id,d.binding_id as default_binding_id from midplat_project_agent_binding b"+DEFAULT_JOIN+" where b.id=?",bindingId);
  return rows.isEmpty()?null:defaultFlag(rows.get(0));
 }
 @Transactional public Map<String,Object> begin(String project,String env,String definition,String name,String operation){
  var projects=db.queryForList("select id from midplat_platform where id=? for update",String.class,project);if(projects.isEmpty())throw new ResourceNotFoundException("项目不存在");
  var prior=binding(project,env);
  if(prior!=null){
   // 旧协议语义：同一项目环境的默认分配必须与请求的 definition/name 一致。
   String sourceDefinition=prior.get("source_definition_id")==null?"":(String)prior.get("source_definition_id");
   String sourceName=prior.get("source_name")==null?"":(String)prior.get("source_name");
   boolean sameDefinition=sourceDefinition.isEmpty()?definition.equals(prior.get("definition_id")):definition.equals(sourceDefinition);
   boolean sameName=sourceName.isEmpty()?true:name.equals(sourceName);
   if(!sameDefinition||!sameName)throw new ConflictException("该项目环境已有不同配置的绑定");
   return prior;
  }
  String deployment=UUID.randomUUID().toString();
  String alias="default";
  int suffix=1;while(!db.queryForList("select id from midplat_project_agent_binding where project_id=? and environment=? and alias=?",project,env,alias).isEmpty())alias="default-"+(++suffix);
  db.update("insert into midplat_project_agent_binding(id,project_id,environment,alias,agent_id,desired_agent_version_id,deployment_id,source_definition_id,source_name,status,generation,operation_id) values(?,?,?,?,?,?,?,?,?,'PENDING',1,?)",
   "bnd-"+deployment,project,env,alias,definition,"av-"+deployment+"-1",deployment,definition,name,operation);
  return bindingById("bnd-"+deployment);
 }
 @Transactional public void complete(String project,String env,String deployment){
  db.update("update midplat_project_agent_binding set status='READY',updated_at=CURRENT_TIMESTAMP where project_id=? and environment=? and deployment_id=?",project,env,deployment);
  if(db.queryForObject("select count(*) from midplat_project_runtime_default where project_id=? and environment=? and route_kind='legacy-agent-runs'",Long.class,project,env)==0)
   db.update("insert into midplat_project_runtime_default(project_id,environment,route_kind,binding_id) select ?,?,'legacy-agent-runs',id from midplat_project_agent_binding where project_id=? and environment=? and deployment_id=?",project,env,project,env,deployment);
 }
 /** W4 分配创建的第一段：校验并落 PENDING 行；幂等键重放返回原分配，不同内容返回 409。 */
 @Transactional public Map<String,Object> beginBinding(String project,String env,String agentId,String versionId,String alias,String operation){
  var projects=db.queryForList("select id from midplat_platform where id=? for update",String.class,project);if(projects.isEmpty())throw new ResourceNotFoundException("项目不存在");
  var prior=db.queryForList("select id from midplat_project_agent_binding where operation_id=? and project_id=? and environment=?",String.class,operation,project,env);
  if(!prior.isEmpty()){
   var existing=bindingById(prior.get(0));
   if(existing==null||!agentId.equals(existing.get("definition_id"))||!versionId.equals(existing.get("desired_agent_version_id"))||!alias.equals(existing.get("name")))
    throw new ConflictException("分配幂等键对应不同请求");
   return existing;
  }
  if(db.queryForObject("select count(*) from midplat_project_agent_binding where project_id=? and environment=? and alias=?",Long.class,project,env,alias)>0)
   throw new ConflictException("该项目环境已存在相同调用别名的分配");
  String deployment=UUID.randomUUID().toString();
  try{
   db.update("insert into midplat_project_agent_binding(id,project_id,environment,alias,agent_id,desired_agent_version_id,deployment_id,status,generation,operation_id) values(?,?,?,?,?,?,?,'PENDING',1,?)",
    "bnd-"+deployment,project,env,alias,agentId,versionId,deployment,operation);
  }catch(org.springframework.dao.DuplicateKeyException duplicate){
   // operation_id 全局唯一：跨项目重放同一幂等键在此兜底为 409，不误读其他项目的分配。
   throw new ConflictException("分配幂等键已被使用");
  }
  return bindingById("bnd-"+deployment);
 }
 @Transactional public void completeBinding(String project,String env,String deployment){
  db.update("update midplat_project_agent_binding set status='READY',last_error=null,updated_at=CURRENT_TIMESTAMP where project_id=? and environment=? and deployment_id=?",project,env,deployment);
  if(db.queryForObject("select count(*) from midplat_project_runtime_default where project_id=? and environment=? and route_kind='legacy-agent-runs'",Long.class,project,env)==0)
   db.update("insert into midplat_project_runtime_default(project_id,environment,route_kind,binding_id) select ?,?,'legacy-agent-runs',id from midplat_project_agent_binding where project_id=? and environment=? and deployment_id=?",project,env,project,env,deployment);
 }
 /** 远端建部署失败：分配按未生效处理转 UNBOUND 并释放别名，原因留在 last_error；同版本重绑可复活原行，不阻断智能体删除。 */
 @Transactional public void recordBindingError(String bindingId,String message){
  db.update("update midplat_project_agent_binding set status='UNBOUND',alias=?,last_error=?,updated_at=CURRENT_TIMESTAMP where id=? and status='PENDING'","unbound-"+bindingId,message==null?"智能体服务调用失败":message.substring(0,Math.min(message.length(),480)),bindingId);
 }
 @Transactional public void setDefaultBinding(String project,String env,String routeKind,String bindingId){
  int updated=db.update("update midplat_project_runtime_default set binding_id=?,row_version=row_version+1 where project_id=? and environment=? and route_kind=?",bindingId,project,env,routeKind);
  if(updated==0)db.update("insert into midplat_project_runtime_default(project_id,environment,route_kind,binding_id) values(?,?,?,?)",project,env,routeKind,bindingId);
 }
 /** 解除分配：清默认路由引用并把分配置为 UNBOUND；alias 释放为占位值供重新绑定使用。部署与版本数据保留在智能体服务。 */
 @Transactional public void unbind(String project,String env,String bindingId){
  db.update("delete from midplat_project_runtime_default where project_id=? and environment=? and binding_id=?",project,env,bindingId);
  int updated=db.update("update midplat_project_agent_binding set status='UNBOUND',alias=?,updated_at=CURRENT_TIMESTAMP where id=? and project_id=? and environment=?","unbound-"+bindingId,bindingId,project,env);
  if(updated==0)throw new ResourceNotFoundException("分配不存在");
 }
 /** 物理删除智能体的中台侧残留：默认路由引用与该智能体全部分配行。连接池 auto-commit=false，必须在事务内提交。 */
 @Transactional public void removeAgentBindings(String agentId,List<String> bindingIds){
  for(String binding:bindingIds)db.update("delete from midplat_project_runtime_default where binding_id=?",binding);
  db.update("delete from midplat_project_agent_binding where agent_id=?",agentId);
 }
 /** 恢复已解绑分配：状态回 PENDING 复用同一 bindingId/deploymentId，智能体侧 createForBinding 幂等返回原部署。 */
 @Transactional public Map<String,Object> reviveBinding(String project,String env,String bindingId,String alias,String operation){
  if(db.queryForObject("select count(*) from midplat_project_agent_binding where project_id=? and environment=? and alias=? and id<>?",Long.class,project,env,alias,bindingId)>0)
   throw new ConflictException("该项目环境已存在相同调用别名的分配");
  int updated=db.update("update midplat_project_agent_binding set status='PENDING',alias=?,operation_id=?,last_error=null,generation=generation+1,updated_at=CURRENT_TIMESTAMP where id=? and project_id=? and environment=? and status='UNBOUND'",alias,operation,bindingId,project,env);
  if(updated==0)throw new ConflictException("分配状态已变化，请刷新后重试");
  return bindingById(bindingId);
 }
 public List<Map<String,Object>> bindings(String env){return defaultFlags(db.queryForList("select b.project_id,b.environment,b.deployment_id,b.id as binding_id,b.alias,b.agent_id,b.desired_agent_version_id,lower(b.status) as status,b.last_error,d.binding_id as default_binding_id from midplat_project_agent_binding b"+DEFAULT_JOIN+" where b.environment=? order by b.created_at",env));}
 /** 全部环境的分配：智能体卡片与详情面板的“是否已绑定”口径与 deleteAgent 检查一致（不按环境过滤）。 */
 public List<Map<String,Object>> bindings(){return defaultFlags(db.queryForList("select b.project_id,b.environment,b.deployment_id,b.id as binding_id,b.alias,b.agent_id,b.desired_agent_version_id,lower(b.status) as status,b.last_error,d.binding_id as default_binding_id from midplat_project_agent_binding b"+DEFAULT_JOIN+" order by b.created_at"));}
 public List<Map<String,Object>> bindingsOf(String project,String env){return defaultFlags(db.queryForList("select b.id as binding_id,b.project_id,b.environment,b.deployment_id,b.agent_id,b.desired_agent_version_id,b.alias,lower(b.status) as status,b.generation,b.operation_id,b.last_error,d.binding_id as default_binding_id from midplat_project_agent_binding b"+DEFAULT_JOIN+" where b.project_id=? and b.environment=? order by b.created_at",project,env));}
 /** 模型与提示词直接来自中台目录；项目访问权限由管理入口校验，不再使用项目资源授权表。 */
 public List<Map<String,Object>> prompts(){
  Set<String> live=new HashSet<>(db.queryForList("select id from midplat_prompt",String.class));
  return catalog.list("prompt").stream().filter(r->live.contains(r.get("resourceId")))
   .filter(r->{JsonNode body=((JsonNode)r.get("content")).path("body");return body.isTextual()&&!body.asText().isBlank();}).toList();
 }
 public List<Map<String,Object>> models(boolean llmOnly){
  String sql="select m.id from midplat_model m where not exists (select 1 from midplat_model_availability a where a.model_id=m.id and a.enabled=false)";
  if(llmOnly)sql+=" and m.kind='llm'";
  Set<String> available=new HashSet<>(db.queryForList(sql,String.class));
  return catalog.list("model").stream().filter(r->available.contains(r.get("resourceId")))
   .filter(r->!llmOnly||"llm".equals(((JsonNode)r.get("content")).path("kind").asText())).toList();
 }
 public Map<String,Object> configurationCatalog(){return Map.of("models",models(true),"availableModels",models(false),"prompts",prompts());}
 /** 校验固定修订与当前主数据。即使不再使用 grant，也不能引用被删除、停用或类型不符的模型。 */
 public Map<String,Object> requireModelRevision(String id){
  var revision=catalog.require(id,"model");String model=(String)revision.get("resourceId");
  var rows=db.queryForList("select kind,supports_tool_calls,supports_json_object from midplat_model where id=?",model);
  if(rows.isEmpty())throw new ResourceNotFoundException("模型已删除: "+model);
  if(!"llm".equals(rows.get(0).get("kind"))||!"llm".equals(((JsonNode)revision.get("content")).path("kind").asText()))throw new IllegalArgumentException("选定修订不是 LLM 模型: "+id);
  if(db.queryForObject("select count(*) from midplat_model_availability where model_id=? and enabled=false",Long.class,model)>0)throw new ForbiddenException("模型已停用: "+model);
  revision.put("currentCapabilities",Map.of("toolCalls",Boolean.TRUE.equals(rows.get(0).get("supports_tool_calls")),"jsonObject",Boolean.TRUE.equals(rows.get(0).get("supports_json_object"))));
  return revision;
 }
 public Map<String,Object> requirePromptRevision(String id){
  var revision=catalog.require(id,"prompt");
  if(db.queryForObject("select count(*) from midplat_prompt where id=?",Long.class,revision.get("resourceId"))==0)throw new ResourceNotFoundException("提示词模板已删除: "+revision.get("resourceId"));
  JsonNode body=((JsonNode)revision.get("content")).path("body");
  if(!body.isTextual()||body.asText().isBlank())throw new IllegalArgumentException("规则提示词模板正文不能为空");
  return revision;
 }
 /** 创建空骨架之外，保存、绑定与发布均要求默认模型和规则提示词完整；任务/节点允许覆盖为其他模型。 */
 public List<String> configurationIssues(JsonNode draft){
  List<String> issues=new ArrayList<>();
  if(!draft.isObject()){issues.add("智能体配置不能为空");return issues;}
  Set<String> models=new TreeSet<>();JsonNode base=draft.path("defaultModelRevisionId");
  if(!base.isTextual()||base.asText().isBlank())issues.add("请选择默认模型");else models.add(base.asText());
  for(JsonNode task:draft.path("tasks")){
   collectModelOverride(task,models,issues);
   for(JsonNode node:task.path("nodes"))collectModelOverride(node,models,issues);
  }
  for(String id:models)try{requireModelRevision(id);}catch(ResourceNotFoundException|ForbiddenException|IllegalArgumentException error){issues.add(error.getMessage());}
  JsonNode role=draft.path("role");String kind=role.path("kind").asText();
  if("inline".equals(kind)){
   JsonNode body=role.path("body");
   if(!body.isTextual()||body.asText().isBlank())issues.add("请填写规则提示词");
   else if(body.asText().length()>30000)issues.add("规则提示词不能超过 30000 字符");
  }else if("template".equals(kind)){
   JsonNode id=role.path("revisionId");
   if(!id.isTextual()||id.asText().isBlank())issues.add("请选择有效的规则提示词模板");
   else try{requirePromptRevision(id.asText());}catch(ResourceNotFoundException|IllegalArgumentException error){issues.add(error.getMessage());}
  }else issues.add("请选择规则提示词来源");
  return issues;
 }
 private static void collectModelOverride(JsonNode node,Set<String> models,List<String> issues){
  JsonNode id=node.path("modelRevisionId");
  if(id.isMissingNode()||id.isNull())return;
  if(!id.isTextual())issues.add("模型修订标识必须为文本");else if(!id.asText().isBlank())models.add(id.asText());
 }
 public void validateAgentConfiguration(JsonNode draft){List<String> issues=configurationIssues(draft);if(!issues.isEmpty())throw new IllegalArgumentException(String.join("；",issues));}
 @Transactional public Map<String,Object> resolve(String project,String env,String deployment,List<String> modelIds,List<String> promptIds){
  var b=binding(project,env);if(b==null||!b.get("deployment_id").equals(deployment)||!b.get("status").equals("ready"))throw new ForbiddenException("部署尚未完成项目绑定");
  if(modelIds.size()>100||promptIds.size()>30)throw new IllegalArgumentException("引用数量超过限制");
  Map<String,Object> models=new TreeMap<>(),prompts=new TreeMap<>();
  // Lock the project so reference insertion is idempotent across concurrent publication retries.
  db.queryForObject("select id from midplat_platform where id=? for update",String.class,project);
  for(String id:new TreeSet<>(modelIds)){
   var revision=requireModelRevision(id);
   reference(id,deployment,(String)revision.get("resourceId"),null);models.put(id,revision);
  }
  for(String id:new TreeSet<>(promptIds)){
   var revision=requirePromptRevision(id);
   reference(id,deployment,null,(String)revision.get("resourceId"));prompts.put(id,revision);
  }
  return Map.of("models",models,"prompts",prompts);
 }
 private void reference(String id,String deployment,String model,String prompt){if(db.queryForObject("select count(*) from midplat_catalog_reference where revision_id=? and deployment_id=?",Long.class,id,deployment)==0)db.update("insert into midplat_catalog_reference(revision_id,deployment_id,model_id,prompt_id) values(?,?,?,?)",id,deployment,model,prompt);}
 public void validateDraftReferences(String project,String env,JsonNode draft){validateAgentConfiguration(draft);}
}
