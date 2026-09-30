package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EffectiveConfigCompiler {
 @org.springframework.beans.factory.annotation.Autowired private AssetService assetService;
 @org.springframework.beans.factory.annotation.Autowired private RuntimeArtifacts runtimeArtifacts;
 private final ObjectMapper json;private final String endpoint;private final String token;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 public EffectiveConfigCompiler(ObjectMapper json,@Value("${fusion.midplat-url}") String endpoint,@Value("${fusion.service-token}") String token){
  this.json=json;this.endpoint=endpoint.replaceAll("/+$","");this.token=token;
  URI uri=URI.create(endpoint);if(!"https".equals(uri.getScheme())&&!Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()))throw new IllegalStateException("Catalog endpoint requires TLS outside loopback");
 }
 public JsonNode compile(String projectId,String environment,String deploymentId,String definitionId,JsonNode draft){
  Set<String> models=new TreeSet<>();String base=draft.path("defaultModelRevisionId").asText();if(base.isBlank())throw new FusionFault(422,"请选择默认模型");models.add(base);
  for(JsonNode task:draft.path("tasks")){
   WorkflowGraph.validate(task,true);
   String taskModel=override(task,"modelRevisionId",base);if(taskModel.isBlank())throw new FusionFault(422,"Every task needs a model");models.add(taskModel);
   for(JsonNode node:task.path("nodes"))if(WorkflowGraph.isModel(node))models.add(override(node,"modelRevisionId",taskModel));
  }
  String prompt=draft.path("role").path("kind").asText().equals("template")?draft.path("role").path("revisionId").asText():"";
  JsonNode catalog=resolve(new CatalogRequest(projectId,environment,deploymentId,List.copyOf(models),prompt.isBlank()?List.of():List.of(prompt)));
  String role=prompt.isBlank()?draft.path("role").path("body").asText():catalog.path("prompts").path(prompt).path("content").path("body").asText();
  if(role.isBlank())throw new FusionFault(422,"Effective role is empty");
  Set<String> assetIds=new TreeSet<>();for(JsonNode task:draft.path("tasks"))for(JsonNode id:task.path("assetRevisionIds"))assetIds.add(id.asText());
  ObjectNode assets=assetIds.isEmpty()?json.createObjectNode():assetService.resolve(projectId,environment,assetIds);
  ObjectNode result=json.createObjectNode();result.put("schemaVersion",3);result.put("compilerVersion","fusion-config-v3");result.put("runtimeVersion","fusion-runtime-v2");if(runtimeArtifacts!=null){result.put("runtimeArtifactDigest",runtimeArtifacts.digest());result.put("runtimeImageDigest",runtimeArtifacts.imageDigest());}result.put("definitionRevisionId",definitionId);result.set("assets",assets);
  result.set("draft",draft.deepCopy());result.set("models",catalog.path("models"));result.set("prompts",catalog.path("prompts"));result.put("effectiveRole",role);
  ArrayNode tasks=result.putArray("tasks");
  for(JsonNode task:draft.path("tasks")){
   String taskModel=override(task,"modelRevisionId",base);ObjectNode t=tasks.addObject();t.put("key",task.path("key").asText());t.put("name",task.path("name").asText());t.put("modelRevisionId",taskModel);
   ArrayNode rules=t.putArray("systemInstructions");rules.add(role);if(!task.path("instructions").asText().isBlank())rules.add(task.path("instructions").asText());
   ArrayNode taskAssets=t.putArray("assetRevisionIds");Set<String> names=new HashSet<>();
   for(JsonNode id:task.path("assetRevisionIds")){taskAssets.add(id.asText());JsonNode asset=assets.path(id.asText());if(asset.path("kind").asText().equals("skill"))rules.add(asset.path("content").path("body").asText());else if(!names.add(asset.path("content").path("toolName").asText()))throw new FusionFault(422,"同一任务的工具名称必须唯一");}
   t.put("responseFormat",task.path("responseFormat").asText("text"));
   boolean tools=false;for(JsonNode assetId:taskAssets)if(!assets.path(assetId.asText()).path("kind").asText().equals("skill"))tools=true;
   if(task.path("nodes").isEmpty())validateCapabilities(catalog.path("models").path(taskModel),tools,t.path("responseFormat").asText());

   if(task.has("workflow"))t.set("workflow",task.path("workflow").deepCopy());
   ArrayNode nodes=t.putArray("nodes");for(JsonNode node:task.path("nodes")){
    ObjectNode n=nodes.addObject();n.put("id",node.path("id").asText());n.put("name",node.path("name").asText());n.put("kind",WorkflowGraph.kind(node));
    if(!WorkflowGraph.isModel(node)){if(node.has("condition"))n.set("condition",node.path("condition").deepCopy());continue;}
    n.put("modelRevisionId",override(node,"modelRevisionId",taskModel));n.put("responseFormat",node.path("responseFormat").asText(t.path("responseFormat").asText()));ArrayNode nr=rules.deepCopy();if(!node.path("instructions").asText().isBlank())nr.add(node.path("instructions").asText());n.set("systemInstructions",nr);validateCapabilities(catalog.path("models").path(n.path("modelRevisionId").asText()),tools,n.path("responseFormat").asText());
   }
  }
  return canonical(result);
 }
 private static void validateCapabilities(JsonNode model,boolean tools,String format){
  JsonNode content=model.path("content"),caps=content.path("capabilities"),live=model.has("currentCapabilities")?model.path("currentCapabilities"):caps;
  if(tools&&(!caps.path("toolCalls").asBoolean(false)||!live.path("toolCalls").asBoolean(false)))throw new FusionFault(422,"模型未声明支持工具调用："+content.path("model").asText()+"；请核实能力并选择新修订");
  if(format.equals("json_object")&&(!caps.path("jsonObject").asBoolean(false)||!live.path("jsonObject").asBoolean(false)))throw new FusionFault(422,"模型未声明支持 JSON 对象输出："+content.path("model").asText()+"；请核实能力并选择新修订");
 }
 private JsonNode resolve(CatalogRequest body){
  try{
   var request=HttpRequest.newBuilder(URI.create(endpoint+"/api/fusion-internal/catalog/resolve")).timeout(Duration.ofSeconds(12))
       .header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
   var response=http.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200){String detail=json.readTree(response.body()).path("detail").asText("Catalog resource validation failed");throw new FusionFault(response.statusCode()>=500?503:422,detail);}
   return json.readTree(response.body());
  }catch(FusionFault e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new FusionFault(503,"Catalog resolution interrupted; no release activated");}catch(Exception e){throw new FusionFault(503,"Catalog unavailable; no release activated");}
 }
 private static String override(JsonNode n,String key,String fallback){String v=n.path(key).asText();return v.isBlank()?fallback:v;}
 public static JsonNode canonical(JsonNode node){
  if(node.isObject()){ObjectNode o=JsonNodeFactory.instance.objectNode();TreeSet<String> keys=new TreeSet<>();node.fieldNames().forEachRemaining(keys::add);for(String k:keys)o.set(k,canonical(node.get(k)));return o;}
  if(node.isArray()){ArrayNode a=JsonNodeFactory.instance.arrayNode();node.forEach(v->a.add(canonical(v)));return a;}return node;
 }
 public static String hash(String body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
