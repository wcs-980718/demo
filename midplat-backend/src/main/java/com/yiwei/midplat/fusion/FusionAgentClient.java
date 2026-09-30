package com.yiwei.midplat.fusion;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.yiwei.midplat.common.api.*;
@Component
public class FusionAgentClient {
 private final ObjectMapper json;private final String url,token;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 public FusionAgentClient(ObjectMapper json,@Value("${midplat.fusion.agent-url:http://127.0.0.1:8742}") String url,@Value("${midplat.fusion.service-token:}") String token){this.json=json;this.url=url.replaceAll("/+$","");this.token=token;URI uri=URI.create(url);if(!"https".equals(uri.getScheme())&&!Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()))throw new IllegalStateException("Agent endpoint requires TLS outside loopback");}
 public JsonNode call(FusionAccess.Identity who,String project,String env,String deployment,String action,Object body){return call(who,project,env,deployment,action,body,"");}
 public JsonNode call(FusionAccess.Identity who,String project,String env,String deployment,String action,Object body,String clientId){
  return dispatch(who,project,env,deployment,action,body,clientId,false);
 }
 /** 独立智能体动作：明确不携带业务项目上下文。 */
 public JsonNode callAgent(FusionAccess.Identity who,String action,Object body){
  return dispatch(who,"","",null,action,body,"",false);
 }
 /** 受控迁移服务身份：唯一允许智能体侧资产主数据写入的通道。 */
 public JsonNode callAsMigrationService(FusionAccess.Identity who,String project,String env,String deployment,String action,Object body){
  return dispatch(who,project,env,deployment,action,body,"",true);
 }
 private JsonNode dispatch(FusionAccess.Identity who,String project,String env,String deployment,String action,Object body,String clientId,boolean migration){
  try{
   Map<String,Object> context=new LinkedHashMap<>();
   context.put("principal",who.principal());context.put("projectId",project==null?"":project);context.put("environment",env==null?"":env);
   context.put("writable",who.writable());context.put("clientId",clientId==null?"":clientId);context.put("migration",migration);
   var command=Map.of("context",context,"action",action,"deploymentId",deployment==null?"":deployment,"body",body,"schemaVersion","w3");
   var request=HttpRequest.newBuilder(URI.create(url+"/internal/fusion/command")).timeout(Duration.ofSeconds(action.equals("activate")?45:15)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
   var response=http.send(request,HttpResponse.BodyHandlers.ofString());JsonNode payload=json.readTree(response.body());
   if(response.statusCode()!=200){String detail=payload.path("detail").asText("智能体管理服务请求失败");throw new FusionUpstreamFault(response.statusCode(),detail);}
   return payload;
  }catch(FusionUpstreamFault e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new FusionUpstreamFault(503,"智能体管理服务请求已中断");}catch(Exception e){throw new FusionUpstreamFault(503,"智能体管理服务暂不可用，数据未降级为演示状态");}
 }
}
