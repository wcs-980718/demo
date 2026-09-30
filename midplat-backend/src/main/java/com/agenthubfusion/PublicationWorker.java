package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Component
public class PublicationWorker {
 private final JdbcTemplate db;private final DeploymentService service;private final EffectiveConfigCompiler compiler;private final TransactionTemplate tx;
 public PublicationWorker(JdbcTemplate db,DeploymentService service,EffectiveConfigCompiler compiler,PlatformTransactionManager manager){this.db=db;this.service=service;this.compiler=compiler;this.tx=new TransactionTemplate(manager);}
 @Scheduled(fixedDelayString="${fusion.publication-poll-ms:1000}") public void poll(){
  var ids=db.queryForList("select id from fusion_publication where status='queued' or (status='validating' and lease_until<?) order by created_at limit 10",String.class,OffsetDateTime.now(ZoneOffset.UTC));
  for(String id:ids)process(id);
 }
 public void process(String id){
  String lease=UUID.randomUUID().toString();
  Map<String,Object> job=tx.execute(status->{
   int claimed=db.update("update fusion_publication set status='validating',lease_token=?,lease_until=? where id=? and (status='queued' or (status='validating' and lease_until<?))",lease,OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(60),id,OffsetDateTime.now(ZoneOffset.UTC));
   return claimed==0?null:db.queryForMap("select p.*,d.project_id,d.environment,d.definition_id from fusion_publication p join fusion_deployment d on d.id=p.deployment_id where p.id=?",id);
  });
  if(job==null)return;
  String deployment=(String)job.get("deployment_id");Context context=new Context((String)job.get("principal"),(String)job.get("project_id"),(String)job.get("environment"),true);
  try{
   JsonNode draft=service.parse((String)job.get("draft"));
   // The external catalog call happens after the lease transaction has committed.
   JsonNode snapshot=compiler.compile(context.projectId(),context.environment(),deployment,(String)job.get("definition_id"),draft);
   if(job.get("restored_from")!=null){
    var old=db.queryForMap("select snapshot,content_hash from fusion_release where id=? and deployment_id=?",job.get("restored_from"),deployment);
    String oldBody=(String)old.get("snapshot");if(!EffectiveConfigCompiler.hash(oldBody).equals(old.get("content_hash")))throw new FusionFault(500,"Historical release checksum failed");
    snapshot=service.parse(oldBody);
   }
   String body=service.encode(snapshot);String hash=EffectiveConfigCompiler.hash(body);
   tx.executeWithoutResult(status->{
    db.queryForObject("select id from fusion_deployment where id=? for update",String.class,deployment);
    var current=db.queryForMap("select status,lease_token from fusion_publication where id=? for update",id);
    if(!"validating".equals(current.get("status"))||!lease.equals(current.get("lease_token")))return;
    long seq=db.queryForObject("select coalesce(max(sequence),0)+1 from fusion_release where deployment_id=?",Long.class,deployment);
    String release=UUID.randomUUID().toString();
    db.update("insert into fusion_release(id,deployment_id,sequence,publication_id,note,restored_from,content_hash,snapshot) values(?,?,?,?,?,?,?,?)",release,deployment,seq,id,job.get("note"),job.get("restored_from"),hash,body);
    db.update("update fusion_publication set status='ready',release_id=?,lease_until=null,error=null where id=?",release,id);
    // Configuration publication is intentionally distinct from runtime activation.
    db.update("update fusion_deployment set published_release_id=?,pending_job_id=null where id=? and pending_job_id=?",release,deployment,id);
    service.audit(context,deployment,"configuration.published",release);
    db.update("insert into fusion_outbox(id,aggregate_id,event_type,payload) values(?,?,?,?)",UUID.randomUUID().toString(),deployment,"configuration.published",service.encode(Map.of("releaseId",release,"hash",hash,"publicationId",id)));
   });
  }catch(Exception e){
   String message=e instanceof FusionFault?e.getMessage():"Publication failed; previous configuration retained";
   tx.executeWithoutResult(status->{
    db.queryForObject("select id from fusion_deployment where id=? for update",String.class,deployment);
    int failed=db.update("update fusion_publication set status='failed',error=?,lease_until=null where id=? and status='validating' and lease_token=?",message.substring(0,Math.min(500,message.length())),id,lease);
    if(failed>0){db.update("update fusion_deployment set pending_job_id=null where id=? and pending_job_id=?",deployment,id);service.audit(context,deployment,"publication.failed",id);}
   });
  }
 }
}
