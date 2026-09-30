package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service
public class RunService {
    private final JdbcTemplate db;
    private final DeploymentService service;
    private final ExecutionSigner signer;
    private final RuntimeUnits units;
    private final TransactionTemplate tx;
    public RunService(JdbcTemplate db,DeploymentService service,ExecutionSigner signer,RuntimeUnits units,PlatformTransactionManager manager){this.db=db;this.service=service;this.signer=signer;this.units=units;tx=new TransactionTemplate(manager);}
    public Map<String,Object> start(Context context,String deployment,JsonNode body){
        if(!signer.enabled())throw new FusionFault(503,"执行服务未启用");
        if(!body.isObject())throw new FusionFault(422,"运行请求无效");
        body.fieldNames().forEachRemaining(k->{if(!Set.of("input","taskKey","sessionId","idempotencyKey").contains(k))throw new FusionFault(422,"运行请求不能覆盖已发布配置："+k);});
        String input=DeploymentService.text(body,"input",20000), key=DeploymentService.text(body,"idempotencyKey",64);
        String digest=EffectiveConfigCompiler.hash(service.encode(EffectiveConfigCompiler.canonical(body)));
        String clientId=context.ownerClientId(),owner=clientId.isEmpty()?null:clientId;
        return tx.execute(status->{
            db.queryForObject("select id from fusion_deployment where id=? for update",String.class,deployment);
            Deployment d=service.get(context,deployment);
            var prior=clientId.isEmpty()
                ?db.queryForList("select id,request_hash from fusion_run where deployment_id=? and principal=? and request_key=? and client_id is null",deployment,context.principal(),key)
                :db.queryForList("select id,request_hash from fusion_run where deployment_id=? and principal=? and request_key=? and client_id=?",deployment,context.principal(),key,owner);
            if(!prior.isEmpty()){if(!digest.equals(prior.get(0).get("request_hash")))throw new FusionFault(409,"同一运行幂等键对应不同输入");return get(context,deployment,(String)prior.get(0).get("id"));}
            String session=body.path("sessionId").asText(), release=d.activeReleaseId(), taskKey=body.path("taskKey").asText();
            if(!session.isBlank()){
                DeploymentService.identifier(session);
                var sessions=clientId.isEmpty()
                    ?db.queryForList("select * from fusion_session where id=? and deployment_id=? and principal=? and status='open' and expires_at>CURRENT_TIMESTAMP for update",session,deployment,context.principal())
                    :db.queryForList("select * from fusion_session where id=? and deployment_id=? and principal=? and client_id=? and status='open' and expires_at>CURRENT_TIMESTAMP for update",session,deployment,context.principal(),owner);
                if(sessions.isEmpty())throw new FusionFault(403,"会话不存在、已过期或不属于当前身份");
                var s=sessions.get(0);release=(String)s.get("release_id");if(!taskKey.isBlank()&&!taskKey.equals(s.get("task_key")))throw new FusionFault(409,"会话固定到原任务，切换任务请新建会话");taskKey=(String)s.get("task_key");
                if(db.queryForObject("select count(*) from fusion_run where session_id=? and status in ('queued','running','cancelling')",Long.class,session)>0)throw new FusionFault(409,"该会话已有执行中的请求");
            }
            if(release==null)throw new FusionFault(409,"请先上线一个已发布版本");
            Release selected=service.release(context,deployment,release);JsonNode task=task(selected.snapshot(),taskKey);taskKey=task.path("key").asText();
            if(session.isBlank()){
                session=UUID.randomUUID().toString();db.update("insert into fusion_session(id,deployment_id,release_id,principal,task_key,expires_at,client_id) values(?,?,?,?,?,?,?)",session,deployment,release,context.principal(),taskKey,OffsetDateTime.now(ZoneOffset.UTC).plusHours(12),owner);
            }
            // F17 最小配额：同一接入应用独占队列上限 8，避免多客户共用项目时相互占满 16 的项目队列。
            if(!clientId.isEmpty()&&db.queryForObject("select count(*) from fusion_run where deployment_id=? and client_id=? and status in ('queued','running','cancelling')",Long.class,deployment,clientId)>=8)throw new FusionFault(429,"当前接入应用运行队列已满");
            if(db.queryForObject("select count(*) from fusion_run where deployment_id=? and status in ('queued','running','cancelling')",Long.class,deployment)>=16)throw new FusionFault(429,"当前项目运行队列已满");
            String run=UUID.randomUUID().toString();
            db.update("insert into fusion_run(id,deployment_id,release_id,session_id,principal,task_key,input,status,request_key,request_hash,origin,client_id) values(?,?,?,?,?,?,?,'queued',?,?,?,?)",run,deployment,release,session,context.principal(),taskKey,input,key,digest,context.principal().equals("service:"+context.projectId())?"business":"agenthub",owner);
            service.audit(context,deployment,"run.created",run);return get(context,deployment,run);
        });
    }
    public static JsonNode task(JsonNode snapshot,String key){
        JsonNode tasks=snapshot.path("tasks");if(key.isBlank()&&tasks.size()==1)return tasks.get(0);
        for(JsonNode task:tasks)if(task.path("key").asText().equals(key))return task;
        throw new FusionFault(422,key.isBlank()?"多任务智能体必须指定 taskKey":"发布版本中不存在该任务");
    }
    public Map<String,Object> get(Context context,String deployment,String run){
        service.get(context,deployment);DeploymentService.identifier(run);String clientId=context.ownerClientId();
        List<Map<String,Object>> rows=clientId.isEmpty()
            ?db.queryForList("select id,release_id,session_id,principal,task_key,input,output,status,error,created_at,started_at,finished_at,last_event,origin from fusion_run where id=? and deployment_id=? and (principal=? or (origin='business' and principal like 'service:%' and client_id is null))",run,deployment,context.principal())
            :db.queryForList("select id,release_id,session_id,principal,task_key,input,output,status,error,created_at,started_at,finished_at,last_event,origin from fusion_run where id=? and deployment_id=? and client_id=?",run,deployment,clientId);
        if(rows.isEmpty())throw new FusionFault(404,"运行记录不存在或无权访问");return rows.get(0);
    }
    public List<Map<String,Object>> list(Context context,String deployment){service.get(context,deployment);String clientId=context.ownerClientId();
        return clientId.isEmpty()
            ?db.queryForList("select id,release_id,session_id,principal,task_key,status,error,created_at,started_at,finished_at,origin from fusion_run where deployment_id=? and (principal=? or (origin='business' and principal like 'service:%' and client_id is null)) order by created_at desc limit 100",deployment,context.principal())
            :db.queryForList("select id,release_id,session_id,principal,task_key,status,error,created_at,started_at,finished_at,origin from fusion_run where deployment_id=? and client_id=? order by created_at desc limit 100",deployment,clientId);}
    public Object events(Context context,String deployment,String run,long after){
        get(context,deployment,run);if(after<0)throw new FusionFault(422,"事件游标无效");
        return db.query("select sequence,event_type,payload,created_at from fusion_run_event where run_id=? and sequence>? order by sequence limit 200",(rs,n)->Map.of("sequence",rs.getLong(1),"type",rs.getString(2),"data",service.parse(rs.getString(3)),"createdAt",rs.getString(4)),run,after);
    }
    public Object cancel(Context context,String deployment,String run){
        var current=get(context,deployment,run);
        tx.executeWithoutResult(status->{
            db.queryForObject("select id from fusion_run where id=? for update",String.class,run);
            int queued=db.update("update fusion_run set status='cancelled',finished_at=CURRENT_TIMESTAMP where id=? and status='queued'",run);
            int live=db.update("update fusion_run set status='cancelling' where id=? and status='running'",run);
            if(queued+live>0)service.audit(context,deployment,"run.cancel.requested",run);
        });
        units.cancel((String)current.get("release_id"),run);return get(context,deployment,run);
    }
    public Object closeSession(Context context,String deployment,String session){
        service.get(context,deployment);String clientId=context.ownerClientId();
        long owned=clientId.isEmpty()
            ?db.queryForObject("select count(*) from fusion_session where id=? and deployment_id=? and principal=?",Long.class,session,deployment,context.principal())
            :db.queryForObject("select count(*) from fusion_session where id=? and deployment_id=? and client_id=?",Long.class,session,deployment,clientId);
        if(owned==0)throw new FusionFault(404,"会话不存在");
        var active=db.queryForList("select id from fusion_run where session_id=? and status in ('queued','running','cancelling')",String.class,session);for(String run:active)cancel(context,deployment,run);
        db.update("update fusion_session set status='closed' where id=?",session);return Map.of("id",session,"status","closed");
    }
    public ArrayNode history(String session){
        var rows=db.queryForList("select input,output from fusion_run where session_id=? and status='succeeded' order by created_at",session);
        ArrayNode result=JsonNodeFactory.instance.arrayNode();
        for(var row:rows){result.addObject().put("role","user").put("content",(String)row.get("input"));result.addObject().put("role","assistant").put("content",(String)row.get("output"));}
        return result;
    }
    public void append(String run,String event,JsonNode payload){
        tx.executeWithoutResult(status->{
            var row=db.queryForMap("select status,last_event,origin from fusion_run where id=? for update",run);
            if(!Set.of("running","cancelling").contains(row.get("status")))return;
            long next=((Number)row.get("last_event")).longValue()+1;
            if(next>8000)throw new FusionFault(422,"单次运行事件数量超过限制");
            db.update("insert into fusion_run_event(run_id,sequence,event_type,payload) values(?,?,?,?)",run,next,event,service.encode(payload));
            db.update("update fusion_run set last_event=? where id=?",next,run);
            if(event.equals("delta")&&payload.path("finalNode").asBoolean())db.update("update fusion_run set output=output || ? where id=?",payload.path("text").asText(),run);
            if(Set.of("completed","failed","cancelled").contains(event)){
                boolean cancelled=row.get("status").equals("cancelling")||event.equals("cancelled");String finalStatus=cancelled?"cancelled":event.equals("completed")?"succeeded":"failed";
                db.update("update fusion_run set status=?,error=?,finished_at=CURRENT_TIMESTAMP,lease_until=null where id=?",finalStatus,event.equals("failed")?payload.path("detail").asText("执行失败"):null,run);
                if(finalStatus.equals("succeeded"))db.update("update fusion_run set output=? where id=?",payload.path("output").asText(),run);
            }
        });
    }
}
