package com.agenthubfusion;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** The saved graph is also the execution contract; coordinates have no execution meaning. */
public final class WorkflowGraph {
    public static final String START="__start__", END="__end__";
    public static final Set<String> KINDS=Set.of("llm","condition","parallel","merge");
    public static final Set<String> OPERATORS=Set.of("contains","equals","not_equals","gt","lt","is_empty","not_empty");
    private WorkflowGraph() {}
    public static String kind(JsonNode node){return node.path("kind").asText("llm");}
    public static boolean isModel(JsonNode node){return kind(node).equals("llm");}

    public static void validate(JsonNode task,boolean complete){
        LinkedHashMap<String,JsonNode> nodes=new LinkedHashMap<>();
        for(JsonNode node:task.path("nodes")){
            String id=node.path("id").asText();
            if(!KINDS.contains(kind(node)))fail("不支持的流程节点类型");
            if(node.has("condition")&&!kind(node).equals("condition"))fail("只有条件节点可以配置判断规则");
            if(kind(node).equals("condition"))validateCondition(node.path("condition"));
            if(nodes.put(id,node)!=null)fail("流程节点标识重复");
        }
        if(!task.has("workflow")){
            if(nodes.values().stream().anyMatch(node->!isModel(node)))fail("条件、并行和汇合节点需要流程连线");
            return; // Existing sequential releases remain valid.
        }
        if(nodes.containsKey(START)||nodes.containsKey(END))fail("节点使用了流程保留标识");
        JsonNode graph=task.path("workflow");fields(graph,Set.of("version","positions","edges"),"流程");
        if(!graph.path("version").isIntegralNumber()||!graph.path("version").canConvertToInt()||graph.path("version").asInt()!=1)fail("不支持的流程版本");
        Set<String> ids=new LinkedHashSet<>();ids.add(START);ids.addAll(nodes.keySet());ids.add(END);
        JsonNode positions=graph.path("positions");
        if(!positions.isArray()||positions.size()>ids.size())fail("画布位置无效");
        Set<String> positioned=new HashSet<>();
        for(JsonNode position:positions){
            fields(position,Set.of("id","x","y"),"画布位置");
            if(!position.path("id").isTextual()||!ids.contains(position.path("id").asText())||!positioned.add(position.path("id").asText()))fail("画布位置引用了无效或重复节点");
            for(String axis:List.of("x","y"))if(!position.path(axis).isNumber()||!Double.isFinite(position.path(axis).asDouble())||Math.abs(position.path(axis).asDouble())>100000)fail("画布坐标超出范围");
        }
        JsonNode edges=graph.path("edges");if(!edges.isArray()||edges.size()>128)fail("流程连线超过限制");
        Map<String,List<String>> outgoing=new HashMap<>(),incoming=new HashMap<>();Set<String> unique=new HashSet<>(),ports=new HashSet<>();
        for(JsonNode edge:edges){
            fields(edge,Set.of("source","target","sourceHandle"),"连线");
            if(!edge.path("source").isTextual()||!edge.path("target").isTextual()||(edge.has("sourceHandle")&&!edge.path("sourceHandle").isTextual()))fail("连线节点与出口标识必须为文本");
            String source=edge.path("source").asText(),target=edge.path("target").asText(),port=edge.path("sourceHandle").asText("");
            if(!ids.contains(source)||!ids.contains(target)||source.equals(target)||source.equals(END)||target.equals(START))fail("流程连线引用了无效节点或方向");
            if(!unique.add(source+"/"+port+"/"+target))fail("流程连线重复");
            String kind=nodes.containsKey(source)?kind(nodes.get(source)):"start";
            if(kind.equals("condition")){
                if(!Set.of("true","false").contains(port)||!ports.add(source+"/"+port))fail("条件节点的满足、不满足出口各只能连接一次");
            }else if(!port.isEmpty())fail("只有条件连线可以指定分支出口");
            List<String> next=outgoing.computeIfAbsent(source,key->new ArrayList<>());next.add(target);
            if(!Set.of("parallel","condition").contains(kind)&&next.size()>1)fail("请通过并行节点连接多个后续步骤");
            if(kind.equals("parallel")&&next.size()>8)fail("单个并行节点最多关联 8 个分支");
            incoming.computeIfAbsent(target,key->new ArrayList<>()).add(source);
        }
        Map<String,Integer> degree=new HashMap<>();ArrayDeque<String> ready=new ArrayDeque<>();
        for(String id:ids){int count=incoming.getOrDefault(id,List.of()).size();degree.put(id,count);if(count==0)ready.add(id);}
        int visited=0;
        while(!ready.isEmpty()){String id=ready.remove();visited++;for(String target:outgoing.getOrDefault(id,List.of()))if(degree.merge(target,-1,Integer::sum)==0)ready.add(target);}
        if(visited!=ids.size())fail("流程不能包含循环连线");
        if(!complete)return;
        if(!reachable(START,outgoing).containsAll(ids)||!reachable(END,incoming).containsAll(ids))fail("所有节点必须连通开始与输出，请连接或删除孤立节点");
        for(var entry:nodes.entrySet()){
            String id=entry.getKey(),kind=kind(entry.getValue());int count=outgoing.getOrDefault(id,List.of()).size();
            if(kind.equals("condition")&&count!=2)fail("条件节点必须同时连接满足与不满足分支");
            if(kind.equals("parallel")&&count<2)fail("并行节点至少需要两个分支");
            if(kind.equals("merge")&&incoming.getOrDefault(id,List.of()).size()<2)fail("汇合节点至少需要两个前置分支");
        }
    }
    private static Set<String> reachable(String root,Map<String,List<String>> links){
        Set<String> visited=new HashSet<>();ArrayDeque<String> pending=new ArrayDeque<>();pending.add(root);
        while(!pending.isEmpty()){String id=pending.remove();if(visited.add(id))pending.addAll(links.getOrDefault(id,List.of()));}return visited;
    }
    private static void validateCondition(JsonNode condition){
        fields(condition,Set.of("source","field","operator","value"),"条件规则");
        if(!Set.of("input","previous").contains(condition.path("source").asText())||!OPERATORS.contains(condition.path("operator").asText()))fail("条件判断来源或操作符无效");
        if(condition.has("field")&&(!condition.path("field").isTextual()||condition.path("field").asText().length()>200||(!condition.path("field").asText().isEmpty()&&!condition.path("field").asText().matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)*"))))fail("JSON 字段路径请使用点分隔的字母、数字或下划线");
        if(!Set.of("is_empty","not_empty").contains(condition.path("operator").asText())&&(!condition.path("value").isTextual()||condition.path("value").asText().length()>2000))fail("请填写条件比较值");
        if(Set.of("gt","lt").contains(condition.path("operator").asText()))try{new java.math.BigDecimal(condition.path("value").asText());}catch(NumberFormatException ex){fail("数值条件需要有效数字");}
    }
    private static void fields(JsonNode node,Set<String> allowed,String label){
        if(!node.isObject())fail(label+"必须是对象");node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))fail(label+"含未知字段："+key);});
    }
    private static void fail(String message){throw new FusionFault(422,message);}
}
