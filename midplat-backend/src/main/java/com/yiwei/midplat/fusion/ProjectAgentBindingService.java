package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yiwei.midplat.common.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * W4 项目分配服务：一个项目可绑定同一智能体的多个版本实例（alias 区分），
 * 旧协议默认分配由 midplat_project_runtime_default 显式指向，不自动抢占。
 * 与旧 bind 流程相同：任何数据库事务都不得跨越对智能体服务的远程请求。
 */
@Service
public class ProjectAgentBindingService {
    // 目前只有 legacy-agent-runs 在 FusionState.binding() 有解析消费方；其余协议接入解析前显式拒绝，避免存而不读。
    private static final Set<String> ROUTE_KINDS=Set.of("legacy-agent-runs");
    private final JdbcTemplate db;private final FusionAgentClient agent;private final FusionState state;private final FusionCatalog catalog;
    public ProjectAgentBindingService(JdbcTemplate db,FusionAgentClient agent,FusionState state,FusionCatalog catalog){this.db=db;this.agent=agent;this.state=state;this.catalog=catalog;}

    public List<Map<String,Object>> list(String project,String env){return state.bindingsOf(project,env);}

    /** 分配预检只检查配置完整、固定修订存在和资源可用，不读取或写入项目资源授权。 */
    public Map<String,Object> preflight(FusionAccess.Identity who,String project,String env,String agentId,String versionId){
        FusionController.identifier(agentId);FusionController.identifier(versionId);
        JsonNode content=versionContent(who,agentId,versionId);
        Set<String> missing=new TreeSet<>(state.configurationIssues(content));Set<String> models=new TreeSet<>();Set<String> prompts=new TreeSet<>();
        if(!content.path("tasks").isArray()||content.path("tasks").isEmpty())missing.add("智能体版本没有可执行任务");
        String base=content.path("defaultModelRevisionId").asText();
        if(!base.isBlank())models.add(base);
        for(JsonNode task:content.path("tasks")){
            String model=task.path("modelRevisionId").asText();if(!model.isBlank())models.add(model);
            for(JsonNode node:task.path("nodes")){String nodeModel=node.path("modelRevisionId").asText();if(!nodeModel.isBlank())models.add(nodeModel);}
        }
        if(content.path("role").path("kind").asText().equals("template"))prompts.add(content.path("role").path("revisionId").asText());
        var tasks=new ArrayList<String>();
        for(JsonNode task:content.path("tasks"))tasks.add(task.path("key").asText());
        return Map.of("ready",missing.isEmpty(),"missing",missing,"agentId",agentId,"agentVersionId",versionId,"tasks",tasks,"modelRevisions",models,"promptRevisions",prompts);
    }

