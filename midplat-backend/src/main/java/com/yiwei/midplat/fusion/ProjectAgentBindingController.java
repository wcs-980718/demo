package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * W4/W5 管理端点：独立智能体（agentId 维度）与项目多分配（bindingId 维度）。
 * 全部位于 /api/fusion 下，受 FusionAccess 管理身份过滤保护。
 */
@RestController
@RequestMapping("/api/fusion")
public class ProjectAgentBindingController {
    private final FusionAgentClient agent;private final ProjectAgentBindingService bindings;private final FusionAccess access;private final FusionState state;
    public ProjectAgentBindingController(FusionAgentClient agent,ProjectAgentBindingService bindings,FusionAccess access,FusionState state){this.agent=agent;this.bindings=bindings;this.access=access;this.state=state;}

    // ---- 独立智能体 ----

    @GetMapping("/agents")
    Object agents(HttpServletRequest request){
        FusionAccess.admin(request);
        return agent.callAgent(FusionAccess.identity(request),"agents",Map.of());
    }

    public record CreateAgentCmd(String name,String description,String taskKey,String taskName,String idempotencyKey){}

    @PostMapping("/agents")
    Object createAgent(HttpServletRequest request,@RequestBody CreateAgentCmd cmd){
        FusionAccess.admin(request);
        Map<String,Object> body=new HashMap<>();
        body.put("name",require(cmd.name(),"name"));body.put("idempotencyKey",require(cmd.idempotencyKey(),"idempotencyKey"));
        if(cmd.description()!=null)body.put("description",cmd.description());
        if(cmd.taskKey()!=null&&!cmd.taskKey().isBlank())body.put("taskKey",cmd.taskKey());
        if(cmd.taskName()!=null&&!cmd.taskName().isBlank())body.put("taskName",cmd.taskName());
        return agent.callAgent(FusionAccess.identity(request),"createAgent",body);
    }

    public record UpdateAgentCmd(String name,String description){}

    @PutMapping("/agents/{agentId}")
    Object updateAgent(HttpServletRequest request,@PathVariable String agentId,@RequestBody UpdateAgentCmd cmd){
        FusionAccess.admin(request);FusionController.identifier(agentId);
        Map<String,Object> body=new HashMap<>();
        body.put("agentId",agentId);body.put("name",require(cmd.name(),"name"));
        if(cmd.description()!=null)body.put("description",cmd.description());
        return agent.callAgent(FusionAccess.identity(request),"updateAgent",body);
    }

    @GetMapping("/agents/{agentId}/draft")
    Object agentDraft(HttpServletRequest request,@PathVariable String agentId){
        FusionAccess.admin(request);FusionController.identifier(agentId);
        return agent.callAgent(FusionAccess.identity(request),"agentDraft",Map.of("agentId",agentId));
    }

    @PutMapping("/agents/{agentId}/draft")
    Object saveAgentDraft(HttpServletRequest request,@PathVariable String agentId,@RequestBody JsonNode body){
        FusionAccess.admin(request);FusionController.identifier(agentId);
        state.validateAgentConfiguration(body.path("draft"));
        Map<String,Object> payload=new HashMap<>();payload.put("agentId",agentId);payload.put("expectedRevision",body.path("expectedRevision").asLong());payload.put("draft",body.path("draft"));
        return agent.callAgent(FusionAccess.identity(request),"saveAgentDraft",payload);
    }

    @GetMapping("/agents/{agentId}/versions")
    Object agentVersions(HttpServletRequest request,@PathVariable String agentId){
        FusionAccess.admin(request);FusionController.identifier(agentId);
        return agent.callAgent(FusionAccess.identity(request),"agentVersions",Map.of("agentId",agentId));
    }

    public record PublishAgentCmd(long expectedRevision,String note,String idempotencyKey){}

