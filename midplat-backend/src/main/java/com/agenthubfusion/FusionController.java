package com.agenthubfusion;
import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class FusionController {
 private final DeploymentService service; private final ActivationService activation; private final RunService runs; private final AgentService agents;
 @org.springframework.beans.factory.annotation.Autowired private AssetService assets;
 @org.springframework.beans.factory.annotation.Autowired private BusinessRunService businessRuns;
 @org.springframework.beans.factory.annotation.Autowired private ExecutionSigner signer;
 public FusionController(DeploymentService service,ActivationService activation,RunService runs,AgentService agents){this.service=service;this.activation=activation;this.runs=runs;this.agents=agents;}
 private static final java.util.Set<String> READ_ACTIONS=java.util.Set.of("get","releases","publication","definitions","audit","activations","runs","run","events","assets","assetRevisions","agents","agentDraft","agentVersions");
 private static final java.util.Set<String> AGENT_ACTIONS=java.util.Set.of("agents","createAgent","updateAgent","agentDraft","saveAgentDraft","publishAgentVersion","agentVersions","deleteAgent");
 @PostMapping("/internal/fusion/command") public Object command(@RequestBody Command command){
  Context c=command.context();
  String action=command.action();
  if(c==null||c.principal()==null||c.principal().isBlank()||c.environment()==null)throw new FusionFault(403,"Missing trusted context");
  if(action==null)throw new FusionFault(422,"Missing action");
  if(command.schemaVersion()!=null&&!java.util.Set.of("w2","w3").contains(command.schemaVersion()))throw new FusionFault(422,"Unsupported command protocol version");
  boolean agentScoped=AGENT_ACTIONS.contains(action);
  // 独立智能体动作明确不携带业务项目；部署动作必须携带真实项目上下文，禁止传假 projectId。
  if(agentScoped&&c.projectId()!=null&&!c.projectId().isBlank())throw new FusionFault(403,"Agent actions must not carry a project context");
  if(!agentScoped&&(c.projectId()==null||c.projectId().isBlank()))throw new FusionFault(403,"Missing trusted project context");
  if(!READ_ACTIONS.contains(action)&&!c.writable())throw new FusionFault(403,"Read-only identity");
  JsonNode b=command.body();
  return switch(action){
   case "agents" -> agents.list();
   case "createAgent" -> agents.create(c,b);
   case "updateAgent" -> agents.update(c,b.path("agentId").asText(),b);
   case "agentDraft" -> agents.draft(b.path("agentId").asText(),b.path("idempotencyKey").asText());
   case "saveAgentDraft" -> agents.saveDraft(c,b.path("agentId").asText(),b);
   case "publishAgentVersion" -> agents.publish(c,b.path("agentId").asText(),b);
   case "agentVersions" -> agents.versions(b.path("agentId").asText());
   case "deleteAgent" -> agents.delete(c,b.path("agentId").asText(),b);
   case "definitions" -> service.definitions();
   case "createDefinition" -> service.createDefinition(b);
   case "create" -> service.create(c,command.deploymentId(),b);
   case "createForBinding" -> service.createForBinding(c,command.deploymentId(),b,agents);
   case "get" -> service.get(c,command.deploymentId());
   case "save" -> service.save(c,command.deploymentId(),b);
   case "generate" -> service.generate(c,command.deploymentId(),b);
   case "releases" -> service.releases(c,command.deploymentId());
   case "publish" -> service.publish(c,command.deploymentId(),b);
   case "publication" -> service.publication(c,command.deploymentId(),b.path("id").asText());
   case "audit" -> service.audit(c,command.deploymentId());
   case "activate" -> activation.activate(c,command.deploymentId(),b);
   case "activations" -> activation.history(c,command.deploymentId());
   case "startRun" -> runs.start(c,command.deploymentId(),b);
   case "startBusinessRun" -> businessRuns.start(c,command.deploymentId(),b);
   case "finishBusinessRun" -> businessRuns.finish(c,command.deploymentId(),b);
   case "runs" -> runs.list(c,command.deploymentId());
   case "run" -> runs.get(c,command.deploymentId(),b.path("id").asText());
   case "events" -> runs.events(c,command.deploymentId(),b.path("id").asText(),b.path("after").asLong());
   case "cancelRun" -> runs.cancel(c,command.deploymentId(),b.path("id").asText());
   case "closeSession" -> runs.closeSession(c,command.deploymentId(),b.path("id").asText());
   case "assets" -> assets.list(c);
   case "assetRevisions" -> assets.revisions(c,b.path("id").asText());
   case "saveAsset" -> assetWrite(c,b,false);
   case "assetEnabled" -> assetWrite(c,b,true);
   default -> throw new FusionFault(404,"Unsupported management action");
 };
 }
 /** W3：写入拒绝在 AssetService 内强制执行，这里仅按动作分发。 */
 private Object assetWrite(Context c,JsonNode b,boolean enableToggle){
  return enableToggle?assets.enabled(c,b.path("id").asText(),b.path("enabled").asBoolean()):assets.save(c,b);
 }
 @PostMapping("/internal/fusion/tool-invocations") public Object tool(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody JsonNode body){return assets.invoke(signer.verify(auth),body);}
 @ExceptionHandler(FusionFault.class) public ResponseEntity<ProblemDetail> fault(FusionFault e){return ResponseEntity.status(e.status).body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status),e.getMessage()));}
 @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) public ResponseEntity<ProblemDetail> conflict(){return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Concurrent operation conflicts; refresh and retry"));}
 @ExceptionHandler(Exception.class) public ResponseEntity<ProblemDetail> unexpected(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Fusion management failed",e);return ResponseEntity.internalServerError().body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,"Management operation failed"));}
}
