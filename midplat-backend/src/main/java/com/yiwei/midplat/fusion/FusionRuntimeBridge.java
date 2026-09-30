package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.yiwei.midplat.common.api.*;
import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.PlatformCredentialService.CallerPlatform;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Existing project credentials resolve a server-owned deployment; clients cannot choose another project. */
@Service
public class FusionRuntimeBridge {
    private final FusionState state;private final FusionAgentClient agent;private final String environment;private final boolean enabled;
    public FusionRuntimeBridge(FusionState state,FusionAgentClient agent,@Value("${midplat.fusion.runtime-environment:development}")String environment,@Value("${midplat.fusion.execution-enabled:false}")boolean enabled){this.state=state;this.agent=agent;this.environment=environment;this.enabled=enabled;}
    public boolean manages(String project){return state.binding(project,environment)!=null;}
    public record Configuration(JsonNode deployment, JsonNode release) {}
    public Configuration configuration(String project) {return configuration(project,"");}
    public Configuration configuration(String project, String clientId) {
        var binding=state.binding(project,environment);
        if(binding==null||!"ready".equals(binding.get("status")))throw new ConflictException("项目部署绑定尚未完成");
        var who=new FusionAccess.Identity("service:"+project,Set.of(project),false,false);
        String id=(String)binding.get("deployment_id");
        JsonNode deployment=agent.call(who,project,environment,id,"get",Map.of(),clientId);
        String active=deployment.path("activeReleaseId").asText("");
        if(active.isBlank())throw new ConflictException("项目尚未上线运行版本，请完成发布与上线");
        for(JsonNode release:agent.call(who,project,environment,id,"releases",Map.of(),clientId))
            if(active.equals(release.path("id").asText()))return new Configuration(deployment,release);
        throw new FusionUpstreamFault(503,"当前运行版本记录缺失");
    }
    /** 项目当前生效的智能体配置：已上线版本优先；执行服务未启用、从未上线时取最新发布版本。 */
    public record Effective(JsonNode deployment, JsonNode release, boolean active) {}
    /** 部署摘要，不含发布快照；用于低成本判断配置是否变化。 */
    public JsonNode deploymentSummary(String project){
        var binding=readyBinding(project);
        return binding==null?null:agent.call(service(project),project,environment,(String)binding.get("deployment_id"),"get",Map.of());
    }
    public Effective effective(String project){
        var binding=readyBinding(project);if(binding==null)return null;
        String id=(String)binding.get("deployment_id");var who=service(project);
        JsonNode deployment=agent.call(who,project,environment,id,"get",Map.of());
        JsonNode releases=agent.call(who,project,environment,id,"releases",Map.of());
        String active=deployment.path("activeReleaseId").asText("");
        for(JsonNode release:releases){
            if(active.isBlank())return new Effective(deployment,release,false);
            if(active.equals(release.path("id").asText()))return new Effective(deployment,release,true);
        }
        return null;
    }
    private Map<String,Object> readyBinding(String project){
        var binding=state.binding(project,environment);
        return binding!=null&&"ready".equals(binding.get("status"))?binding:null;
    }
    private static FusionAccess.Identity service(String project){return new FusionAccess.Identity("service:"+project,Set.of(project),false,false);}
    public JsonNode command(ManagedPlatform platform,String action,Object body){return commandWithClient(platform,action,body,"");}
    public JsonNode command(CallerPlatform caller,String action,Object body){return commandWithClient(caller.platform(),action,body,caller.clientId());}
    private JsonNode commandWithClient(ManagedPlatform platform,String action,Object body,String clientId){
        if(!enabled)throw new FusionUpstreamFault(503,"融合执行服务未启用");
        var binding=state.binding(platform.getId(),environment);if(binding==null||!"ready".equals(binding.get("status")))throw new ConflictException("项目尚未绑定可执行的智能体部署");
        return agent.call(new FusionAccess.Identity("service:"+platform.getId(),Set.of(platform.getId()),true,false),platform.getId(),environment,(String)binding.get("deployment_id"),action,body,clientId);
    }
    public record Chat(String content,String model){}
    public Chat chat(ManagedPlatform project,String input){return chat(project,input,"");}
    public Chat chat(ManagedPlatform project,String input,String clientId){
        JsonNode run=commandWithClient(project,"startRun",Map.of("input",input,"taskKey","","idempotencyKey",UUID.randomUUID().toString()),clientId);String id=run.path("id").asText();
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        try{
            while(System.nanoTime()<deadline){
                run=commandWithClient(project,"run",Map.of("id",id),clientId);String status=run.path("status").asText();
                if(status.equals("succeeded"))return new Chat(run.path("output").asText(),"agenthub:"+run.path("release_id").asText());
                if(Set.of("failed","cancelled").contains(status))throw new FusionUpstreamFault(502,run.path("error").asText("智能体执行未完成"));
                Thread.sleep(250);
            }
            throw new FusionUpstreamFault(504,"智能体执行超时，请通过运行接口查询结果");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new FusionUpstreamFault(503,"运行等待已中断");}
        finally{if(!run.path("status").asText().equals("succeeded"))try{commandWithClient(project,"cancelRun",Map.of("id",id),clientId);}catch(Exception ignored){}try{commandWithClient(project,"closeSession",Map.of("id",run.path("session_id").asText()),clientId);}catch(Exception ignored){}}
    }
}
