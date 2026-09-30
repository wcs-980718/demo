package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * W4 独立智能体：Agent 草稿与不可变 AgentVersion 可以脱离业务项目存在。
 * 蓝图校验复用 DeploymentService.validateDraft；项目执行编译仍发生在 binding 创建的 deployment 上。
 */
@Service
public class AgentService {
    private final JdbcTemplate db;private final ObjectMapper json;private final DeploymentService deployments;
    public AgentService(JdbcTemplate db,ObjectMapper json,DeploymentService deployments){this.db=db;this.json=json;this.deployments=deployments;}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception e){throw new FusionFault(500,"智能体数据损坏");}}

    public List<Map<String,Object>> list(){
        return db.query("select a.*,v.sequence as version_sequence from fusion_agent a left join fusion_agent_version v on v.id=a.latest_version_id order by a.updated_at desc,a.name",
            (rs,n)->{Map<String,Object> row=new LinkedHashMap<>();
                row.put("id",rs.getString("id"));row.put("name",rs.getString("name"));row.put("description",rs.getString("description"));
                row.put("status",rs.getString("status"));row.put("draftRevision",rs.getLong("draft_revision"));
                row.put("latestVersionId",rs.getString("latest_version_id"));
                long sequence=rs.getLong("version_sequence");row.put("latestVersionSequence",rs.wasNull()?null:(Object)sequence);
                row.put("sourceProjectId",rs.getString("source_project_id"));row.put("portable",rs.getBoolean("portable"));
                row.put("updatedAt",rs.getString("updated_at"));return row;});
    }

    @Transactional public Map<String,Object> create(Context c,JsonNode body){
        String id=DeploymentService.text(body,"idempotencyKey",64);DeploymentService.identifier(id);
        String name=DeploymentService.text(body,"name",128);
        String description=body.path("description").asText("").trim();if(description.length()>500)throw new FusionFault(422,"描述过长");
        String taskKey=body.path("taskKey").asText("main"),taskName=body.path("taskName").asText(name);
        var prior=db.queryForList("select name from fusion_agent where id=?",id);
        if(!prior.isEmpty()){
            if(!name.equals(prior.get(0).get("name")))throw new FusionFault(409,"智能体创建幂等键对应不同名称");
            return view(id);
        }
        ObjectNode draft=json.createObjectNode().put("defaultModelRevisionId","").put("temperature",0.4);
        draft.set("role",json.createObjectNode().put("kind","inline").put("body",""));
        draft.putArray("tasks").addObject().put("key",taskKey).put("name",taskName).put("instructions","").putArray("nodes");
        deployments.validateDraft(draft,false);
        // 独立 Agent 不携带业务项目；source_project_id 仅迁移回填时由 SQL 写入。
        db.update("insert into fusion_agent(id,name,description,owner_subject,draft,draft_revision,portable) values(?,?,?,?,?,0,false)",
            id,name,description,c==null?"":c.principal(),deployments.encode(draft));
        mirrorDefinition(id,name,deployments.encode(draft));
        audit(c,id,"agent.created",id);
        return view(id);
    }

    @Transactional public Map<String,Object> update(Context c,String agentId,JsonNode body){
        DeploymentService.identifier(agentId);
        String name=DeploymentService.text(body,"name",128);
        String description=body.path("description").asText("").trim();if(description.length()>500)throw new FusionFault(422,"描述过长");
        int updated=db.update("update fusion_agent set name=?,description=?,updated_at=CURRENT_TIMESTAMP where id=?",name,description,agentId);
        if(updated==0)throw new FusionFault(404,"智能体不存在");
        mirrorDefinition(agentId,name,(String)db.queryForObject("select draft from fusion_agent where id=?",String.class,agentId));
        audit(c,agentId,"agent.updated",name);
        return view(agentId);
    }

    public Map<String,Object> draft(String agentId){
        DeploymentService.identifier(agentId);
        var rows=db.queryForList("select draft,draft_revision,name,description,status,source_project_id,latest_version_id from fusion_agent where id=?",agentId);
        if(rows.isEmpty())throw new FusionFault(404,"智能体不存在");
        var row=rows.get(0);
        Map<String,Object> result=new LinkedHashMap<>(view(agentId));
        result.put("draft",parse((String)row.get("draft")));result.put("expectedRevision",((Number)row.get("draft_revision")).longValue());
        return result;
    }

    /** 只读发布预检提示；幂等请求内容的一致性仍由 publish 在服务端验证。 */
    public Map<String,Object> draft(String agentId,String publicationKey){
        var result=draft(agentId);
        if(publicationKey!=null&&!publicationKey.isBlank()){
            if(publicationKey.length()>64)throw new FusionFault(422,"Invalid publication key");
            result.put("publicationExists",db.queryForObject("select count(*) from fusion_agent_publication where agent_id=? and idempotency_key=?",Long.class,agentId,publicationKey)>0);
        }
        return result;
    }
    @Transactional public Map<String,Object> saveDraft(Context c,String agentId,JsonNode body){
        DeploymentService.identifier(agentId);
        if(db.queryForObject("select count(*) from fusion_agent where id=?",Long.class,agentId)==0)throw new FusionFault(404,"智能体不存在");
        long expected=expectedRevision(body);
        JsonNode draft=body.path("draft");deployments.validateDraft(draft,false);deployments.validateRequiredConfiguration(draft);
        int updated=db.update("update fusion_agent set draft=?,draft_revision=draft_revision+1,updated_at=CURRENT_TIMESTAMP where id=? and draft_revision=?",deployments.encode(draft),agentId,expected);
        if(updated==0)throw new FusionFault(409,"智能体草稿已更新，请刷新后重试");
        mirrorDefinition(agentId,(String)db.queryForObject("select name from fusion_agent where id=?",String.class,agentId),deployments.encode(draft));
        audit(c,agentId,"agent.draft.saved",Long.toString(expected+1));
        return draft(agentId);
    }

    @Transactional public Map<String,Object> publish(Context c,String agentId,JsonNode body){
        DeploymentService.identifier(agentId);
        String key=DeploymentService.text(body,"idempotencyKey",64);String note=body.path("note").asText("").trim();
        if(note.length()>500)throw new FusionFault(422,"发布说明过长");
        long expected=expectedRevision(body);
        String requestHash=EffectiveConfigCompiler.hash(deployments.encode(body));
        var prior=db.queryForList("select id,request_hash,version_id,status from fusion_agent_publication where agent_id=? and idempotency_key=?",agentId,key);
        if(!prior.isEmpty()){
            if(!requestHash.equals(prior.get(0).get("request_hash")))throw new FusionFault(409,"发布幂等键对应不同请求");
            Map<String,Object> result=draft(agentId);
            result.put("publication",Map.of("id",prior.get(0).get("id"),"versionId",prior.get(0).get("version_id")==null?"":prior.get(0).get("version_id"),"status",prior.get(0).get("status")));
            return result;
        }
        var agents=db.queryForList("select draft,draft_revision from fusion_agent where id=? for update",agentId);
        if(agents.isEmpty())throw new FusionFault(404,"智能体不存在");
        JsonNode draft=parse((String)agents.get(0).get("draft"));
        long revision=((Number)agents.get(0).get("draft_revision")).longValue();
        if(revision!=expected)throw new FusionFault(409,"智能体草稿已更新，请刷新后重新发布");
        deployments.validateDraft(draft,true);
        long sequence=1+db.queryForObject("select coalesce(max(sequence),0) from fusion_agent_version where agent_id=?",Long.class,agentId);
        String versionId="av-"+agentId+"-"+sequence;
        if(versionId.length()>64)versionId=UUID.randomUUID().toString();
        String encoded=deployments.encode(draft);
        db.update("insert into fusion_agent_version(id,agent_id,sequence,content,content_hash,created_by,note) values(?,?,?,?,?,?,?)",
            versionId,agentId,sequence,encoded,EffectiveConfigCompiler.hash(encoded),c==null?"":c.principal(),note);
        db.update("update fusion_agent set latest_version_id=?,updated_at=CURRENT_TIMESTAMP where id=?",versionId,agentId);
        String publication=UUID.randomUUID().toString();
        db.update("insert into fusion_agent_publication(id,agent_id,idempotency_key,request_hash,version_id,status) values(?,?,?,?,?,'ready')",publication,agentId,key,requestHash,versionId);
        mirrorDefinition(agentId,(String)db.queryForObject("select name from fusion_agent where id=?",String.class,agentId),encoded);
        audit(c,agentId,"agent.version.published",versionId);
        Map<String,Object> result=draft(agentId);
        result.put("publication",Map.of("id",publication,"versionId",versionId,"status","ready"));
        return result;
    }

    /**
     * 物理删除智能体及其全部分配部署数据。中台侧保证所有绑定已 UNBOUND，
     * 这里再校验每个 deployment 的 binding_id 属于调用方给出的已解绑集合，防止误删他人生效部署。
     */
    @Transactional public Map<String,Object> delete(Context c,String agentId,JsonNode body){
        DeploymentService.identifier(agentId);
        if(db.queryForObject("select count(*) from fusion_agent where id=?",Long.class,agentId)==0)throw new FusionFault(404,"智能体不存在");
        Set<String> bindingIds=new HashSet<>();for(JsonNode b:body.path("bindingIds"))bindingIds.add(b.asText());
        List<String> deploymentIds=new ArrayList<>();for(JsonNode d:body.path("deploymentIds"))deploymentIds.add(d.asText());
        for(String dep:deploymentIds){
            DeploymentService.identifier(dep);
            var rows=db.queryForList("select binding_id from fusion_deployment where id=?",String.class,dep);
            if(rows.isEmpty())throw new FusionFault(404,"部署不存在: "+dep);
            if(!bindingIds.contains(rows.get(0)))throw new FusionFault(409,"部署不属于已解绑分配集合: "+dep);
        }
        // 第二道防线：本智能体的全部部署都必须由调用方申报，未申报的部署说明仍有绑定残留，拒绝删除避免产生孤儿部署。
        Set<String> declared=new HashSet<>(deploymentIds);
        var undeclared=db.queryForList("select id from fusion_deployment where definition_id=? or source_agent_version_id in (select id from fusion_agent_version where agent_id=?)",String.class,agentId,agentId)
            .stream().filter(id->!declared.contains(id)).toList();
        if(!undeclared.isEmpty())throw new FusionFault(409,"智能体仍存在未解绑的部署: "+String.join(",",undeclared));
        for(String dep:deploymentIds){
            db.update("delete from fusion_tool_call where run_id in (select id from fusion_run where deployment_id=?)",dep);
            db.update("delete from fusion_run_event where run_id in (select id from fusion_run where deployment_id=?)",dep);
            db.update("delete from fusion_run where deployment_id=?",dep);
            db.update("delete from fusion_session where deployment_id=?",dep);
            db.update("delete from fusion_activation where deployment_id=?",dep);
            db.update("delete from fusion_runtime_unit where deployment_id=?",dep);
            db.update("update fusion_deployment set published_release_id=null,active_release_id=null where id=?",dep);
            db.update("delete from fusion_release where deployment_id=?",dep);
            db.update("delete from fusion_publication where deployment_id=?",dep);
            db.update("delete from fusion_audit where deployment_id=?",dep);
            db.update("delete from fusion_outbox where aggregate_id=?",dep);
            db.update("delete from fusion_deployment where id=?",dep);
        }
        db.update("delete from fusion_agent_audit where agent_id=?",agentId);
        db.update("delete from fusion_agent_publication where agent_id=?",agentId);
        db.update("delete from fusion_agent_version where agent_id=?",agentId);
        db.update("delete from fusion_agent where id=?",agentId);
        // 定义镜像仅在没有任何部署引用时删除；遗留部署引用的原始定义行保持不动。
        if(db.queryForObject("select count(*) from fusion_deployment where definition_id=?",Long.class,agentId)==0)
            db.update("delete from fusion_definition where id=?",agentId);
        return Map.of("deleted",agentId,"deployments",deploymentIds.size());
    }

    public List<Map<String,Object>> versions(String agentId){
        DeploymentService.identifier(agentId);
        if(db.queryForObject("select count(*) from fusion_agent where id=?",Long.class,agentId)==0)throw new FusionFault(404,"智能体不存在");
        return db.query("select id,sequence,content,content_hash,note,created_by,created_at from fusion_agent_version where agent_id=? order by sequence desc",
            (rs,n)->{Map<String,Object> row=new LinkedHashMap<>();
                row.put("id",rs.getString("id"));row.put("agentId",agentId);row.put("sequence",rs.getLong("sequence"));
                String content=rs.getString("content");
                String hash=rs.getString("content_hash");if(hash==null||hash.isBlank())hash=EffectiveConfigCompiler.hash(content);
                row.put("hash",hash);row.put("note",rs.getString("note"));row.put("createdBy",rs.getString("created_by"));row.put("createdAt",rs.getString("created_at"));
                row.put("content",parse(content));return row;},agentId);
    }

    /** 供分配创建读取的不可变版本内容；不存在或非本智能体时报 404。 */
    public JsonNode versionContent(String agentId,String versionId){
        DeploymentService.identifier(agentId);DeploymentService.identifier(versionId);
        var rows=db.queryForList("select content from fusion_agent_version where id=? and agent_id=?",String.class,versionId,agentId);
        if(rows.isEmpty())throw new FusionFault(404,"智能体版本不存在");
        return parse(rows.get(0));
    }

    /** 旧协议兼容：fusion_definition 外键仍然指向定义表；智能体镜像行让旧 bind 流程可以引用独立智能体。 */
    private void mirrorDefinition(String agentId,String name,String encoded){
        int updated=db.update("update fusion_definition set name=?,content=? where id=?",name,encoded,agentId);
        if(updated==0)db.update("insert into fusion_definition(id,name,content) values(?,?,?)",agentId,name,encoded);
    }

    private long expectedRevision(JsonNode body){
        if(!body.path("expectedRevision").isIntegralNumber())throw new FusionFault(422,"缺少草稿修订号");
        return body.path("expectedRevision").asLong();
    }

    private Map<String,Object> view(String agentId){
        var rows=db.queryForList("select id,name,description,status,draft_revision,latest_version_id,source_project_id,portable,updated_at from fusion_agent where id=?",agentId);
        if(rows.isEmpty())throw new FusionFault(404,"智能体不存在");
        var row=rows.get(0);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",row.get("id"));result.put("name",row.get("name"));result.put("description",row.get("description"));
        result.put("status",row.get("status"));result.put("draftRevision",((Number)row.get("draft_revision")).longValue());
        result.put("latestVersionId",row.get("latest_version_id"));result.put("sourceProjectId",row.get("source_project_id"));
        result.put("portable",Boolean.TRUE.equals(row.get("portable")));result.put("updatedAt",row.get("updated_at"));
        return result;
    }

    private void audit(Context c,String agentId,String action,String target){
        String principal=c==null||c.principal()==null||c.principal().isBlank()?"unknown":c.principal();
        db.update("insert into fusion_agent_audit(id,agent_id,principal,action,target) values(?,?,?,?,?)",
            UUID.randomUUID().toString(),agentId,principal,action,target);
    }
}
