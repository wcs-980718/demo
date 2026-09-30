package com.agenthubfusion.runtime;

import com.agenthubfusion.FusionFault;
import com.agenthubfusion.WorkflowGraph;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowExecutorTest {
    static final ObjectMapper JSON=new ObjectMapper();
    ExecutorService pool;
    @BeforeEach void start(){pool=Executors.newFixedThreadPool(8);}
    @AfterEach void stop()throws Exception{pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    static ObjectNode parallel(){
        ObjectNode task=JSON.createObjectNode().put("key","main").put("name","parallel").put("instructions","");
        ArrayNode nodes=task.putArray("nodes");
        node(nodes,"fork","parallel");node(nodes,"left","llm");node(nodes,"right","llm");node(nodes,"join","merge");node(nodes,"answer","llm");
        ObjectNode graph=task.putObject("workflow").put("version",1);graph.putArray("positions");
        ArrayNode edges=graph.putArray("edges");edge(edges,"__start__","fork");edge(edges,"fork","left");edge(edges,"fork","right");edge(edges,"left","join");edge(edges,"right","join");edge(edges,"join","answer");edge(edges,"answer","__end__");return task;
    }
    static ObjectNode condition(){
        ObjectNode task=parallel();ArrayNode nodes=task.putArray("nodes");
        node(nodes,"route","condition").putObject("condition").put("source","input").put("operator","contains").put("value","报告");
        node(nodes,"report","llm");node(nodes,"chat","llm");node(nodes,"join","merge");
        ArrayNode edges=((ObjectNode)task.path("workflow")).putArray("edges");
        edge(edges,"__start__","route");edge(edges,"route","report").put("sourceHandle","true");edge(edges,"route","chat").put("sourceHandle","false");edge(edges,"report","join");edge(edges,"chat","join");edge(edges,"join","__end__");return task;
    }
    static ObjectNode node(ArrayNode nodes,String id,String kind){return nodes.addObject().put("id",id).put("name",id).put("kind",kind).put("instructions","");}
    static ObjectNode edge(ArrayNode edges,String source,String target){return edges.addObject().put("source",source).put("target",target);}

    @Test void parallelCallsOverlapAndJoinReceivesEveryResultInStableOrder()throws Exception{
        CountDownLatch entered=new CountDownLatch(2);List<String> called=new CopyOnWriteArrayList<>();
        String output=WorkflowExecutor.execute(parallel(),"input",(node,prior,first)->{
            String id=node.path("id").asText();called.add(id);
            if(Set.of("left","right").contains(id)){entered.countDown();assertTrue(entered.await(2,TimeUnit.SECONDS),"Branches must really overlap");return id+" output";}
            assertEquals(JSON.readTree("{\"left\":\"left output\",\"right\":\"right output\"}"),JSON.readTree(prior));return "combined";
        },(type,data)->{},pool);
        assertEquals("combined",output);assertEquals(3,called.size());assertEquals("answer",called.get(2));
    }
    @Test void onlySelectedConditionBranchCallsTheModelAndJoinDoesNotWaitForSkippedBranch()throws Exception{
        for(boolean match:List.of(true,false)){
            List<String> called=new CopyOnWriteArrayList<>(),skipped=new CopyOnWriteArrayList<>();
            String selected=match?"report":"chat",ignored=match?"chat":"report";
            String output=WorkflowExecutor.execute(condition(),match?"生成报告":"回答问题",(node,prior,first)->{called.add(node.path("id").asText());return node.path("id").asText();},
                (type,data)->{if(type.equals("node.skipped"))skipped.add((String)data.get("nodeId"));},pool);
            assertEquals(selected,output);assertEquals(List.of(selected),called);assertEquals(List.of(ignored),skipped);
        }
    }
    @Test void nestedParallelUnderAnInactiveBranchIsSkippedTransitively()throws Exception{
        ObjectNode task=condition();ArrayNode nodes=(ArrayNode)task.path("nodes"),edges=(ArrayNode)task.path("workflow").path("edges");
        ((ObjectNode)nodes.get(1)).put("kind","parallel");node(nodes,"left","llm");node(nodes,"right","llm");node(nodes,"merge","merge");
        ((ObjectNode)edges.get(3)).put("source","merge");edge(edges,"report","left");edge(edges,"report","right");edge(edges,"left","merge");edge(edges,"right","merge");
        List<String> called=new CopyOnWriteArrayList<>(),skipped=new CopyOnWriteArrayList<>();
        String result=WorkflowExecutor.execute(task,"普通问题",(node,prior,first)->{called.add(node.path("id").asText());return "answer";},(type,data)->{if(type.equals("node.skipped"))skipped.add((String)data.get("nodeId"));},pool);
        assertEquals("answer",result);assertEquals(List.of("chat"),called);assertTrue(skipped.containsAll(List.of("report","left","right","merge")));
    }
    @Test void failedBranchInterruptsOtherBranchAndNeverExecutesDownstream()throws Exception{
        CountDownLatch entered=new CountDownLatch(2),interrupted=new CountDownLatch(1);
        IOException error=assertThrows(IOException.class,()->WorkflowExecutor.execute(parallel(),"input",(node,prior,first)->{
            String id=node.path("id").asText();assertNotEquals("answer",id);entered.countDown();assertTrue(entered.await(2,TimeUnit.SECONDS));
            if(id.equals("left"))throw new IOException("left failed");
            try{new CountDownLatch(1).await();}catch(InterruptedException failure){interrupted.countDown();throw failure;}return "unreachable";
        },(type,data)->{},pool));
        assertEquals("left failed",error.getMessage());assertTrue(interrupted.await(2,TimeUnit.SECONDS));
    }
    @Test void numericalJsonConditionsAndInvalidInputHaveDefinedBehavior()throws Exception{
        ObjectNode rule=JSON.createObjectNode().put("source","previous").put("field","risk.score").put("operator","gt").put("value","0.5");
        assertTrue(WorkflowExecutor.matches(rule,"ignored","{\"risk\":{\"score\":0.9}}"));
        assertFalse(WorkflowExecutor.matches(rule,"ignored","{\"risk\":{\"score\":0.1}}"));
        assertThrows(IOException.class,()->WorkflowExecutor.matches(rule,"ignored","not json"));
        rule.put("operator","is_empty");assertTrue(WorkflowExecutor.matches(rule,"ignored","{}"));
    }
    @Test void cycleDisconnectedNodesAndInvalidConditionPortsCannotPublish(){
        ObjectNode task=parallel();ArrayNode edges=(ArrayNode)task.path("workflow").path("edges");edge(edges,"answer","fork");
        assertThrows(FusionFault.class,()->WorkflowGraph.validate(task,true));
        ObjectNode detached=parallel();((ArrayNode)detached.path("workflow").path("edges")).remove(2);
        assertDoesNotThrow(()->WorkflowGraph.validate(detached,false));assertThrows(FusionFault.class,()->WorkflowGraph.validate(detached,true));
        ObjectNode routing=condition();((ObjectNode)routing.at("/workflow/edges/2")).put("sourceHandle","true");
        assertThrows(FusionFault.class,()->WorkflowGraph.validate(routing,false));
    }
    @Test void legacySequentialTasksAreAcceptedButRoutingCannotSilentlyFallBackToSequential(){
        ObjectNode task=parallel();task.remove("workflow");assertThrows(FusionFault.class,()->WorkflowGraph.validate(task,false));
        for(JsonNode node:task.path("nodes"))((ObjectNode)node).remove("kind");assertDoesNotThrow(()->WorkflowGraph.validate(task,true));
    }
}