    @PostMapping("/agents/{agentId}/versions")
    Object publishAgentVersion(HttpServletRequest request,@PathVariable String agentId,@RequestBody PublishAgentCmd cmd){
        FusionAccess.admin(request);FusionController.identifier(agentId);
        String key=require(cmd.idempotencyKey(),"idempotencyKey");
        JsonNode current=agent.callAgent(FusionAccess.identity(request),"agentDraft",Map.of("agentId",agentId,"idempotencyKey",key));
        // 已存在的幂等操作交给智能体服务校验请求摘要并重放；停用资源不应破坏历史成功请求的重放。
        // 新请求的当前修订必须有效；过期修订交给服务端返回冲突，不能用另一个草稿替代。
        if(!current.path("publicationExists").asBoolean()&&current.path("expectedRevision").asLong(-1)==cmd.expectedRevision())state.validateAgentConfiguration(current.path("draft"));
        Map<String,Object> payload=new HashMap<>();
        payload.put("agentId",agentId);payload.put("expectedRevision",cmd.expectedRevision());payload.put("idempotencyKey",key);
        if(cmd.note()!=null)payload.put("note",cmd.note());
        return agent.callAgent(FusionAccess.identity(request),"publishAgentVersion",payload);
    }

    @DeleteMapping("/agents/{agentId}")
    Object deleteAgent(HttpServletRequest request,@PathVariable String agentId){
        FusionAccess.admin(request);FusionController.identifier(agentId);
        return bindings.deleteAgent(FusionAccess.identity(request),agentId);
    }

    // ---- 项目多分配 ----

    @GetMapping("/projects/{project}/agent-bindings")
    Object listBindings(HttpServletRequest request,@PathVariable String project,@RequestParam(defaultValue="development") String environment){
        FusionAccess.project(request,project,false);FusionController.check(project,environment);
        return bindings.list(project,environment);
    }

    public record PreflightCmd(String agentId,String agentVersionId){}

    @PostMapping("/projects/{project}/agent-bindings/preflight")
    Object preflight(HttpServletRequest request,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody PreflightCmd cmd){
        FusionAccess.project(request,project,false);FusionController.check(project,environment);
        return bindings.preflight(FusionAccess.identity(request),project,environment,require(cmd.agentId(),"agentId"),require(cmd.agentVersionId(),"agentVersionId"));
    }

    public record CreateBindingCmd(String agentId,String agentVersionId,String alias,String name,String idempotencyKey){}

    @PostMapping("/projects/{project}/agent-bindings")
    Object createBinding(HttpServletRequest request,@PathVariable String project,@RequestParam(defaultValue="development") String environment,@RequestBody CreateBindingCmd cmd){
        FusionAccess.project(request,project,true);FusionController.check(project,environment);
        String alias=cmd.alias()==null||cmd.alias().isBlank()?"default":cmd.alias();
        String name=cmd.name()==null||cmd.name().isBlank()?cmd.agentId():cmd.name();
        return bindings.create(FusionAccess.identity(request),project,environment,require(cmd.agentId(),"agentId"),require(cmd.agentVersionId(),"agentVersionId"),alias,require(cmd.idempotencyKey(),"idempotencyKey"),name);
    }

    public record SetDefaultCmd(String routeKind){}

    @PostMapping("/projects/{project}/agent-bindings/{bindingId}/default")
    Object setDefault(HttpServletRequest request,@PathVariable String project,@PathVariable String bindingId,@RequestParam(defaultValue="development") String environment,@RequestBody SetDefaultCmd cmd){
        FusionAccess.project(request,project,true);FusionController.check(project,environment);
        bindings.setDefault(project,environment,require(bindingId,"bindingId"),require(cmd.routeKind(),"routeKind"));
        return bindings.list(project,environment);
    }

    @DeleteMapping("/projects/{project}/agent-bindings/{bindingId}")
    Object unbind(HttpServletRequest request,@PathVariable String project,@PathVariable String bindingId,@RequestParam(defaultValue="development") String environment){
        FusionAccess.project(request,project,true);FusionController.check(project,environment);
        bindings.unbind(project,environment,require(bindingId,"bindingId"));
        return bindings.list(project,environment);
    }

    static String require(String value,String field){if(value==null||value.isBlank())throw new IllegalArgumentException(field+" 不能为空");return value;}
}
