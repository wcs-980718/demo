package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.yiwei.midplat.common.api.*;
import com.yiwei.midplat.platform.ManagedPlatformRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/fusion")
public class FusionController {
 @org.springframework.beans.factory.annotation.Value("${midplat.fusion.execution-enabled:false}") private boolean executionEnabled;
 @org.springframework.beans.factory.annotation.Value("${midplat.fusion.runtime-environment:development}") private String runtimeEnvironment = "development";
 @org.springframework.beans.factory.annotation.Autowired private ModelInvocationGateway modelGateway;
 private final FusionAccess access;private final FusionState state;private final FusionAgentClient agent;private final FusionCatalog catalog;private final ManagedPlatformRepository projects;
 public FusionController(FusionAccess access,FusionState state,FusionAgentClient agent,FusionCatalog catalog,ManagedPlatformRepository projects){this.access=access;this.state=state;this.agent=agent;this.catalog=catalog;this.projects=projects;}
 @GetMapping("/status") public Object status(){return Map.of("enabled",access.enabled(),"localAuthentication",access.local(),"intranetManagement",access.intranet(),"executionEnabled",executionEnabled,"runtimeEnvironment",runtimeEnvironment);}
 @GetMapping("/session") public Object session(HttpServletRequest req){Map<String,Object> view=new LinkedHashMap<>();view.put("identity",FusionAccess.identity(req));view.put("localAuthentication",access.local());view.put("intranetManagement",access.intranet());view.put("csrf",access.local()||access.intranet()?req.getSession().getAttribute("fusion.csrf"):null);return view;}
 @GetMapping("/bindings") public Object bindings(HttpServletRequest req,@RequestParam(required=false) String environment){
  var who=FusionAccess.identity(req);
  // 不传 environment 返回全部环境的分配：智能体绑定状态需与删除校验同口径，避免非当前环境绑定不可见。
  if(environment==null||environment.isBlank())return state.bindings().stream().filter(b->who.admin()||who.projects().contains(b.get("project_id"))).toList();
  check("scope",environment);
  return state.bindings(environment).stream().filter(b->who.admin()||who.projects().contains(b.get("project_id"))).toList();
 }
 @GetMapping("/projects") public Object projects(HttpServletRequest req){var who=FusionAccess.identity(req);return projects.findAllByOrderByNameAsc().stream().filter(p->who.admin()||who.projects().contains(p.getId())).map(p->{var row=new LinkedHashMap<String,Object>();row.put("id",p.getId());row.put("name",p.getName());if(p.getAgentMode()!=null&&!p.getAgentMode().isBlank())row.put("agentMode",p.getAgentMode());return row;}).toList();}
 @GetMapping("/projects/{project}/binding") public Object binding(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment){FusionAccess.project(req,project,false);check(project,environment);return state.binding(project,environment);}
 @GetMapping("/projects/{project}/definitions") public Object definitions(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment){FusionAccess.project(req,project,false);check(project,environment);return agent.call(FusionAccess.identity(req),project,environment,null,"definitions",Map.of());}
 @PostMapping("/projects/{project}/definitions") public Object createDefinition(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){FusionAccess.admin(req);check(project,environment);return agent.call(FusionAccess.identity(req),project,environment,null,"createDefinition",body);}
 @PostMapping("/projects/{project}/binding") public Object bind(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){
  FusionAccess.project(req,project,true);check(project,environment);
  String definition=text(body,"definitionId",64),name=text(body,"name",128),operation=text(body,"idempotencyKey",64);
  JsonNode definitions=agent.call(FusionAccess.identity(req),project,environment,null,"definitions",Map.of());
  boolean known=false;for(JsonNode item:definitions){if(definition.equals(item.path("id").asText()))known=true;}
  if(!known)throw new IllegalArgumentException("智能体定义修订不存在");
  // Each state method commits independently. No database transaction spans the remote request.
  var b=state.begin(project,environment,definition,name,operation);
  String id=(String)b.get("deployment_id");
  try{
   agent.call(FusionAccess.identity(req),project,environment,id,"create",Map.of("definitionId",definition,"name",name));
  }catch(RuntimeException failure){
   state.recordBindingError((String)b.get("binding_id"),failure.getMessage());
   throw failure;
  }
  state.complete(project,environment,id);return state.binding(project,environment);
 }
 @GetMapping("/catalog") public Object agentCatalog(HttpServletRequest req){FusionAccess.admin(req);return state.configurationCatalog();}
 @GetMapping("/projects/{project}/catalog") public Object catalog(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment){
  FusionAccess.project(req,project,false);check(project,environment);
  return state.configurationCatalog();
 }
 @PutMapping("/projects/{project}/model-grants/{model}") public Object grant(HttpServletRequest req,@PathVariable String project,@PathVariable String model,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){
  FusionAccess.admin(req);check(project,environment);identifier(model);throw new FusionUpstreamFault(410,"项目模型授权已取消，请直接在智能体配置中选择模型");
 }
 @PutMapping("/projects/{project}/prompt-grants/{prompt}") public Object grantPrompt(HttpServletRequest req,@PathVariable String project,@PathVariable String prompt,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){
  FusionAccess.admin(req);check(project,environment);identifier(prompt);throw new FusionUpstreamFault(410,"项目提示词授权已取消，请直接在智能体配置中选择规则提示词");
 }
 @GetMapping("/projects/{project}/effective-config") public Object config(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment){return call(req,project,environment,"get",Map.of(),false);}
 @GetMapping("/projects/{project}/assets") public Object assets(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development")String environment){return assetCall(req,project,environment,"assets",Map.of(),false);}
 /** W3：资产主数据写入退役。保留路由但服务端拒绝普通管理身份，只读解析继续可用。 */
 @PostMapping("/projects/{project}/assets") public Object saveAsset(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development")String environment,@RequestBody JsonNode body){throw new ForbiddenException("能力资产已迁移至中台统一管理：智能体侧只读引用固定修订，请在能力中心维护资产");}
 @GetMapping("/projects/{project}/assets/{id}/revisions") public Object assetRevisions(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestParam(defaultValue="development")String environment){identifier(id);return assetCall(req,project,environment,"assetRevisions",Map.of("id",id),false);}
 @PutMapping("/projects/{project}/assets/{id}/enabled") public Object assetEnabled(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestParam(defaultValue="development")String environment,@RequestBody JsonNode body){identifier(id);throw new ForbiddenException("能力资产已迁移至中台统一管理：启用状态由中台能力资产管理维护");}
 private Object assetCall(HttpServletRequest req,String project,String env,String action,Object body,boolean write){FusionAccess.project(req,project,write);check(project,env);if(!projects.existsById(project))throw new ResourceNotFoundException("项目不存在");return agent.call(FusionAccess.identity(req),project,env,null,action,body);}
 @PutMapping("/projects/{project}/draft") public Object save(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){
  FusionAccess.project(req,project,true);check(project,environment);state.validateDraftReferences(project,environment,body.path("draft"));return call(req,project,environment,"save",body,true);
 }
 @PutMapping("/projects/{project}/draft/generate") public Object generate(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){
  FusionAccess.project(req,project,true);check(project,environment);state.validateDraftReferences(project,environment,body.path("draft"));return call(req,project,environment,"generate",body,true);
 }
 @GetMapping("/projects/{project}/releases") public Object releases(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment){return call(req,project,environment,"releases",Map.of(),false);}
 @PostMapping("/projects/{project}/publications") public ResponseEntity<Object> publish(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){Object result=call(req,project,environment,"publish",body,true);hotUpdate(project,environment);return ResponseEntity.accepted().body(result);}
 @GetMapping("/projects/{project}/publications/{id}") public Object publication(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestParam(defaultValue="development") String environment){identifier(id);return call(req,project,environment,"publication",Map.of("id",id),false);}
 @GetMapping("/projects/{project}/audit") public Object audit(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment){return call(req,project,environment,"audit",Map.of(),false);}
 @PostMapping("/projects/{project}/activations") public Object activate(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){Object result=call(req,project,environment,"activate",body,true);hotUpdate(project,environment);return result;}
 @GetMapping("/projects/{project}/activations") public Object activations(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment){return call(req,project,environment,"activations",Map.of(),false);}
 @PostMapping("/projects/{project}/runs") public ResponseEntity<Object> startRun(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody JsonNode body){return ResponseEntity.accepted().body(call(req,project,environment,"startRun",body,true));}
 @GetMapping("/projects/{project}/runs") public Object runs(HttpServletRequest req,@PathVariable String project,@RequestParam(defaultValue="development") String environment){return call(req,project,environment,"runs",Map.of(),false);}
 @GetMapping("/projects/{project}/runs/{id}") public Object run(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestParam(defaultValue="development") String environment){identifier(id);return call(req,project,environment,"run",Map.of("id",id),false);}
 @GetMapping("/projects/{project}/runs/{id}/events") public Object events(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestParam(defaultValue="development") String environment,@RequestParam(defaultValue="0") long after){identifier(id);return call(req,project,environment,"events",Map.of("id",id,"after",after),false);}
 @PostMapping("/projects/{project}/runs/{id}/cancel") public Object cancel(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestParam(defaultValue="development") String environment){identifier(id);Object result=call(req,project,environment,"cancelRun",Map.of("id",id),true);modelGateway.cancelAuthorizedRun(id);return result;}
 @PostMapping("/projects/{project}/sessions/{id}/close") public Object closeSession(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestParam(defaultValue="development") String environment){identifier(id);return call(req,project,environment,"closeSession",Map.of("id",id),true);}
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.yiwei.midplat.platform.AgentConfigDeliveryService hotUpdates;
 /** 发布或上线后立即把智能体配置直连热更新到业务项目；不影响管理接口的返回。 */
 private void hotUpdate(String project,String env){if(hotUpdates!=null&&runtimeEnvironment.equals(env))try{hotUpdates.trigger(project);}catch(RuntimeException ignored){}}
 private Object call(HttpServletRequest req,String project,String env,String action,Object body,boolean write){
  FusionAccess.project(req,project,write);check(project,env);var b=state.binding(project,env);if(b==null||!b.get("status").equals("ready"))throw new ConflictException("请先完成项目部署绑定");
  return agent.call(FusionAccess.identity(req),project,env,(String)b.get("deployment_id"),action,body);
 }
 static void check(String project,String env){identifier(project);if(env==null||!env.matches("[a-zA-Z0-9_-]{1,32}"))throw new IllegalArgumentException("环境标识无效");}
 static void identifier(String id){if(id==null||!id.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("标识格式无效");}
 static String text(JsonNode body,String key,int limit){String text=body.path(key).asText("").trim();if(text.isEmpty()||text.length()>limit)throw new IllegalArgumentException(key+" 不能为空或超过长度限制");return text;}
}
