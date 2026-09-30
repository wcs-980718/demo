package com.agenthubfusion;

import com.agenthubfusion.FusionTypes.Context;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Project-owned immutable skills, tool definitions and searchable knowledge/data assets. */
@Service
public class AssetService {
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;
    private final java.util.concurrent.ScheduledExecutorService deadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fusion-tool-deadline");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy void stop(){deadlines.shutdownNow();}
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public AssetService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager){this.db=db;this.json=json;tx=new TransactionTemplate(manager);}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception e){throw new FusionFault(500,"资产数据损坏");}}
    public List<Map<String,Object>> list(Context context){return db.query("select a.*,r.content,r.content_hash from fusion_asset a join fusion_asset_revision r on r.id=a.current_revision_id where a.project_id=? and a.environment=? order by a.name",(rs,n)->Map.of("id",rs.getString("id"),"name",rs.getString("name"),"kind",rs.getString("kind"),"enabled",rs.getBoolean("enabled"),"revision",rs.getLong("revision"),"revisionId",rs.getString("current_revision_id"),"hash",rs.getString("content_hash"),"content",parse(rs.getString("content"))),context.projectId(),context.environment());}
    public Object save(Context context,JsonNode body){
        // W3：资产主数据写入退役，仅受控迁移服务身份保留通道。
        if(!context.migration())throw new FusionFault(403,"能力资产主数据已迁移至中台统一管理；智能体侧只读引用固定修订");
        String id=DeploymentService.text(body,"id",64);DeploymentService.identifier(id);
        String name=DeploymentService.text(body,"name",128),kind=DeploymentService.text(body,"kind",16);
        if(!Set.of("skill","tool","knowledge","data").contains(kind))throw new FusionFault(422,"不支持的资产类型");
        JsonNode content=body.path("content");validate(kind,content);
        if(!body.path("expectedRevision").isIntegralNumber())throw new FusionFault(422,"缺少资产修订号");
        return tx.execute(status->{
            var rows=db.queryForList("select * from fusion_asset where id=? for update",id);
            if(!rows.isEmpty()&&(!context.projectId().equals(rows.get(0).get("project_id"))||!context.environment().equals(rows.get(0).get("environment"))))throw new FusionFault(403,"资产不属于该项目环境");
            long revision=rows.isEmpty()?0:((Number)rows.get(0).get("revision")).longValue();
            if(revision!=body.path("expectedRevision").asLong())throw new FusionFault(409,"资产已更新，请刷新");
            if(!rows.isEmpty()&&!kind.equals(rows.get(0).get("kind")))throw new FusionFault(422,"资产类型不能变更");
            String revisionId=UUID.randomUUID().toString(),encoded=EffectiveConfigCompiler.canonical(content).toString();
            if(rows.isEmpty())db.update("insert into fusion_asset(id,project_id,environment,kind,name,revision,current_revision_id) values(?,?,?,?,?,1,?)",id,context.projectId(),context.environment(),kind,name,revisionId);
            else db.update("update fusion_asset set name=?,revision=revision+1,current_revision_id=? where id=?",name,revisionId,id);
            db.update("insert into fusion_asset_revision(id,asset_id,revision,content,content_hash) values(?,?,?,?,?)",revisionId,id,revision+1,encoded,EffectiveConfigCompiler.hash(encoded));
            return list(context).stream().filter(row->row.get("id").equals(id)).findFirst().orElseThrow();
        });
    }
    public Object enabled(Context context,String id,boolean enabled){
        if(!context.migration())throw new FusionFault(403,"能力资产启停已迁移至中台统一管理");
        if(db.update("update fusion_asset set enabled=? where id=? and project_id=? and environment=?",enabled,id,context.projectId(),context.environment())==0)throw new FusionFault(404,"资产不存在");return list(context);
    }
    public Object revisions(Context context,String id){
        if(db.queryForObject("select count(*) from fusion_asset where id=? and project_id=? and environment=?",Long.class,id,context.projectId(),context.environment())==0)throw new FusionFault(404,"资产不存在");
        return db.query("select * from fusion_asset_revision where asset_id=? order by revision desc",(rs,n)->Map.of("id",rs.getString("id"),"revision",rs.getLong("revision"),"hash",rs.getString("content_hash"),"content",parse(rs.getString("content"))),id);
    }
    public ObjectNode resolve(String project,String environment,Collection<String> ids){
        if(ids.size()>50)throw new FusionFault(422,"引用资产超过限制");
        ObjectNode result=json.createObjectNode();
        for(String id:new TreeSet<>(ids)){
            var rows=db.queryForList("select r.*,a.kind,a.name,a.enabled from fusion_asset_revision r join fusion_asset a on a.id=r.asset_id where r.id=? and a.project_id=? and a.environment=?",id,project,environment);
            if(rows.isEmpty()||!Boolean.TRUE.equals(rows.get(0).get("enabled")))throw new FusionFault(403,"引用资产不存在、未授权或已停用");
            var row=rows.get(0);String content=(String)row.get("content");if(!EffectiveConfigCompiler.hash(content).equals(row.get("content_hash")))throw new FusionFault(500,"资产修订摘要不匹配");
            result.set(id,json.createObjectNode().put("id",id).put("assetId",(String)row.get("asset_id")).put("name",(String)row.get("name")).put("kind",(String)row.get("kind")).put("hash",(String)row.get("content_hash")).set("content",parse(content)));
        }
        return result;
    }
    private void validate(String kind,JsonNode content){
        if(!content.isObject()||content.toString().length()>150000)throw new FusionFault(422,"资产内容必须为对象且不超过 150000 字符");
        if(kind.equals("skill")){DeploymentService.text(content,"body",100000);return;}
        String function=DeploymentService.text(content,"toolName",64);if(!function.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))throw new FusionFault(422,"工具名称格式无效");
        DeploymentService.text(content,"description",1000);
        if(Set.of("knowledge","data").contains(kind)){
            if(!content.path("documents").isArray()||content.path("documents").isEmpty()||content.path("documents").size()>200)throw new FusionFault(422,"请提供 1–200 条真实内容");
            for(JsonNode document:content.path("documents")){DeploymentService.text(document,"title",200);DeploymentService.text(document,"text",50000);}return;
        }
        uri(content.path("url").asText());
        if(!Set.of("GET","POST").contains(content.path("method").asText()))throw new FusionFault(422,"工具仅支持 GET/POST");
        if(!content.path("parameters").isObject()||!content.path("parameters").path("type").asText().equals("object"))throw new FusionFault(422,"工具需要 object 参数模式");
        if(content.has("headers")||content.has("apiKey"))throw new FusionFault(422,"不要在资产正文保存密钥；受保护工具请通过已有业务服务接入");
        if(content.path("method").asText().equals("POST")&&!content.path("readOnly").asBoolean())throw new FusionFault(422,"当前工具必须声明为只读，写操作不允许自动执行");
    }
    static URI uri(String value){
        try{URI uri=URI.create(value);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null)throw new Exception();
            for(InetAddress address:InetAddress.getAllByName(uri.getHost()))if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isMulticastAddress())throw new Exception();return uri;
        }catch(Exception e){throw new FusionFault(422,"工具地址不可用或属于禁止访问的本地/元数据地址");}
    }
    public JsonNode invoke(JsonNode claims,JsonNode request){
        String run=claims.path("runId").asText(),id=request.path("assetRevisionId").asText(),call=DeploymentService.text(request,"callId",128);
        boolean allowed=false;for(JsonNode asset:claims.path("assets"))if(id.equals(asset.asText()))allowed=true;
        if(!allowed)throw new FusionFault(403,"执行票据未授权此资产");
        if(db.queryForObject("select count(*) from fusion_run where id=? and deployment_id=? and release_id=? and principal=? and status='running'",Long.class,run,claims.path("deploymentId").asText(),claims.path("releaseId").asText(),claims.path("principal").asText())==0)throw new FusionFault(403,"运行已结束、已取消或身份不匹配");
        JsonNode asset=resolve(claims.path("projectId").asText(),claims.path("environment").asText(),List.of(id)).path(id);
        JsonNode arguments=request.path("arguments");if(!arguments.isObject()||arguments.toString().length()>20000)throw new FusionFault(422,"工具参数无效");
        String digest=EffectiveConfigCompiler.hash(request.toString());
        var prior=db.queryForList("select * from fusion_tool_call where run_id=? and call_id=?",run,call);
        if(!prior.isEmpty()){if(!digest.equals(prior.get(0).get("request_hash")))throw new FusionFault(409,"工具调用标识重复但参数不同");if("succeeded".equals(prior.get(0).get("status")))return parse((String)prior.get(0).get("result"));throw new FusionFault(409,"工具结果未确认，禁止自动重复执行");}
        db.update("insert into fusion_tool_call(run_id,call_id,asset_revision_id,request_hash,status) values(?,?,?,?,'running')",run,call,id,digest);
        try{
            JsonNode content=asset.path("content"),result;
            if(Set.of("knowledge","data").contains(asset.path("kind").asText())){
                String query=DeploymentService.text(arguments,"query",1000).toLowerCase(Locale.ROOT);
                String[] terms=query.split("\\s+");ArrayNode hits=json.createArrayNode();
                for(JsonNode document:content.path("documents")){String text=(document.path("title").asText()+" "+document.path("text").asText()).toLowerCase(Locale.ROOT);if(Arrays.stream(terms).anyMatch(text::contains)){hits.add(document);if(hits.size()>=8)break;}}
                result=json.createObjectNode().put("revisionId",id).set("documents",hits);
            }else if(asset.path("kind").asText().equals("tool")){
                validateArguments(content.path("parameters"),arguments);
                URI target=uri(content.path("url").asText());HttpRequest.Builder builder=HttpRequest.newBuilder().timeout(Duration.ofSeconds(20));
                if(content.path("method").asText().equals("GET")){
                    StringJoiner query=new StringJoiner("&");arguments.fields().forEachRemaining(entry->query.add(URLEncoder.encode(entry.getKey(),java.nio.charset.StandardCharsets.UTF_8)+"="+URLEncoder.encode(entry.getValue().isValueNode()?entry.getValue().asText():entry.getValue().toString(),java.nio.charset.StandardCharsets.UTF_8)));
                    builder.uri(URI.create(target+(query.length()>0?"?"+query:""))).GET();
                }else builder.uri(target).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(arguments.toString()));
                var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
                var deadline=deadlines.schedule(()->{try{response.body().close();}catch(Exception ignored){}},20,java.util.concurrent.TimeUnit.SECONDS);
                try(InputStream input=response.body()){
                    if(response.statusCode()<200||response.statusCode()>=300)throw new IOException("工具 HTTP "+response.statusCode());byte[] bytes=input.readNBytes(100001);if(bytes.length>100000)throw new IOException("工具返回超出大小限制");
                    String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);result=json.createObjectNode().put("status",response.statusCode()).put("body",text);
                }finally{deadline.cancel(false);}
            }else throw new FusionFault(422,"技能不能作为工具调用");
            if(result.toString().length()>100000)throw new FusionFault(422,"检索结果超过预算，请缩小查询范围");
            db.update("update fusion_tool_call set status='succeeded',result=? where run_id=? and call_id=?",result.toString(),run,call);return result;
        }catch(Exception e){db.update("update fusion_tool_call set status='unknown' where run_id=? and call_id=?",run,call);throw new FusionFault(502,e instanceof FusionFault?e.getMessage():"工具执行失败，结果未知，未自动重试");}
    }
    static void validateArguments(JsonNode schema,JsonNode arguments){
        for(JsonNode key:schema.path("required"))if(!arguments.hasNonNull(key.asText()))throw new FusionFault(422,"缺少工具参数："+key.asText());
        arguments.fields().forEachRemaining(entry->{JsonNode property=schema.path("properties").path(entry.getKey());if(property.isMissingNode())throw new FusionFault(422,"未知工具参数");JsonNode value=entry.getValue();boolean ok=switch(property.path("type").asText()){case "string"->value.isTextual();case "integer"->value.isIntegralNumber();case "number"->value.isNumber();case "boolean"->value.isBoolean();case "object"->value.isObject();case "array"->value.isArray();default->false;};if(!ok)throw new FusionFault(422,"工具参数类型不匹配");});
    }
}
