package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.PlatformCredentialService.CallerPlatform;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;

/** Original platforms retain tools and retrieval; publication owns model selection and task rules. */
@Service
public class BusinessModelService {
    private final FusionRuntimeBridge bridge;
    private final ModelInvocationGateway gateway;
    public BusinessModelService(FusionRuntimeBridge bridge, ModelInvocationGateway gateway) {
        this.bridge=bridge;this.gateway=gateway;
    }
    /** W2: clientId rides into the agent-side run/session ownership space; configuration itself is project-scoped. */
    public Object configuration(ManagedPlatform project, String clientId) {
        var configuration=bridge.configuration(project.getId(),clientId);
        JsonNode snapshot=configuration.release().path("snapshot");
        List<Map<String,Object>> tasks=new ArrayList<>();
        for(JsonNode task:snapshot.path("tasks")) {
            JsonNode model=snapshot.path("models").path(task.path("modelRevisionId").asText()).path("content");
            tasks.add(Map.of("key",task.path("key").asText(),"name",task.path("name").asText(),
                "model",model.path("model").asText(),"modelRevisionId",task.path("modelRevisionId").asText(),
                "systemInstructions",task.path("systemInstructions"),"supportsBusinessCalls",task.path("nodes").isEmpty()&&task.path("assetRevisionIds").isEmpty()));
        }
        return Map.of("projectId",project.getId(),"clientId",clientId,"releaseId",configuration.release().path("id").asText(),
            "releaseSequence",configuration.release().path("sequence").asInt(),"tasks",tasks);
    }
    public void chat(ManagedPlatform project,String clientId,String task,String key,JsonNode request,HttpServletResponse response) throws IOException {
        chat(project,clientId,task,key,request,response,null,null);
    }
    public void chat(ManagedPlatform project,String clientId,String task,String key,JsonNode request,HttpServletResponse response,Long deadlineMillis,Long timeoutMillis) throws IOException {
        if(timeoutMillis!=null) {
            long localDeadline=gateway.deadlineAfter(timeoutMillis);
            deadlineMillis=deadlineMillis==null?localDeadline:Math.min(deadlineMillis,localDeadline);
        }
        // 在创建运行记录前拒绝无效或已经过期的业务请求。
        if(deadlineMillis!=null)gateway.remainingTimeoutMs(deadlineMillis);
        FusionController.identifier(task);
        if(key==null||key.isBlank())throw new IllegalArgumentException("缺少 X-Request-Id，请为一次业务调用使用固定请求标识");
        FusionController.identifier(key);
        ObjectNode payload=businessPayload(request);
        // Validate before reserving an audited run or contacting any provider.
        gateway.payload(payload,"validation");
        CallerPlatform caller=new CallerPlatform(project,clientId,null);
        JsonNode started=bridge.command(caller,"startBusinessRun",Map.of("taskKey",task,"idempotencyKey",key,"request",request));
        String run=started.path("runId").asText();
        response.setHeader("X-Agent-Run-Id",run);response.setHeader("X-Agent-Release-Id",started.path("releaseId").asText());
        String status="failed",error="业务模型调用中断",output="";JsonNode usage=null;
        try {
            applyPublishedTask(payload,started);
            ModelInvocationGateway.Result result=deadlineMillis==null
                ?gateway.invoke("Bearer "+started.path("ticket").asText(),payload,response)
                :gateway.invoke("Bearer "+started.path("ticket").asText(),payload,response,deadlineMillis);
            status=result.status();error=result.error();output=result.output();usage=result.usage();
        } catch(RuntimeException failure) {
            error=failure instanceof FusionUpstreamFault||failure instanceof IllegalArgumentException||failure instanceof com.yiwei.midplat.common.api.ConflictException
                ?failure.getMessage():"业务模型调用未完成";
            throw failure;
        } finally {
            ObjectNode finished=JsonNodeFactory.instance.objectNode().put("id",run).put("status",status).put("output",output);
            if(error!=null)finished.put("error",error);if(usage!=null)finished.set("usage",usage);
            // The gateway commits its own audit first. A lost management connection expires this run as unknown.
            boolean interrupted=Thread.interrupted();
            try{bridge.command(caller,"finishBusinessRun",finished);}
            catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).error("Business run {} completion could not be persisted",run,failure);}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    static ObjectNode businessPayload(JsonNode request) {
        if(!request.isObject())throw new IllegalArgumentException("模型请求必须为对象");
        if(request.has("nodeId")||request.has("sequence"))throw new IllegalArgumentException("业务请求不能选择执行节点或调用轮次");
        ObjectNode payload=(ObjectNode)request.deepCopy();payload.remove("model");payload.put("nodeId","task").put("sequence",0);
        return payload;
    }
    static void applyPublishedTask(ObjectNode payload,JsonNode started) {
        JsonNode task=started.path("task");
        ArrayNode messages=JsonNodeFactory.instance.arrayNode();StringJoiner businessRules=new StringJoiner("\n\n");
        for(JsonNode message:payload.path("messages")) {
            if(Set.of("system","developer").contains(message.path("role").asText()))businessRules.add(message.path("content").asText());
        }
        StringJoiner published=new StringJoiner("\n\n");task.path("systemInstructions").forEach(rule->published.add(rule.asText()));
        String rules=businessRules.length()==0?published.toString():"原平台业务执行上下文：\n"+businessRules+"\n\n已发布的任务规则（优先于业务上下文）：\n"+published;
        messages.addObject().put("role","system").put("content",rules);
        for(JsonNode message:payload.path("messages"))if(!Set.of("system","developer").contains(message.path("role").asText()))messages.add(message);
        payload.set("messages",messages);payload.put("temperature",started.path("temperature").asDouble(0.4));
        if(task.path("responseFormat").asText().equals("json_object"))payload.putObject("response_format").put("type","json_object");
        if(payload.path("stream").asBoolean())payload.putObject("stream_options").put("include_usage",true);
    }
}
