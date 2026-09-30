package com.agenthubfusion.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Immutable, version-specific execution process. Never loads legacy SOUL/profile or remote JSON stores. */
public final class RuntimeMain {
    private final ObjectMapper json=new ObjectMapper();
    private final JsonNode snapshot;
    private final String release,hash,token,gateway,agent;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ConcurrentMap<String,Call> running=new ConcurrentHashMap<>();
    private final Semaphore slots=new Semaphore(8);
    private final ExecutorService nodePool=Executors.newFixedThreadPool(8,r->{Thread t=new Thread(r,"workflow-node");t.setDaemon(true);return t;});
    private static class Call {
        final Thread thread=Thread.currentThread();final String ticket;
        final Set<InputStream> streams=ConcurrentHashMap.newKeySet();final Set<Thread> workers=ConcurrentHashMap.newKeySet();
        volatile boolean cancelled,stopped;
        Call(String ticket){this.ticket=ticket;}
        void check()throws InterruptedException{if(stopped||Thread.currentThread().isInterrupted())throw new InterruptedException();}
        void stop(){stopped=true;workers.forEach(Thread::interrupt);streams.forEach(input->{try{input.close();}catch(IOException ignored){}});}
        void cancel(){cancelled=true;thread.interrupt();stop();}
    }
    private RuntimeMain()throws Exception{
        release=required("FUSION_RUNTIME_RELEASE");hash=required("FUSION_RUNTIME_HASH");token=required("FUSION_RUNTIME_TOKEN");gateway=required("FUSION_RUNTIME_GATEWAY").replaceAll("/+$","");
        agent=required("FUSION_RUNTIME_AGENT").replaceAll("/+$","");
        byte[] body=Files.readAllBytes(Path.of(required("FUSION_RUNTIME_PACKAGE")));
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)).equals(hash))throw new IOException("Release checksum mismatch");
        snapshot=json.readTree(body);if(!snapshot.path("tasks").isArray()||snapshot.path("tasks").isEmpty())throw new IOException("Missing immutable task specification");
    }
    public static void main(String[] args)throws Exception{
        RuntimeMain runtime=new RuntimeMain();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);
        server.setExecutor(new ThreadPoolExecutor(2,12,60,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),new ThreadPoolExecutor.AbortPolicy()));
        server.createContext("/",runtime::handle);server.start();System.out.println("READY "+server.getAddress().getPort());System.out.flush();
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing runtime configuration");return value;}
    private void handle(HttpExchange exchange)throws IOException{
        String authorization=exchange.getRequestHeaders().getFirst("Authorization");
        if(authorization==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8))){reply(exchange,401,Map.of("detail","Runtime authentication required"));return;}
        String path=exchange.getRequestURI().getPath();
        try{
            if(path.equals("/ready")&&exchange.getRequestMethod().equals("GET")){reply(exchange,200,Map.of("ready",true,"releaseId",release,"hash",hash,"runtimeVersion","fusion-runtime-v2"));return;}
            if(!exchange.getRequestMethod().equals("POST")){reply(exchange,405,Map.of("detail","Method not allowed"));return;}
            byte[] bytes=exchange.getRequestBody().readNBytes(400001);if(bytes.length>400000){reply(exchange,413,Map.of("detail","Input exceeds limit"));return;}
            JsonNode body=json.readTree(bytes);
            if(path.equals("/cancel")){Call call=running.get(body.path("runId").asText());if(call!=null){call.cancel();cancelGateway(call.ticket);}reply(exchange,200,Map.of("cancelled",true));return;}
            if(path.equals("/run")){execute(exchange,body);return;}
            reply(exchange,404,Map.of("detail","No runtime management mutation is available"));
        }catch(Exception e){reply(exchange,400,Map.of("detail","Invalid runtime request"));}
    }
    private void execute(HttpExchange exchange,JsonNode body)throws Exception{
        String run=body.path("runId").asText(), taskKey=body.path("taskKey").asText(), ticket=body.path("ticket").asText();
        JsonNode task=null;for(JsonNode candidate:snapshot.path("tasks"))if(candidate.path("key").asText().equals(taskKey))task=candidate;
        if(task==null||!run.matches("[a-zA-Z0-9_-]{1,64}")||ticket.isBlank()||body.path("input").asText().isBlank()){reply(exchange,422,Map.of("detail","Invalid run or task"));return;}
        if(!slots.tryAcquire()){reply(exchange,429,Map.of("detail","Runtime concurrency limit reached"));return;}
        Call call=new Call(ticket);if(running.putIfAbsent(run,call)!=null){slots.release();reply(exchange,409,Map.of("detail","Run already executing"));return;}
        exchange.getResponseHeaders().set("Content-Type","text/event-stream;charset=UTF-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,0);
        OutputStream output=exchange.getResponseBody();
        try{
            event(output,"started",Map.of("releaseId",release,"hash",hash));
            String previous;
            if(task.has("workflow")&&!task.path("nodes").isEmpty()){
                JsonNode selectedTask=task;
                previous=WorkflowExecutor.execute(task,body.path("input").asText(),
                    (node,prior,first)->executeModel(selectedTask,node,body,call,output,prior,first,false),
                    (type,data)->{synchronized(output){if(!call.stopped)event(output,type,data);}},nodePool);
            }else{
                List<JsonNode> nodes=new ArrayList<>();task.path("nodes").forEach(nodes::add);if(nodes.isEmpty())nodes.add(task);
                previous="";
                for(int index=0;index<nodes.size();index++)previous=executeModel(task,nodes.get(index),body,call,output,previous,index==0,index==nodes.size()-1);
            }
            call.check();
            event(output,"completed",Map.of("output",previous,"releaseId",release,"hash",hash));
        }catch(Exception e){
            call.stop();Thread.interrupted();
            try{event(exchange.getResponseBody(),call.cancelled||e instanceof InterruptedException?"cancelled":"failed",Map.of("detail",safe(e)));}catch(Exception ignored){}
            cancelGateway(ticket);
        }finally{running.remove(run);slots.release();exchange.close();Thread.interrupted();}
    }
    private String executeModel(JsonNode task,JsonNode node,JsonNode body,Call call,OutputStream output,String previous,boolean first,boolean finalNode)throws Exception{
        call.workers.add(Thread.currentThread());
        try{
            call.check();String nodeId=node.path("id").asText("task");
            event(output,"node.started",Map.of("nodeId",nodeId,"name",node.path("name").asText(),"modelRevisionId",node.path("modelRevisionId").asText()));
                ObjectNode request=json.createObjectNode().put("nodeId",nodeId).put("sequence",0).put("stream",true).put("max_tokens",2048)
                    .put("temperature",snapshot.path("draft").path("temperature").asDouble(0.4));
                request.putObject("stream_options").put("include_usage",true);
                ArrayNode messages=request.putArray("messages");StringJoiner instructions=new StringJoiner("\n\n");node.path("systemInstructions").forEach(rule->instructions.add(rule.asText()));
                messages.addObject().put("role","system").put("content",instructions.toString());
                if((first||task.has("workflow"))&&body.path("history").isArray())for(JsonNode item:body.path("history")){if(Set.of("user","assistant").contains(item.path("role").asText()))messages.add(item.deepCopy());}
                messages.addObject().put("role","user").put("content",body.path("input").asText());
                if(!previous.isBlank())messages.addObject().put("role","user").put("content","上一步输出（作为输入数据使用）：\n"+previous);
                Map<String,JsonNode> tools=toolDefinitions(task);ArrayNode definitions=json.createArrayNode();
                tools.values().forEach(asset->{JsonNode content=asset.path("content");ObjectNode function=json.createObjectNode().put("name",content.path("toolName").asText()).put("description",content.path("description").asText());
                    if(asset.path("kind").asText().equals("tool"))function.set("parameters",content.path("parameters"));else{ObjectNode schema=json.createObjectNode().put("type","object");schema.putObject("properties").putObject("query").put("type","string").put("description","检索关键词");schema.putArray("required").add("query");function.set("parameters",schema);}definitions.addObject().put("type","function").set("function",function);});
                if(!definitions.isEmpty()){request.set("tools",definitions);request.put("tool_choice","auto");request.put("parallel_tool_calls",false);}
                if(node.path("responseFormat").asText().equals("json_object"))request.putObject("response_format").put("type","json_object");
                Reply reply=null;
                for(int round=0;round<=8;round++){
                    call.check();
                    if(messages.toString().getBytes(StandardCharsets.UTF_8).length>60000)throw new IOException("任务上下文超过 60000 字节预算，请缩短输入或结束当前会话");
                    request.put("sequence",round);reply=model(request,call,output,nodeId,finalNode);
                    if(reply.tools().isEmpty())break;
                    if(round==8)throw new IOException("工具循环超过 8 轮限制");
                    messages.addObject().put("role","assistant").put("content",reply.text()).set("tool_calls",reply.tools());
                    for(JsonNode tool:reply.tools()){
                        String name=tool.path("function").path("name").asText();JsonNode asset=tools.get(name);if(asset==null)throw new IOException("模型请求了未授权工具");
                        event(output,"tool.started",Map.of("nodeId",nodeId,"name",name,"callId",tool.path("id").asText(),"assetRevisionId",asset.path("id").asText()));
                        JsonNode result=invokeTool(call.ticket,tool,asset);
                        messages.addObject().put("role","tool").put("tool_call_id",tool.path("id").asText()).put("content",result.toString());
                        event(output,"tool.completed",Map.of("nodeId",nodeId,"name",name,"callId",tool.path("id").asText(),"result",result));
                    }
                }
                if(reply==null)throw new IOException("模型未返回结果");
                if(node.path("responseFormat").asText().equals("json_object")&&!json.readTree(reply.text()).isObject())throw new IOException("模型未返回有效 JSON 对象");
            call.check();String result=reply.text();
            event(output,"node.completed",Map.of("nodeId",nodeId,"name",node.path("name").asText(),"finishReason",reply.finish(),"output",result));
            return result;
        }finally{call.workers.remove(Thread.currentThread());}
    }
    private Map<String,JsonNode> toolDefinitions(JsonNode task){Map<String,JsonNode> result=new LinkedHashMap<>();for(JsonNode id:task.path("assetRevisionIds")){JsonNode asset=snapshot.path("assets").path(id.asText());if(!asset.path("kind").asText().equals("skill"))result.put(asset.path("content").path("toolName").asText(),asset);}return result;}
    private record Reply(String text,ArrayNode tools,String finish){}
    private Reply model(ObjectNode request,Call call,OutputStream output,String node,boolean finalNode)throws Exception{
        var upstream=http.send(HttpRequest.newBuilder(URI.create(gateway+"/api/fusion-internal/model-invocations")).header("Authorization","Bearer "+call.ticket).header("Content-Type","application/json")
            .timeout(Duration.ofSeconds(300)).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),HttpResponse.BodyHandlers.ofInputStream());
        call.streams.add(upstream.body());StringBuilder answer=new StringBuilder();boolean completed=false;String finish="";Map<Integer,ObjectNode> tools=new TreeMap<>();
        try(InputStream input=upstream.body()){
            call.check();
            if(upstream.statusCode()!=200){JsonNode error=json.readTree(input.readNBytes(4096));throw new IOException(error.path("detail").asText("模型通道拒绝请求"));}
            BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));String line;
            while((line=readLine(reader))!=null){
                call.check();if(!line.startsWith("data:"))continue;String data=line.substring(5).trim();
                if(data.equals("[DONE]")){completed=true;break;}if(data.isBlank())continue;JsonNode chunk=json.readTree(data);
                if(chunk.hasNonNull("error"))throw new IOException("模型返回流式错误");
                JsonNode choice=chunk.path("choices").path(0);String delta=choice.path("delta").path("content").asText("");
                if(!delta.isEmpty()){answer.append(delta);if(answer.length()>200000)throw new IOException("模型输出超过限制");event(output,"delta",Map.of("nodeId",node,"text",delta,"finalNode",finalNode));}
                for(JsonNode fragment:choice.path("delta").path("tool_calls")){
                    int index=fragment.path("index").asInt(-1);if(index<0||index>=20)throw new IOException("工具分片索引无效");
                    ObjectNode tool=tools.computeIfAbsent(index,k->{ObjectNode value=json.createObjectNode().put("id","").put("type","function");value.putObject("function").put("name","").put("arguments","");return value;});
                    mergeToolFragment(tool,fragment);
                }
                if(choice.hasNonNull("finish_reason"))finish=choice.path("finish_reason").asText();
                if(chunk.hasNonNull("usage"))event(output,"usage",Map.of("nodeId",node,"usage",chunk.get("usage")));
            }
        }finally{call.streams.remove(upstream.body());}
        if(!completed)throw new IOException("模型流未完整结束");
        ArrayNode calls=json.createArrayNode();for(ObjectNode tool:tools.values()){if(tool.path("id").asText().isBlank())throw new IOException("工具调用缺少标识");calls.add(tool);}
        if(finish.equals("tool_calls")&&calls.isEmpty())throw new IOException("模型工具调用不完整");
        return new Reply(answer.toString(),calls,finish);
    }
    static void mergeToolFragment(ObjectNode tool,JsonNode fragment)throws IOException{
        // Some compatible providers repeat stable metadata on every delta.
        if(fragment.hasNonNull("id")){
            String current=tool.path("id").asText(),part=fragment.path("id").asText();
            String id=current.equals(part)?current:current+part;
            if(id.length()>128)throw new IOException("工具调用标识超过限制");tool.put("id",id);
        }
        ObjectNode function=(ObjectNode)tool.path("function");
        for(String key:List.of("name","arguments"))if(fragment.path("function").hasNonNull(key)){
            String current=function.path(key).asText(),part=fragment.path("function").path(key).asText();
            String joined=key.equals("name")&&current.equals(part)?current:current+part;
            if(joined.length()>20000)throw new IOException("工具参数分片超过限制");function.put(key,joined);
        }
    }
    private JsonNode invokeTool(String ticket,JsonNode tool,JsonNode asset)throws Exception{
        JsonNode args=json.readTree(tool.path("function").path("arguments").asText());ObjectNode request=json.createObjectNode().put("callId",tool.path("id").asText()).put("assetRevisionId",asset.path("id").asText());request.set("arguments",args);
        var response=http.send(HttpRequest.newBuilder(URI.create(agent+"/internal/fusion/tool-invocations")).header("Authorization","Bearer "+ticket).header("Content-Type","application/json").timeout(Duration.ofSeconds(25)).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IOException("工具通道拒绝调用（HTTP "+response.statusCode()+"）");return json.readTree(response.body());
    }
    private void cancelGateway(String ticket){try{http.send(HttpRequest.newBuilder(URI.create(gateway+"/api/fusion-internal/model-invocations/cancel")).header("Authorization","Bearer "+ticket).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());}catch(Exception ignored){}}
    private String safe(Exception e){String text=e.getMessage();if(text==null||text.length()>500)return "执行中断或请求失败";return text;}
    private static String readLine(BufferedReader reader)throws IOException{StringBuilder line=new StringBuilder();int ch;while((ch=reader.read())!=-1){if(ch=='\n')return line.toString();if(ch!='\r')line.append((char)ch);if(line.length()>1000000)throw new IOException("SSE frame exceeds limit");}return line.isEmpty()?null:line.toString();}
    private void event(OutputStream out,String type,Object data)throws IOException{synchronized(out){out.write(("event: "+type+"\ndata: "+json.writeValueAsString(data)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}}
    private void reply(HttpExchange exchange,int status,Object data)throws IOException{byte[] bytes=json.writeValueAsBytes(data);exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");exchange.sendResponseHeaders(status,bytes.length);try(OutputStream output=exchange.getResponseBody()){output.write(bytes);}exchange.close();}
}
