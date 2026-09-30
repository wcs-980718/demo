package com.agenthubfusion.runtime;

import com.agenthubfusion.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real child JVM and interleaved SSE through a deterministic local model gateway. */
@Timeout(25)
class WorkflowRuntimeIntegrationTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    RuntimeUnits units;HttpServer gateway;ExecutorService serverPool;
    Queue<JsonNode> requests=new ConcurrentLinkedQueue<>();CountDownLatch branches,opened;boolean blockModels;
    @BeforeEach void setup()throws Exception{
        gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);serverPool=Executors.newCachedThreadPool();gateway.setExecutor(serverPool);
        gateway.createContext("/api/fusion-internal/model-invocations/cancel",exchange->{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));exchange.close();});
        gateway.createContext("/api/fusion-internal/model-invocations",exchange->{
            JsonNode request=json.readTree(exchange.getRequestBody());requests.add(request);String id=request.path("nodeId").asText();
            if(branches!=null&&Set.of("left","right").contains(id)){
                branches.countDown();try{if(!branches.await(3,TimeUnit.SECONDS)){exchange.sendResponseHeaders(500,-1);exchange.close();return;}}catch(InterruptedException error){exchange.close();return;}
            }
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()){
                String frame=json.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("content",id+"-result"),"finish_reason","stop"))));
                output.write(("data: "+frame+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();
                if(blockModels){
                    opened.countDown();
                    try{for(int index=0;index<100;index++){Thread.sleep(50);output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));output.flush();}}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                    catch(java.io.IOException disconnected){return;}
                }
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));output.flush();
            }
        });gateway.start();
        units=new RuntimeUnits(json,directory.toString(),"http://127.0.0.1:"+gateway.getAddress().getPort(),"",2);
        org.springframework.test.util.ReflectionTestUtils.setField(units,"selfUrl","http://127.0.0.1:"+gateway.getAddress().getPort());
    }
    @AfterEach void cleanup(){if(units!=null)units.stop();if(gateway!=null)gateway.stop(0);if(serverPool!=null)serverPool.shutdownNow();}
    RuntimeUnits.Unit unit(ObjectNode task){
        task.put("modelRevisionId","m1");task.putArray("assetRevisionIds");task.putArray("systemInstructions").add("task role");
        for(JsonNode item:task.path("nodes"))if(WorkflowGraph.isModel(item)){ObjectNode node=(ObjectNode)item;node.put("modelRevisionId","m1");node.putArray("systemInstructions").add("node: "+node.path("name").asText());}
        ObjectNode snapshot=json.createObjectNode();snapshot.putObject("draft").put("temperature",0.4);snapshot.putObject("assets");snapshot.putArray("tasks").add(task);
        String hash=EffectiveConfigCompiler.hash(snapshot.toString());return units.prepare(UUID.randomUUID().toString(),hash,snapshot);
    }
    List<Map.Entry<String,JsonNode>> run(RuntimeUnits.Unit unit,String input)throws Exception{
        var response=units.run(unit,Map.of("runId",UUID.randomUUID().toString(),"taskKey","main","input",input,"ticket","local-test-ticket","history",List.of()));
        assertEquals(200,response.statusCode());String body;
        try(var stream=response.body()){body=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        List<Map.Entry<String,JsonNode>> events=new ArrayList<>();
        for(String frame:body.split("\n\n")){
            if(frame.isBlank())continue;String[] lines=frame.split("\n");assertEquals(2,lines.length,"Concurrent SSE frames must never interleave");
            assertTrue(lines[0].startsWith("event: "));assertTrue(lines[1].startsWith("data: "));
            events.add(Map.entry(lines[0].substring(7),json.readTree(lines[1].substring(6))));
        }
        return events;
    }
    @Test void realRuntimeExecutesParallelModelsAndFeedsMergedResultsToTheFinalModel()throws Exception{
        branches=new CountDownLatch(2);ObjectNode task=WorkflowExecutorTest.parallel();
        // Storage order is deliberately reversed to ensure the runtime follows edges.
        ArrayNode reversed=json.createArrayNode();for(int index=task.path("nodes").size()-1;index>=0;index--)reversed.add(task.path("nodes").get(index));task.set("nodes",reversed);
        var events=run(unit(task),"analyze this");var last=events.get(events.size()-1);
        assertEquals("completed",last.getKey());assertEquals("answer-result",last.getValue().path("output").asText());assertEquals(3,requests.size());
        JsonNode answer=requests.stream().filter(request->request.path("nodeId").asText().equals("answer")).findFirst().orElseThrow();
        String messages=answer.path("messages").toString();assertTrue(messages.contains("left-result"));assertTrue(messages.contains("right-result"));
        long firstCompletion=events.stream().takeWhile(event->!event.getKey().equals("node.completed")||!Set.of("left","right").contains(event.getValue().path("nodeId").asText())).count();
        assertEquals(2,events.subList(0,(int)firstCompletion).stream().filter(event->event.getKey().equals("node.started")&&Set.of("left","right").contains(event.getValue().path("nodeId").asText())).count());
    }
    @Test void realRuntimeSelectsBothConditionOutcomesOnSeparateRuns()throws Exception{
        var unit=unit(WorkflowExecutorTest.condition());
        for(String input:List.of("生成报告","普通问题")){
            var events=run(unit,input);String expected=input.contains("报告")?"report":"chat";
            assertEquals(expected+"-result",events.get(events.size()-1).getValue().path("output").asText());
            assertEquals(1,events.stream().filter(event->event.getKey().equals("node.skipped")).count());
            assertEquals(1,events.stream().filter(event->event.getKey().equals("branch.selected")).count());
        }
        assertEquals(Set.of("report","chat"),requests.stream().map(request->request.path("nodeId").asText()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void cancellingAParallelRunClosesAllBranchesAndDoesNotExecuteItsJoin()throws Exception{
        branches=new CountDownLatch(2);opened=new CountDownLatch(2);blockModels=true;
        var unit=unit(WorkflowExecutorTest.parallel());String id=UUID.randomUUID().toString();
        var response=units.run(unit,Map.of("runId",id,"taskKey","main","input","cancel this","ticket","local-test-ticket","history",List.of()));
        assertTrue(opened.await(4,TimeUnit.SECONDS));units.cancel(unit.releaseId(),id);
        String body;try(var stream=response.body()){body=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        assertTrue(body.contains("event: cancelled"),body);assertFalse(body.contains("event: completed"),body);
        assertEquals(Set.of("left","right"),requests.stream().map(request->request.path("nodeId").asText()).collect(java.util.stream.Collectors.toSet()));
    }
}
