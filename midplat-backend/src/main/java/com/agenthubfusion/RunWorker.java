package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RunWorker {
    private final JdbcTemplate db;private final RunService runs;private final DeploymentService service;private final RuntimeUnits units;private final ExecutionSigner signer;
    private final String owner=UUID.randomUUID().toString();
    private final ExecutorService pool=new ThreadPoolExecutor(10,10,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20));
    private final ConcurrentMap<String,InputStream> streams=new ConcurrentHashMap<>();
    private final Set<String> live=ConcurrentHashMap.newKeySet();
    public RunWorker(JdbcTemplate db,RunService runs,DeploymentService service,RuntimeUnits units,ExecutionSigner signer){this.db=db;this.runs=runs;this.service=service;this.units=units;this.signer=signer;}
    @Scheduled(fixedDelayString="${fusion.run-poll-ms:500}") public void poll(){
        if(!signer.enabled())return;
        for(var expired:db.queryForList("select id,release_id from fusion_run where status in ('running','cancelling') and lease_owner=? and started_at<?",owner,OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10))){
            String id=(String)expired.get("id");runs.append(id,"failed",JsonNodeFactory.instance.objectNode().put("detail","运行超过 10 分钟上限，已取消外部调用"));
            units.cancel((String)expired.get("release_id"),id);InputStream input=streams.get(id);if(input!=null)try{input.close();}catch(IOException ignored){}
        }
        for(String run:live)db.update("update fusion_run set lease_until=? where id=? and lease_owner=? and status in ('running','cancelling')",OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30),run,owner);
        db.update("update fusion_run set status='failed',error='执行进程失联，结果未知；为避免重复副作用未自动重放',finished_at=CURRENT_TIMESTAMP where status in ('running','cancelling') and lease_until<CURRENT_TIMESTAMP");
        for(var row:db.queryForList("select id,release_id from fusion_run where status='cancelling' and lease_owner=?",owner))units.cancel((String)row.get("release_id"),(String)row.get("id"));
        if(live.size()>=10)return;
        for(String run:db.queryForList("select id from fusion_run where status='queued' order by created_at limit 10",String.class)){
            if(live.size()>=10)break;
            int claimed=db.update("update fusion_run set status='running',started_at=CURRENT_TIMESTAMP,lease_owner=?,lease_until=? where id=? and status='queued'",owner,OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30),run);
            if(claimed==0)continue;live.add(run);
            try{pool.submit(()->execute(run));}catch(RejectedExecutionException e){live.remove(run);db.update("update fusion_run set status='queued',lease_until=null,lease_owner=null where id=? and lease_owner=?",run,owner);}
        }
    }
    void execute(String run){
        try{
            var row=db.queryForMap("select r.*,d.project_id,d.environment from fusion_run r join fusion_deployment d on d.id=r.deployment_id where r.id=?",run);
            String deployment=(String)row.get("deployment_id"),release=(String)row.get("release_id"),clientId=(String)row.get("client_id");
            Context context=new Context((String)row.get("principal"),(String)row.get("project_id"),(String)row.get("environment"),true,clientId);
            Release selected=service.release(context,deployment,release);JsonNode task=RunService.task(selected.snapshot(),(String)row.get("task_key"));
            RuntimeUnits.Unit unit=units.prepare(release,selected.hash(),selected.snapshot());
            if(!"running".equals(db.queryForObject("select status from fusion_run where id=?",String.class,run))){runs.append(run,"cancelled",JsonNodeFactory.instance.objectNode());return;}
            String ticket=signer.sign(context,deployment,release,run,task);
            var response=units.run(unit,Map.of("runId",run,"taskKey",row.get("task_key"),"input",row.get("input"),"ticket",ticket,"history",runs.history((String)row.get("session_id"))));
            streams.put(run,response.body());
            try(InputStream input=response.body()){
                if(response.statusCode()!=200)throw new FusionFault(502,"运行单元拒绝请求："+response.statusCode());
                BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));String line,type="";boolean terminal=false;
                while((line=reader.readLine())!=null){
                    if(line.startsWith("event: "))type=line.substring(7);
                    if(line.startsWith("data: ")){
                        JsonNode payload=service.parse(line.substring(6));runs.append(run,type,payload);
                        if(Set.of("completed","failed","cancelled").contains(type))terminal=true;
                    }
                }
                if(!terminal)throw new FusionFault(502,"运行连接提前结束，结果未知");
            }
        }catch(Exception e){runs.append(run,"failed",JsonNodeFactory.instance.objectNode().put("detail",e instanceof FusionFault?e.getMessage():"运行失败或连接中断，未自动重放"));}
        finally{streams.remove(run);live.remove(run);}
    }
    @Scheduled(fixedDelay=60000) public void retire(){
        if(!signer.enabled())return;
        Set<String> retained=new HashSet<>(db.queryForList("select active_release_id from fusion_deployment where active_release_id is not null",String.class));
        retained.addAll(db.queryForList("select release_id from fusion_session where status='open' and expires_at>CURRENT_TIMESTAMP",String.class));
        retained.addAll(db.queryForList("select release_id from fusion_run where status in ('queued','running','cancelling')",String.class));
        units.retireExcept(retained);
        // Durable events are acknowledged after their in-database state is visible; delivery is idempotent.
        db.update("update fusion_outbox set delivered_at=CURRENT_TIMESTAMP where delivered_at is null");
    }
    @PreDestroy public void stop(){pool.shutdownNow();streams.values().forEach(input->{try{input.close();}catch(IOException ignored){}});}
}
