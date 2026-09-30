package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One original-platform model turn, pinned to the active task and audited beside managed runs. */
@Service
public class BusinessRunService {
    private final JdbcTemplate db;
    private final DeploymentService deployments;
    private final RunService runs;
    private final ExecutionSigner signer;
    private final TransactionTemplate tx;
    public BusinessRunService(JdbcTemplate db, DeploymentService deployments, RunService runs,
                              ExecutionSigner signer, PlatformTransactionManager manager) {
        this.db=db; this.deployments=deployments; this.runs=runs; this.signer=signer;
        tx=new TransactionTemplate(manager);
    }
    private void serviceIdentity(Context context) {
        if(!context.writable() || !("service:"+context.projectId()).equals(context.principal()))
            throw new FusionFault(403,"业务模型调用必须使用所属项目的服务身份");
        if(!signer.enabled()) throw new FusionFault(503,"执行服务未启用");
    }
    public Object start(Context context, String deployment, JsonNode body) {
        serviceIdentity(context);
        String key=DeploymentService.text(body,"idempotencyKey",64);
        String taskKey=DeploymentService.text(body,"taskKey",64);
        JsonNode request=body.path("request");
        if(!request.isObject() || request.toString().length()>300000)
            throw new FusionFault(422,"业务模型请求格式或大小无效");
        String digest=EffectiveConfigCompiler.hash(deployments.encode(EffectiveConfigCompiler.canonical(body)));
        String clientId=context.ownerClientId(),owner=clientId.isEmpty()?null:clientId;
        return tx.execute(status->{
            db.queryForObject("select id from fusion_deployment where id=? for update",String.class,deployment);
            Deployment current=deployments.get(context,deployment);
            var prior=clientId.isEmpty()
                ?db.queryForList("select id,request_hash from fusion_run where deployment_id=? and principal=? and request_key=? and client_id is null",deployment,context.principal(),key)
                :db.queryForList("select id,request_hash from fusion_run where deployment_id=? and principal=? and request_key=? and client_id=?",deployment,context.principal(),key,owner);
            if(!prior.isEmpty()) throw new FusionFault(409,digest.equals(prior.get(0).get("request_hash"))
                ? "该业务请求已受理，禁止重复执行；运行 ID："+prior.get(0).get("id") : "同一业务请求标识对应不同内容");
            if(current.activeReleaseId()==null) throw new FusionFault(409,"项目尚未上线智能体版本");
            Release release=deployments.release(context,deployment,current.activeReleaseId());
            JsonNode task=RunService.task(release.snapshot(),taskKey);
            if(!task.path("nodes").isEmpty() || !task.path("assetRevisionIds").isEmpty())
                throw new FusionFault(422,"原平台模型接口要求单步任务；带节点或能力资产的流程请使用运行接口");
            if(db.queryForObject("select count(*) from fusion_run where deployment_id=? and status in ('queued','running','cancelling')",Long.class,deployment)>=16)
                throw new FusionFault(429,"当前项目运行队列已满");
            String run=UUID.randomUUID().toString(), session=UUID.randomUUID().toString();
            var expires=OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(11);
            db.update("insert into fusion_session(id,deployment_id,release_id,principal,task_key,status,expires_at,client_id) values(?,?,?,?,?,'closed',?,?)",session,deployment,release.id(),context.principal(),taskKey,expires,owner);
            db.update("insert into fusion_run(id,deployment_id,release_id,session_id,principal,task_key,input,status,request_key,request_hash,origin,started_at,lease_until,client_id) values(?,?,?,?,?,?,?,'running',?,?,'business',CURRENT_TIMESTAMP,?,?)",
                run,deployment,release.id(),session,context.principal(),taskKey,request.path("messages").toString(),key,digest,expires,owner);
            deployments.audit(context,deployment,"business.run.created",run);
            ObjectNode event=JsonNodeFactory.instance.objectNode().put("releaseId",release.id()).put("taskKey",taskKey).put("origin","business");
            runs.append(run,"started",event);
            return Map.of("runId",run,"releaseId",release.id(),"task",task,"ticket",signer.sign(context,deployment,release.id(),run,task),
                "temperature",release.snapshot().path("draft").path("temperature").asDouble(0.4));
        });
    }
    public Object finish(Context context, String deployment, JsonNode body) {
        serviceIdentity(context);
        String id=DeploymentService.text(body,"id",64), clientId=context.ownerClientId();
        runs.get(context,deployment,id);
        long owned=clientId.isEmpty()
            ?db.queryForObject("select count(*) from fusion_run where id=? and origin='business' and principal=?",Long.class,id,context.principal())
            :db.queryForObject("select count(*) from fusion_run where id=? and origin='business' and client_id=?",Long.class,id,clientId);
        if(owned!=1)
            throw new FusionFault(404,"业务运行不存在");
        String status=body.path("status").asText();
        if(!Set.of("succeeded","failed","cancelled").contains(status)) throw new FusionFault(422,"业务运行结束状态无效");
        if(body.hasNonNull("usage")) runs.append(id,"usage",JsonNodeFactory.instance.objectNode().set("usage",body.path("usage")));
        ObjectNode payload=JsonNodeFactory.instance.objectNode().put("output",body.path("output").asText());
        if(!status.equals("succeeded")) payload.put("detail",body.path("error").asText("业务模型调用未完成"));
        runs.append(id,status.equals("succeeded")?"completed":status.equals("cancelled")?"cancelled":"failed",payload);
        return runs.get(context,deployment,id);
    }
}
