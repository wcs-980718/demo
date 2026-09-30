package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class ActivationService {
    private final DeploymentService service;
    private final EffectiveConfigCompiler compiler;
    private final RuntimeUnits units;
    private final ExecutionSigner signer;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public ActivationService(DeploymentService service,EffectiveConfigCompiler compiler,RuntimeUnits units,ExecutionSigner signer,JdbcTemplate db,PlatformTransactionManager manager){this.service=service;this.compiler=compiler;this.units=units;this.signer=signer;this.db=db;tx=new TransactionTemplate(manager);}
    public Object activate(Context context,String deployment,JsonNode body){
        if(!signer.enabled())throw new FusionFault(503,"执行服务未启用");
        Deployment d=service.get(context,deployment);String release=DeploymentService.text(body,"releaseId",64);
        Release selected=service.release(context,deployment,release);
        if(!body.path("expectedActivationRevision").isIntegralNumber())throw new FusionFault(422,"缺少运行版本修订号");
        long expected=body.path("expectedActivationRevision").asLong();
        if(d.activationRevision()!=expected)throw new FusionFault(409,"运行版本已变化，请刷新后重试");
        // Authorization and process preparation occur outside the pointer transaction.
        compiler.compile(context.projectId(),context.environment(),deployment,d.definitionId(),selected.snapshot().path("draft"));
        try{
            units.prepare(release,selected.hash(),selected.snapshot());
            return tx.execute(status->{
                db.queryForObject("select id from fusion_deployment where id=? for update",String.class,deployment);
                Deployment current=service.get(context,deployment);
                if(current.activationRevision()!=expected)throw new FusionFault(409,"另一条上线操作已经生效，请刷新");
                int updated=db.update("update fusion_runtime_unit set status='ready',error=null,prepared_at=CURRENT_TIMESTAMP where release_id=?",release);
                if(updated==0)db.update("insert into fusion_runtime_unit(release_id,deployment_id,content_hash,status,prepared_at,runtime_version) values(?,?,?,'ready',CURRENT_TIMESTAMP,'fusion-runtime-v1')",release,deployment,selected.hash());
                db.update("update fusion_deployment set active_release_id=?,activation_revision=activation_revision+1 where id=?",release,deployment);
                String id=UUID.randomUUID().toString();
                db.update("insert into fusion_activation(id,deployment_id,release_id,previous_release_id,revision,principal) values(?,?,?,?,?,?)",id,deployment,release,current.activeReleaseId(),expected+1,context.principal());
                service.audit(context,deployment,"runtime.activated",release);
                db.update("insert into fusion_outbox(id,aggregate_id,event_type,payload) values(?,?,?,?)",id,deployment,"runtime.activated",service.encode(Map.of("releaseId",release,"hash",selected.hash(),"activationRevision",expected+1)));
                return service.get(context,deployment);
            });
        }catch(FusionFault e){
            service.audit(context,deployment,"runtime.activation.failed",release);
            throw e;
        }
    }
    public List<Map<String,Object>> history(Context context,String deployment){service.get(context,deployment);return db.queryForList("select id,release_id,previous_release_id,revision,principal,created_at from fusion_activation where deployment_id=? order by revision desc",deployment);}
}
