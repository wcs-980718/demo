package com.agenthubfusion.runtime;

import com.agenthubfusion.WorkflowGraph;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** A bounded DAG scheduler. Inactive condition branches settle as skipped, so joins cannot hang. */
public final class WorkflowExecutor {
    private static final ObjectMapper JSON=new ObjectMapper();
    @FunctionalInterface public interface ModelRunner {String run(JsonNode node,String previous,boolean first)throws Exception;}
    @FunctionalInterface public interface Events {void emit(String type,Map<String,Object> data)throws Exception;}
    private record Result(String id,String output,String branch,boolean active) {}
    private WorkflowExecutor() {}

    public static String execute(JsonNode task,String input,ModelRunner runner,Events events,ExecutorService pool)throws Exception{
        WorkflowGraph.validate(task,true);
        LinkedHashMap<String,JsonNode> pending=new LinkedHashMap<>();task.path("nodes").forEach(node->pending.put(node.path("id").asText(),node));
        List<JsonNode> edges=new ArrayList<>();task.path("workflow").path("edges").forEach(edges::add);
        Map<String,Result> settled=new HashMap<>();settled.put(WorkflowGraph.START,new Result(WorkflowGraph.START,input,null,true));
        CompletionService<Result> completions=new ExecutorCompletionService<>(pool);List<Future<Result>> futures=new ArrayList<>();int active=0;
        try{
            while(!pending.isEmpty()||active>0){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                boolean progressed=false;
                for(var iterator=pending.entrySet().iterator();iterator.hasNext();){
                    var entry=iterator.next();String id=entry.getKey();JsonNode node=entry.getValue();
                    List<JsonNode> incoming=edges.stream().filter(edge->edge.path("target").asText().equals(id)).toList();
                    if(incoming.stream().anyMatch(edge->!settled.containsKey(edge.path("source").asText())))continue;
                    List<String> sources=incoming.stream().filter(edge->selected(edge,settled)).map(edge->edge.path("source").asText()).distinct().sorted().toList();
                    if(sources.isEmpty()){
                        settled.put(id,new Result(id,"",null,false));events.emit("node.skipped",Map.of("nodeId",id,"name",node.path("name").asText(),"detail","条件分支未命中"));iterator.remove();progressed=true;continue;
                    }
                    String previous=combine(sources,settled);String kind=WorkflowGraph.kind(node);
                    if(WorkflowGraph.isModel(node)){
                        if(active>=4)continue;
                        futures.add(completions.submit(()->{
                            try{return new Result(id,runner.run(node,previous,sources.equals(List.of(WorkflowGraph.START))),null,true);}
                            catch(Exception error){events.emit("node.failed",Map.of("nodeId",id,"name",node.path("name").asText(),"detail",error.getMessage()==null?"节点执行失败":error.getMessage()));throw error;}
                        }));active++;
                    }else{
                        events.emit("node.started",Map.of("nodeId",id,"name",node.path("name").asText(),"kind",kind));
                        String branch=kind.equals("condition")?Boolean.toString(matches(node.path("condition"),input,previous)):null;
                        if(branch!=null)events.emit("branch.selected",Map.of("nodeId",id,"name",node.path("name").asText(),"branch",branch,"detail",branch.equals("true")?"满足条件":"不满足条件"));
                        settled.put(id,new Result(id,previous,branch,true));
                        events.emit("node.completed",Map.of("nodeId",id,"name",node.path("name").asText(),"kind",kind,"output",previous));
                    }
                    iterator.remove();progressed=true;
                }
                if(active>0){
                    // First drain ready results. Block only when no further routing can progress.
                    Future<Result> future=completions.poll();
                    if(future==null&&(!progressed||active>=4||pending.isEmpty()))future=completions.take();
                    if(future!=null){Result result=future.get();settled.put(result.id(),result);active--;}
                }else if(!progressed&&!pending.isEmpty())throw new IOException("流程存在无法执行的依赖");
            }
            List<String> exits=edges.stream().filter(edge->edge.path("target").asText().equals(WorkflowGraph.END)&&selected(edge,settled)).map(edge->edge.path("source").asText()).distinct().sorted().toList();
            if(exits.isEmpty())throw new IOException("没有分支到达输出节点");
            return combine(exits,settled);
        }catch(ExecutionException error){if(error.getCause() instanceof Exception cause)throw cause;throw error;}
        finally{for(Future<Result> future:futures)if(!future.isDone())future.cancel(true);}
    }
    private static boolean selected(JsonNode edge,Map<String,Result> settled){
        Result source=settled.get(edge.path("source").asText());
        return source!=null&&source.active()&&(source.branch()==null||source.branch().equals(edge.path("sourceHandle").asText()));
    }
    private static String combine(List<String> sources,Map<String,Result> settled)throws IOException{
        if(sources.size()==1)return settled.get(sources.get(0)).output();
        ObjectNode result=JSON.createObjectNode();for(String source:sources)result.put(source,settled.get(source).output());return JSON.writeValueAsString(result);
    }
    static boolean matches(JsonNode condition,String input,String previous)throws IOException{
        String value=condition.path("source").asText().equals("input")?input:previous;
        String field=condition.path("field").asText("");
        if(!field.isEmpty()){
            JsonNode current;
            try{current=JSON.readTree(value);}catch(Exception error){throw new IOException("条件节点需要有效 JSON 才能读取字段："+field);}
            if(current==null)throw new IOException("条件节点收到空 JSON");
            for(String segment:field.split("\\.")){
                if(current.isArray()&&segment.matches("[0-9]{1,8}"))current=current.path(Integer.parseInt(segment));else current=current.path(segment);
            }
            value=current.isMissingNode()||current.isNull()?"":current.isValueNode()?current.asText():current.toString();
        }
        String expected=condition.path("value").asText("");
        return switch(condition.path("operator").asText()){
            case "contains" -> value.contains(expected);
            case "equals" -> value.equals(expected);
            case "not_equals" -> !value.equals(expected);
            case "is_empty" -> value.isBlank();
            case "not_empty" -> !value.isBlank();
            case "gt","lt" -> {
                try{int compared=new BigDecimal(value.trim()).compareTo(new BigDecimal(expected.trim()));yield condition.path("operator").asText().equals("gt")?compared>0:compared<0;}
                catch(NumberFormatException error){throw new IOException("条件节点的数值比较收到非数字内容");}
            }
            default -> throw new IOException("不支持的条件操作符");
        };
    }
}
