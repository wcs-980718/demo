package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yiwei.midplat.common.api.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Streaming OpenAI-compatible invocation. Only this boundary resolves provider credentials. */
@Service
public class ModelInvocationGateway {
    private final ExecutionTickets tickets;
    private final FusionCatalog catalog;
    private final FusionState state;
    private final JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ObjectMapper json;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    @Value("${midplat.fusion.model-call-timeout-ms:300000}") private long callTimeoutMs=300000;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fusion-model-deadline");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy void stopDeadlines(){deadlines.shutdownNow();active.values().forEach(ActiveCall::close);}
    private final Semaphore slots = new Semaphore(32);
    private final ConcurrentMap<String, Semaphore> projectSlots = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ActiveCall> active = new ConcurrentHashMap<>();
    private static class ActiveCall {
        final String runId; volatile InputStream input; volatile Thread thread; volatile boolean timedOut;
        ActiveCall(String runId) { this.runId = runId; this.thread = Thread.currentThread(); }
        synchronized void close() { try { if (input != null) input.close(); } catch (IOException ignored) {} if (thread != null) thread.interrupt(); }
        synchronized void expire(){if(thread==null)return;timedOut=true;close();}
        synchronized void finished(){thread=null;}
    }
    public ModelInvocationGateway(ExecutionTickets tickets, FusionCatalog catalog, FusionState state,
            JdbcTemplate db, ObjectMapper json, org.springframework.transaction.PlatformTransactionManager manager, @Value("${midplat.fusion.execution-enabled:false}") boolean enabled) {
        this.tickets=tickets; this.catalog=catalog; this.state=state; this.db=db; this.json=json; this.enabled=enabled;this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    private int write(String sql,Object...args){return tx.execute(status->db.update(sql,args));}
    public JsonNode authorize(String authorization) {
        if (!enabled) throw new FusionUpstreamFault(503,"执行服务未启用");
        return tickets.verify(authorization);
    }
    public record Result(String status, String error, String output, JsonNode usage) {}
    long deadlineAfter(long timeoutMillis) {
        if(timeoutMillis<=0)throw new IllegalArgumentException("X-Request-Timeout-Ms 必须是正数毫秒数");
        // 在本机转换相对预算，避免业务服务器时钟偏差改变调用时限。
        return System.currentTimeMillis()+Math.min(callTimeoutMs,timeoutMillis);
    }
    // 调用方只能缩短服务端预算；绝对时间同时涵盖创建运行记录所花的时间。
    long remainingTimeoutMs(Long deadlineMillis) {
        if(deadlineMillis==null)return callTimeoutMs;
        if(deadlineMillis<=0)throw new IllegalArgumentException("X-Request-Deadline-Ms 必须是正数时间戳");
        long remaining=deadlineMillis-System.currentTimeMillis();
        if(remaining<=0)throw new FusionUpstreamFault(504,"业务模型调用已超过执行时限");
        return Math.min(callTimeoutMs,remaining);
    }
    public Result invoke(String authorization, JsonNode request, HttpServletResponse response) throws IOException {
        return invoke(authorization,request,response,null);
    }
    public Result invoke(String authorization, JsonNode request, HttpServletResponse response, Long deadlineMillis) throws IOException {
        JsonNode claims=authorize(authorization);
        String project=claims.path("projectId").asText(), env=claims.path("environment").asText(), run=claims.path("runId").asText();
        String node=FusionController.text(request,"nodeId",64); FusionController.identifier(node);
        String revision=claims.path("nodes").path(node).asText();
        if(revision.isBlank()) throw new ForbiddenException("该节点未包含在执行票据中");
        if(!request.path("sequence").isIntegralNumber() || request.path("sequence").asInt(-1)<0 || request.path("sequence").asInt()>8) throw new IllegalArgumentException("模型调用轮数超限");
        if(db.queryForObject("select count(*) from midplat_cancelled_run where run_id=?",Long.class,run)>0) throw new ConflictException("运行已取消");
        var binding=state.binding(project,env);
        if(binding==null || !claims.path("deploymentId").asText().equals(binding.get("deployment_id")) || !"ready".equals(binding.get("status"))) throw new ForbiddenException("执行部署绑定不匹配");
        var revisionView=state.requireModelRevision(revision); String modelId=(String)revisionView.get("resourceId");
        if(db.queryForObject("select count(*) from midplat_catalog_reference where deployment_id=? and revision_id=?",Long.class,binding.get("deployment_id"),revision)==0) throw new ForbiddenException("模型修订未登记到发布部署");
        JsonNode config=(JsonNode)revisionView.get("content");
        var credentials=db.queryForList("select api_key,base_url,supports_tool_calls,supports_json_object from midplat_model where id=?",modelId);
        if(credentials.isEmpty())throw new ResourceNotFoundException("模型不存在");
        if(!config.path("baseUrl").asText().equals(credentials.get(0).get("base_url")))throw new ConflictException("模型连接地址已变更，请重新发布；旧修订不会向旧地址发送新凭证");
        if(request.path("tools").size()>0&&!Boolean.TRUE.equals(credentials.get(0).get("supports_tool_calls")))throw new IllegalArgumentException("模型未声明支持工具调用，请在模型管理中核实能力");
        if("json_object".equals(request.path("response_format").path("type").asText())&&!Boolean.TRUE.equals(credentials.get(0).get("supports_json_object")))throw new IllegalArgumentException("模型未声明支持 JSON 对象输出");
        String credential=(String)credentials.get(0).get("api_key");
        if(!"llm".equals(config.path("kind").asText())) throw new IllegalArgumentException("调用需要 LLM 修订");
        ObjectNode payload=payload(request,config.path("model").asText());
        URI uri=endpoint(config.path("baseUrl").asText());
        remainingTimeoutMs(deadlineMillis);
        Semaphore projectLimit=projectSlots.computeIfAbsent(project,k->new Semaphore(8));
        if(!slots.tryAcquire()) throw new FusionUpstreamFault(429,"模型调用并发已满，请稍后重试");
        if(!projectLimit.tryAcquire()){slots.release();throw new FusionUpstreamFault(429,"项目并发限额已满");}
        String invocation=UUID.randomUUID().toString(); ActiveCall call=new ActiveCall(run); boolean inserted=false;
        String status="failed", error=null, output=""; JsonNode usage=null;ScheduledFuture<?> deadline=null;
        try {
            String invitationClientId=claims.path("clientId").asText("");
            write("insert into midplat_model_invocation(id,run_id,node_id,call_sequence,project_id,environment,deployment_id,release_id,principal,client_id,credential_id,model_revision_id,status) values(?,?,?,?,?,?,?,?,?,?,?,?,'running')",
                    invocation,run,node,request.path("sequence").asInt(),project,env,binding.get("deployment_id"),claims.path("releaseId").asText(),claims.path("principal").asText(),invitationClientId.isEmpty()?null:invitationClientId,claims.path("credentialId").isValueNode()?claims.path("credentialId").asText():null,revision);
            inserted=true;
            long timeoutMs=remainingTimeoutMs(deadlineMillis);
            active.put(invocation,call);deadline=deadlines.schedule(call::expire,timeoutMs,TimeUnit.MILLISECONDS);
            if(db.queryForObject("select count(*) from midplat_cancelled_run where run_id=?",Long.class,run)>0) throw new InterruptedException();
            HttpRequest.Builder upstream=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs)).header("Content-Type","application/json").header("Accept",payload.path("stream").asBoolean()?"text/event-stream":"application/json");
            if(credential!=null&&!credential.isBlank()) upstream.header("Authorization","Bearer "+credential);
            var result=http.send(upstream.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),HttpResponse.BodyHandlers.ofInputStream());
            call.input=result.body();
            try(InputStream input=result.body()) {
                if(call.timedOut||Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(result.statusCode()!=200) throw new FusionUpstreamFault(result.statusCode()==429?429:502,"上游模型返回 HTTP "+result.statusCode());
                boolean stream=payload.path("stream").asBoolean();
                response.setStatus(200);response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");response.setHeader("X-Invocation-Id",invocation);
                if(stream) {
                    String contentType=result.headers().firstValue("Content-Type").orElse("");
                    if(!contentType.contains("text/event-stream")) throw new FusionUpstreamFault(502,"上游未按约定返回 SSE");
                    response.setContentType("text/event-stream;charset=UTF-8");
                    UsageCollector collector=new UsageCollector(json);byte[] buffer=new byte[4096];int read;
                    long bytes=0;
                    while(!collector.done&&(read=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();bytes+=read;if(bytes>8_000_000)throw new IOException("Response too large");collector.accept(buffer,read);response.getOutputStream().write(buffer,0,read);response.flushBuffer();}
                    usage=collector.usage; output=collector.output();
                    if(!collector.done) throw new IOException("Incomplete model stream");
                } else {
                    byte[] bytes=input.readNBytes(2_000_001);if(bytes.length>2_000_000)throw new IOException("Response too large");
                    JsonNode resultBody=json.readTree(bytes);usage=resultBody.get("usage");output=messageOutput(resultBody.path("choices").path(0).path("message"));response.setContentType("application/json;charset=UTF-8");response.getOutputStream().write(bytes);response.flushBuffer();
                }
                status="succeeded";
            }
        } catch(org.springframework.dao.DuplicateKeyException e) { throw new ConflictException("该运行节点调用已经提交，禁止重复计费执行");
        } catch(FusionUpstreamFault e) {error=e.getMessage();throw e;
        } catch(InterruptedException e) {
            if(call.timedOut){error="模型流超过执行时限，已关闭上游连接";throw new FusionUpstreamFault(504,error);}
            status="cancelled";error="运行已取消";Thread.currentThread().interrupt();if(!response.isCommitted())throw new ConflictException(error);
        } catch(IOException e) {
            if(call.timedOut||e instanceof HttpTimeoutException){call.timedOut=true;error="模型流超过执行时限，已关闭上游连接";throw new FusionUpstreamFault(504,error);}
            if(Thread.currentThread().isInterrupted()){status="cancelled";error="运行已取消";}else error="上游连接或客户端连接中断";if(!response.isCommitted())throw new FusionUpstreamFault(502,error);throw e;
        } finally {
            call.finished();if(deadline!=null)deadline.cancel(false);
            if(call.timedOut){status="failed";error="模型流超过执行时限，已关闭上游连接";Thread.interrupted();}
            active.remove(invocation);projectLimit.release();slots.release();
            if(inserted){boolean known=usage!=null&&usage.path("prompt_tokens").canConvertToLong()&&usage.path("completion_tokens").canConvertToLong();
                boolean interrupted=Thread.interrupted();
                try{write("update midplat_model_invocation set status=?,input_tokens=?,output_tokens=?,usage_status=?,error=?,finished_at=CURRENT_TIMESTAMP where id=?",
                    status,known?usage.path("prompt_tokens").asLong():null,known?usage.path("completion_tokens").asLong():null,known?"reported":"unknown",error,invocation);}
                finally{if(interrupted)Thread.currentThread().interrupt();}}
        }
        return new Result(status,error,output,usage);
    }
    public Map<String,Object> cancel(String authorization) {
        return cancelAuthorizedRun(authorize(authorization).path("runId").asText());
    }
    // Called only after the management or project boundary has checked ownership of this run.
    public Map<String,Object> cancelAuthorizedRun(String run) {
        try{write("insert into midplat_cancelled_run(run_id,expires_at) values(?,?)",run,OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));}catch(org.springframework.dao.DuplicateKeyException ignored){}
        active.values().stream().filter(c->c.runId.equals(run)).forEach(ActiveCall::close);
        return Map.of("runId",run,"cancelled",true);
    }
    static URI endpoint(String base) {
        try{URI uri=URI.create(base.replaceAll("/+$","")+"/chat/completions");if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Exception();return uri;}
        catch(Exception e){throw new IllegalArgumentException("模型目录地址无效");}
    }
    ObjectNode payload(JsonNode request,String model){
        if(!request.isObject()||request.toString().length()>300000)throw new IllegalArgumentException("模型请求超出大小限制");
        Set<String> allowed=Set.of("nodeId","sequence","messages","tools","tool_choice","response_format","stream","stream_options","temperature","max_tokens","parallel_tool_calls","top_p","stop","seed","enable_thinking");
        request.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("不支持的调用字段："+k);});
        JsonNode messages=request.path("messages");if(!messages.isArray()||messages.isEmpty()||messages.size()>100)throw new IllegalArgumentException("messages 必须包含 1–100 条消息");
        for(JsonNode message:messages){if(!Set.of("system","user","assistant","tool","developer").contains(message.path("role").asText()))throw new IllegalArgumentException("消息角色无效");if(message.path("role").asText().equals("tool")&&message.path("tool_call_id").asText().isBlank())throw new IllegalArgumentException("工具结果缺少 tool_call_id");}
        if(request.has("tools")&&(!request.path("tools").isArray()||request.path("tools").size()>20))throw new IllegalArgumentException("工具定义数量超限");
        if(request.has("max_tokens")&&(!request.path("max_tokens").isIntegralNumber()||request.path("max_tokens").asInt()<1||request.path("max_tokens").asInt()>16384))throw new IllegalArgumentException("max_tokens 必须为 1–16384");
        for(String flag:List.of("stream","parallel_tool_calls","enable_thinking"))if(request.has(flag)&&!request.path(flag).isBoolean())throw new IllegalArgumentException(flag+" 必须为布尔值");
        ObjectNode payload=(ObjectNode)request.deepCopy();payload.remove(List.of("nodeId","sequence"));payload.put("model",model);if(!payload.has("max_tokens"))payload.put("max_tokens",2048);return payload;
    }
    private static String messageOutput(JsonNode message) {
        return message.path("tool_calls").isEmpty() ? message.path("content").asText("") : message.toString();
    }
    static class UsageCollector {
        private final ObjectMapper json;
        private final ByteArrayOutputStream line=new ByteArrayOutputStream();
        private final StringBuilder content=new StringBuilder();
        private final SortedMap<Integer,ObjectNode> toolCalls=new TreeMap<>();
        JsonNode usage; boolean done;
        UsageCollector(ObjectMapper json){this.json=json;}
        void accept(byte[] bytes,int size)throws IOException {
            for(int i=0;i<size;i++) {
                if(bytes[i]=='\n') {
                    String value=line.toString(StandardCharsets.UTF_8).trim();line.reset();
                    if(value.startsWith("data:")) {
                        String data=value.substring(5).trim();
                        if(data.equals("[DONE]"))done=true;
                        else {
                            JsonNode chunk;
                            try{chunk=json.readTree(data);}catch(Exception ignored){continue;}
                            if(chunk.hasNonNull("usage"))usage=chunk.get("usage");
                            JsonNode delta=chunk.path("choices").path(0).path("delta");
                            content.append(delta.path("content").asText(""));
                            for(JsonNode fragment:delta.path("tool_calls")) {
                                int index=fragment.path("index").asInt(-1);
                                if(index<0||index>=20)throw new IOException("Invalid tool call index");
                                ObjectNode call=toolCalls.computeIfAbsent(index,k->{ObjectNode c=json.createObjectNode().put("id","").put("type","function");c.putObject("function").put("name","").put("arguments","");return c;});
                                appendFragment(call,"id",fragment.path("id").asText(""),true);
                                ObjectNode function=(ObjectNode)call.path("function");
                                appendFragment(function,"name",fragment.path("function").path("name").asText(""),true);
                                appendFragment(function,"arguments",fragment.path("function").path("arguments").asText(""),false);
                            }
                        }
                    }
                } else {
                    if(line.size()>1_000_000)throw new IOException("SSE frame too large");line.write(bytes[i]);
                }
            }
        }
        private static void appendFragment(ObjectNode target,String key,String value,boolean repeatedIdentifier) {
            String current=target.path(key).asText();
            if(!value.isEmpty()&&(!repeatedIdentifier||!current.equals(value)))target.put(key,current+value);
        }
        String output() {
            ObjectNode message=json.createObjectNode().put("role","assistant").put("content",content.toString());
            if(!toolCalls.isEmpty())toolCalls.values().forEach(message.putArray("tool_calls")::add);
            return messageOutput(message);
        }
    }
}
