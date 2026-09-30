package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class DeploymentService {
 private final JdbcTemplate db;private final ObjectMapper json;
 public DeploymentService(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 public JsonNode parse(String body){try{return json.readTree(body);}catch(Exception e){throw new FusionFault(500,"Stored configuration is invalid");}}
 public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new FusionFault(422,"Invalid configuration");}}
 public List<Map<String,Object>> definitions(){return db.query("select * from fusion_definition order by name",(rs,n)->Map.of("id",rs.getString("id"),"name",rs.getString("name"),"content",parse(rs.getString("content"))));}
 @Transactional public Map<String,Object> createDefinition(JsonNode body){
  String id=text(body,"idempotencyKey",64);identifier(id);String name=text(body,"name",128);String taskKey=text(body,"taskKey",64);identifier(taskKey);String taskName=text(body,"taskName",128);
  ObjectNode content=json.createObjectNode();content.putArray("tasks").addObject().put("key",taskKey).put("name",taskName).put("instructions","").putArray("nodes");
  String encoded=encode(content);var prior=db.queryForList("select name,content from fusion_definition where id=?",id);
  if(!prior.isEmpty()){if(!name.equals(prior.get(0).get("name"))||!encoded.equals(prior.get(0).get("content")))throw new FusionFault(409,"Definition operation has a different payload");}
  else db.update("insert into fusion_definition(id,name,content) values(?,?,?)",id,name,encoded);
  return Map.of("id",id,"name",name,"content",content);
 }
 @Transactional public Deployment create(Context c,String id,JsonNode body){
  identifier(id);String name=text(body,"name",128);String definitionId=text(body,"definitionId",64);
  var existing=db.queryForList("select id,project_id,environment from fusion_deployment where id=?",id);
  if(!existing.isEmpty()){
   var row=existing.get(0);
   if(!row.get("project_id").equals(c.projectId())||!row.get("environment").equals(c.environment()))throw new FusionFault(403,"Deployment belongs to another project or environment");
   Deployment previous=get(c,id);if(!previous.definitionId().equals(definitionId)||!previous.name().equals(name))throw new FusionFault(409,"Deployment request differs from its existing binding");
   return previous;
  }
  var defs=db.queryForList("select content from fusion_definition where id=?",String.class,definitionId);
  if(defs.isEmpty())throw new FusionFault(422,"Unknown definition revision");
  ObjectNode draft=(ObjectNode)parse(defs.get(0)).deepCopy();draft.put("defaultModelRevisionId","");draft.put("temperature",0.4);
  draft.set("role",json.createObjectNode().put("kind","inline").put("body",""));
  db.update("insert into fusion_deployment(id,project_id,environment,definition_id,name,revision,draft) values(?,?,?,?,?,0,?)",id,c.projectId(),c.environment(),definitionId,name,encode(draft));
  audit(c,id,"deployment.created",id);return get(c,id);
 }
 /**
  * W4：分配驱动的部署创建。同一 bindingId 幂等（重复到达不建第二份部署）；
  * 草稿来自不可变 AgentVersion 内容，不再重置为空骨架（修复“复用定义只剩任务骨架”的问题）。
  */
 @Transactional public Deployment createForBinding(Context c,String id,JsonNode body,AgentService agentService){
  identifier(id);String name=text(body,"name",128);String agentId=text(body,"agentId",64);String versionId=text(body,"agentVersionId",64);String bindingId=text(body,"bindingId",64);
  var existing=db.queryForList("select id from fusion_deployment where binding_id=?",String.class,bindingId);
  if(!existing.isEmpty()){
   if(!existing.get(0).equals(id))throw new FusionFault(409,"Binding already owns a different deployment");
   Deployment previous=get(c,id);
   if(!previous.definitionId().equals(agentId))throw new FusionFault(409,"Binding agent changed; create a new binding");
   return previous;
  }
  JsonNode content=agentService.versionContent(agentId,versionId);
  ObjectNode draft=(ObjectNode)content.deepCopy();
  if(db.queryForObject("select count(*) from fusion_deployment where id=?",Long.class,id)>0)throw new FusionFault(409,"Deployment id already exists");
  if(db.queryForObject("select count(*) from fusion_definition where id=?",Long.class,agentId)==0)
   db.update("insert into fusion_definition(id,name,content) values(?,?,?)",agentId,name,encode(draft));
  db.update("insert into fusion_deployment(id,project_id,environment,definition_id,name,revision,draft,binding_id,source_agent_version_id) values(?,?,?,?,?,0,?,?,?)",
   id,c.projectId(),c.environment(),agentId,name,encode(draft),bindingId,versionId);
  audit(c,id,"deployment.created.from-binding",bindingId);return get(c,id);
 }
 public Deployment get(Context c,String id){return read(c,id,false);}
 private Deployment read(Context c,String id,boolean lock){
  var rows=db.query("select * from fusion_deployment where id=?"+(lock?" for update":""),(rs,n)->new Deployment(rs.getString("id"),rs.getString("project_id"),rs.getString("environment"),rs.getString("definition_id"),rs.getString("name"),rs.getLong("revision"),parse(rs.getString("draft")),rs.getString("published_release_id"),rs.getString("active_release_id"),rs.getString("pending_job_id"),rs.getLong("activation_revision")),id);
  if(rows.isEmpty())throw new FusionFault(404,"Deployment not found");
  Deployment d=rows.get(0);
  if(!d.projectId().equals(c.projectId())||!d.environment().equals(c.environment()))throw new FusionFault(403,"Deployment belongs to another project or environment");
  return d;
 }
 @Transactional public Deployment save(Context c,String id,JsonNode body){
  Deployment d=read(c,id,true);checkRevision(d,body);
  JsonNode draft=body.path("draft");validateDraft(draft,false);validateRequiredConfiguration(draft);
  db.update("update fusion_deployment set draft=?,revision=revision+1 where id=?",encode(draft),id);
  audit(c,id,"draft.saved",Long.toString(d.revision()+1));return get(c,id);
 }
 @Transactional public Deployment generate(Context c,String id,JsonNode body){
  read(c,id,true);validateDraft(body.path("draft"),true);
  Deployment saved=save(c,id,body);audit(c,id,"workflow.generated",Long.toString(saved.revision()));return saved;
 }
 /** 初建可保留空骨架，但保存和发布必须由用户配置默认模型与规则提示词。 */
 public void validateRequiredConfiguration(JsonNode draft){
  JsonNode model=draft.path("defaultModelRevisionId");
  if(!model.isTextual()||model.asText().isBlank())throw new FusionFault(422,"请选择默认模型");
  identifier(model.asText());
  JsonNode role=draft.path("role");String kind=role.path("kind").asText();
  if(!Set.of("inline","template").contains(kind))throw new FusionFault(422,"请选择规则提示词来源");
  JsonNode value=role.path("inline".equals(kind)?"body":"revisionId");
  if(!value.isTextual()||value.asText().isBlank())throw new FusionFault(422,"inline".equals(kind)?"请填写规则提示词":"请选择规则提示词模板");
  if("inline".equals(kind)&&value.asText().length()>30000)throw new FusionFault(422,"规则提示词不能超过 30000 字符");
  if("template".equals(kind))identifier(value.asText());
 }
 public void validateDraft(JsonNode draft,boolean publish){
  if(!draft.isObject()||draft.toString().length()>200000)throw new FusionFault(422,"Invalid or oversized draft");
  Set<String> allowed=Set.of("defaultModelRevisionId","temperature","role","tasks");
  draft.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new FusionFault(422,"Unknown draft field: "+k);});
  JsonNode role=draft.path("role");String kind=role.path("kind").asText();
  if(!role.isObject()||!Set.of("inline","template").contains(kind))throw new FusionFault(422,"Role must be inline or a fixed template revision");
  role.fieldNames().forEachRemaining(k->{if(!Set.of("kind",kind.equals("inline")?"body":"revisionId").contains(k))throw new FusionFault(422,"Prompt sources are mutually exclusive");});
  if(publish)validateRequiredConfiguration(draft);
  if(!draft.path("temperature").isNumber()||!Double.isFinite(draft.path("temperature").asDouble())||draft.path("temperature").asDouble()<0||draft.path("temperature").asDouble()>2)throw new FusionFault(422,"Temperature must be between 0 and 2");
  JsonNode tasks=draft.path("tasks");if(!tasks.isArray()||tasks.isEmpty()||tasks.size()>20)throw new FusionFault(422,"Configure between 1 and 20 tasks");
  Set<String> keys=new HashSet<>();
  for(JsonNode task:tasks){
   String key=text(task,"key",64);identifier(key);if(!keys.add(key))throw new FusionFault(422,"Task keys must be unique");
   text(task,"name",128);if(!task.path("instructions").isTextual())throw new FusionFault(422,"Task instructions must be text");
   if(publish&&task.path("modelRevisionId").asText(draft.path("defaultModelRevisionId").asText()).isBlank()&&draft.path("defaultModelRevisionId").asText().isBlank())throw new FusionFault(422,"Every task needs an available model revision");
   task.fieldNames().forEachRemaining(k->{if(!Set.of("key","name","instructions","modelRevisionId","nodes","assetRevisionIds","responseFormat","workflow").contains(k))throw new FusionFault(422,"Unknown task field: "+k);});
   if(task.has("assetRevisionIds")&&(!task.path("assetRevisionIds").isArray()||task.path("assetRevisionIds").size()>20))throw new FusionFault(422,"任务资产引用超过限制");
   for(JsonNode asset:task.path("assetRevisionIds"))identifier(asset.asText());
   if(task.has("responseFormat")&&!Set.of("text","json_object").contains(task.path("responseFormat").asText()))throw new FusionFault(422,"响应格式无效");
   JsonNode nodes=task.path("nodes");if(!nodes.isArray()||nodes.size()>30)throw new FusionFault(422,"Task nodes must be an array with at most 30 items");
   Set<String> ids=new HashSet<>();for(JsonNode node:nodes){String nodeId=text(node,"id",64);identifier(nodeId);if(!ids.add(nodeId))throw new FusionFault(422,"Node IDs must be unique");text(node,"name",128);if(!node.path("instructions").isTextual())throw new FusionFault(422,"Node instructions must be text");node.fieldNames().forEachRemaining(k->{if(!Set.of("id","name","instructions","modelRevisionId","responseFormat","kind","condition").contains(k))throw new FusionFault(422,"Unknown node field: "+k);});if(node.has("responseFormat")&&!Set.of("text","json_object").contains(node.path("responseFormat").asText()))throw new FusionFault(422,"节点响应格式无效");}
   WorkflowGraph.validate(task,publish);
  }
 }
 @Transactional public Publication publish(Context c,String id,JsonNode body){
  Deployment d=read(c,id,true);String key=text(body,"idempotencyKey",64);String note=text(body,"note",500);
  String requestHash=EffectiveConfigCompiler.hash(encode(body));
  var prior=db.queryForList("select id,request_hash from fusion_publication where deployment_id=? and idempotency_key=?",id,key);
  if(!prior.isEmpty()){
   if(!prior.get(0).get("request_hash").equals(requestHash))throw new FusionFault(409,"Idempotency key was used for a different request");
   return publication(c,id,(String)prior.get(0).get("id"));
  }
  checkRevision(d,body);if(d.pendingJobId()!=null)throw new FusionFault(409,"A publication is already in progress");
  String restored=body.path("restoreReleaseId").asText("");JsonNode draft=d.draft();
  if(!restored.isBlank())draft=release(c,id,restored).snapshot().path("draft");
  validateDraft(draft,true);
  String job=UUID.randomUUID().toString();
  db.update("insert into fusion_publication(id,deployment_id,idempotency_key,request_hash,principal,status,draft,note,restored_from) values(?,?,?,?,?,'queued',?,?,?)",job,id,key,requestHash,c.principal(),encode(draft),note,restored.isBlank()?null:restored);
  db.update("update fusion_deployment set pending_job_id=? where id=?",job,id);audit(c,id,"publication.requested",job);
  return publication(c,id,job);
 }
 public Publication publication(Context c,String deploymentId,String jobId){
  get(c,deploymentId);
  var rows=db.query("select * from fusion_publication where id=? and deployment_id=?",(rs,n)->new Publication(rs.getString("id"),rs.getString("deployment_id"),rs.getString("status"),rs.getString("error"),rs.getString("release_id")),jobId,deploymentId);
  if(rows.isEmpty())throw new FusionFault(404,"Publication not found");return rows.get(0);
 }
 public List<Release> releases(Context c,String id){get(c,id);return db.query("select * from fusion_release where deployment_id=? order by sequence desc",(rs,n)->new Release(rs.getString("id"),id,rs.getLong("sequence"),rs.getString("note"),rs.getString("restored_from"),rs.getString("content_hash"),parse(rs.getString("snapshot")),rs.getString("created_at")),id);}
 public Release release(Context c,String id,String releaseId){return releases(c,id).stream().filter(r->r.id().equals(releaseId)).findFirst().orElseThrow(()->new FusionFault(404,"Historical release not found in this deployment"));}
 public List<Map<String,Object>> audit(Context c,String id){get(c,id);return db.queryForList("select principal,action,target,created_at from fusion_audit where deployment_id=? order by created_at desc limit 100",id);}
 public void audit(Context c,String id,String action,String target){db.update("insert into fusion_audit(id,deployment_id,principal,action,target) values(?,?,?,?,?)",UUID.randomUUID().toString(),id,c.principal(),action,target);}
 private void checkRevision(Deployment d,JsonNode body){if(!body.path("expectedRevision").isIntegralNumber()||!body.path("expectedRevision").canConvertToLong()||body.path("expectedRevision").asLong()!=d.revision())throw new FusionFault(409,"Draft changed; reload before saving or publishing");}
 public static void identifier(String value){if(value==null||!value.matches("[a-zA-Z0-9_-]{1,64}"))throw new FusionFault(422,"Invalid identifier");}
 public static String text(JsonNode n,String key,int max){String s=n.path(key).asText("").trim();if(s.isEmpty()||s.length()>max)throw new FusionFault(422,"Invalid "+key);return s;}
}