    /** 创建分配：PENDING → 智能体侧按 bindingId 幂等建部署 → READY。失败保留可恢复状态与原因。 */
    public Map<String,Object> create(FusionAccess.Identity who,String project,String env,String agentId,String versionId,String alias,String operation,String name){
        FusionController.check(project,env);FusionController.identifier(agentId);FusionController.identifier(versionId);FusionController.identifier(operation);
        if(alias==null||!alias.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("调用别名格式无效");
        JsonNode content=versionContent(who,agentId,versionId);
        if(!content.path("tasks").isArray()||content.path("tasks").isEmpty())throw new IllegalArgumentException("智能体版本没有可执行任务");
        state.validateAgentConfiguration(content);
        Map<String,Object> binding;
        var restorable=db.queryForList("select id from midplat_project_agent_binding where project_id=? and environment=? and agent_id=? and desired_agent_version_id=? and status='UNBOUND'",String.class,project,env,agentId,versionId);
        if(!restorable.isEmpty())binding=state.reviveBinding(project,env,restorable.get(0),alias,operation);
        else binding=state.beginBinding(project,env,agentId,versionId,alias,operation);
        if("ready".equals(binding.get("status")))return binding;
        String bindingId=(String)binding.get("binding_id");String deployment=(String)binding.get("deployment_id");
        try{
            agent.call(who,project,env,deployment,"createForBinding",Map.of("bindingId",bindingId,"agentId",agentId,"agentVersionId",versionId,"name",name));
        }catch(RuntimeException failure){
            state.recordBindingError(bindingId,failure.getMessage());
            throw failure;
        }
        state.completeBinding(project,env,deployment);
        return state.bindingById(bindingId);
    }

    /** 解除分配：分配置 UNBOUND 并释放默认路由与别名；部署、草稿、版本与运行历史保留在智能体服务，重新绑定同版本可恢复。 */
    public void unbind(String project,String env,String bindingId){
        FusionController.check(project,env);FusionController.identifier(bindingId);
        var rows=db.queryForList("select id from midplat_project_agent_binding where id=? and project_id=? and environment=?",String.class,bindingId,project,env);
        if(rows.isEmpty())throw new ResourceNotFoundException("分配不存在");
        state.unbind(project,env,bindingId);
    }

    /**
     * 物理删除独立智能体：要求全部分配均已 UNBOUND；智能体侧级联删除部署/版本/草稿/历史，
     * 中台侧随后清除默认路由与分配行。与旧流程一致：远程调用不与本地事务同范围。
     */
    public JsonNode deleteAgent(FusionAccess.Identity who,String agentId){
        FusionController.identifier(agentId);
        var rows=db.queryForList("select id,project_id,environment,status,deployment_id from midplat_project_agent_binding where agent_id=?",agentId);
        var active=rows.stream().filter(r->!"UNBOUND".equals(r.get("status"))).toList();
        if(!active.isEmpty()){
            // 绑定可能位于当前界面未选中的环境；列出项目/环境/状态便于定位解绑。
            var blockers=active.stream().map(r->r.get("project_id")+"("+r.get("environment")+","+String.valueOf(r.get("status")).toLowerCase(Locale.ROOT)+")").toList();
            throw new ConflictException("仍存在生效中的项目绑定，请先解绑后再删除智能体："+String.join("、",blockers));
        }
        var bindingIds=rows.stream().map(r->(String)r.get("id")).toList();
        var deploymentIds=rows.stream().map(r->(String)r.get("deployment_id")).toList();
        JsonNode result;
        try{
            result=agent.callAgent(who,"deleteAgent",Map.of("agentId",agentId,"bindingIds",bindingIds,"deploymentIds",deploymentIds));
        }catch(FusionUpstreamFault fault){
            // 上次调用可能已完成远端删除但本地清理失败；404 视为已删除，继续收敛本地行保证可重试。
            if(fault.status()!=404)throw fault;
            var fallback=JsonNodeFactory.instance.objectNode();
            fallback.put("deleted",agentId);fallback.put("deployments",0);fallback.put("note","智能体侧记录不存在，仅清理本地分配数据");
            result=fallback;
        }
        // 中台侧写操作统一走 FusionState 事务方法（连接池 auto-commit=false，裸 db.update 会随连接归还回滚）。
        state.removeAgentBindings(agentId,bindingIds);
        return result;
    }

    public void setDefault(String project,String env,String bindingId,String routeKind){
        FusionController.check(project,env);FusionController.identifier(bindingId);
        if(!ROUTE_KINDS.contains(routeKind))throw new IllegalArgumentException("不支持的路由协议");
        var rows=db.queryForList("select status from midplat_project_agent_binding where id=? and project_id=? and environment=?",bindingId,project,env);
        if(rows.isEmpty())throw new ResourceNotFoundException("分配不存在");
        if(!"READY".equals(rows.get(0).get("status")))throw new ConflictException("只有就绪的分配才能设为默认路由");
        state.setDefaultBinding(project,env,routeKind,bindingId);
    }

    private JsonNode versionContent(FusionAccess.Identity who,String agentId,String versionId){
        var versions=agent.callAgent(who,"agentVersions",Map.of("agentId",agentId));
        for(JsonNode version:versions)if(versionId.equals(version.path("id").asText()))return version.path("content");
        throw new ResourceNotFoundException("智能体版本不存在");
    }
}
