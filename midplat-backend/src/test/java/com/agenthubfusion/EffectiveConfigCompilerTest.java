package com.agenthubfusion;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class EffectiveConfigCompilerTest {
 @Test void resolvesEveryModelRevisionAndPreservesNodeRulesExactlyOnce()throws Exception{
  ObjectMapper json=new ObjectMapper();AtomicReference<JsonNode> request=new AtomicReference<>();
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/fusion-internal/catalog/resolve",exchange->{
   request.set(json.readTree(exchange.getRequestBody()));assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
   byte[] response="{\"models\":{\"m1\":{},\"m2\":{},\"m3\":{}},\"prompts\":{\"p1\":{\"content\":{\"body\":\"fixed-template-role\"}}}}".getBytes(StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
  });server.start();
  try{
   var compiler=new EffectiveConfigCompiler(json,"http://127.0.0.1:"+server.getAddress().getPort(),"test-token");
   JsonNode draft=json.readTree("""
    {"defaultModelRevisionId":"m1","temperature":0.4,"role":{"kind":"template","revisionId":"p1"},"tasks":[
      {"key":"generic","name":"Generic","instructions":"task-rule","modelRevisionId":"m2","nodes":[{"id":"n1","name":"N1","instructions":"node-rule","modelRevisionId":"m3"}]},
      {"key":"other","name":"Other","instructions":"other-rule","nodes":[]}]}
    """);
   var compiled=compiler.compile("project","staging","deployment","definition",draft);
   assertEquals("m2",compiled.at("/tasks/0/modelRevisionId").asText());assertEquals("m3",compiled.at("/tasks/0/nodes/0/modelRevisionId").asText());assertEquals("m1",compiled.at("/tasks/1/modelRevisionId").asText());
   assertEquals(json.readTree("[\"fixed-template-role\",\"task-rule\",\"node-rule\"]"),compiled.at("/tasks/0/nodes/0/systemInstructions"));
   assertEquals(3,request.get().path("modelRevisionIds").size());assertEquals("staging",request.get().path("environment").asText());
   assertFalse(compiled.toString().contains("test-token"));
  }finally{server.stop(0);}
 }
 @Test void unsupportedOrUndeclaredCapabilitiesFailBeforePublication()throws Exception{
  ObjectMapper json=new ObjectMapper();AtomicReference<String> caps=new AtomicReference<>("{}");AtomicReference<String> liveCaps=new AtomicReference<>("{\"toolCalls\":true,\"jsonObject\":true}");
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/fusion-internal/catalog/resolve",exchange->{byte[] response=("{\"models\":{\"m1\":{\"content\":{\"model\":\"model-one\",\"capabilities\":"+caps.get()+"},\"currentCapabilities\":"+liveCaps.get()+"}},\"prompts\":{}}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
  try{
   var compiler=new EffectiveConfigCompiler(json,"http://127.0.0.1:"+server.getAddress().getPort(),"test-token");
   var draft=json.readTree("{\"defaultModelRevisionId\":\"m1\",\"role\":{\"kind\":\"inline\",\"body\":\"role\"},\"tasks\":[{\"key\":\"task\",\"name\":\"Task\",\"responseFormat\":\"json_object\",\"nodes\":[]}]}");
   assertEquals(422,assertThrows(FusionFault.class,()->compiler.compile("p","dev","d","definition",draft)).status);
   caps.set("{\"jsonObject\":true,\"toolCalls\":false}");assertDoesNotThrow(()->compiler.compile("p","dev","d","definition",draft));
   var assetService=org.mockito.Mockito.mock(AssetService.class);
   org.mockito.Mockito.when(assetService.resolve(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenReturn((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"asset-r1\":{\"kind\":\"knowledge\",\"content\":{\"toolName\":\"lookup\"}}}"));
   org.springframework.test.util.ReflectionTestUtils.setField(compiler,"assetService",assetService);
   ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("tasks").get(0)).putArray("assetRevisionIds").add("asset-r1");
   assertEquals(422,assertThrows(FusionFault.class,()->compiler.compile("p","dev","d","definition",draft)).status);
   caps.set("{\"jsonObject\":true,\"toolCalls\":true}");assertDoesNotThrow(()->compiler.compile("p","dev","d","definition",draft));
   liveCaps.set("{\"jsonObject\":false,\"toolCalls\":false}");assertEquals(422,assertThrows(FusionFault.class,()->compiler.compile("p","dev","d","definition",draft)).status);
  }finally{server.stop(0);}
 }
 @Test void canonicalHashesIgnoreObjectKeyOrderButPreserveContent()throws Exception{
  ObjectMapper json=new ObjectMapper();var a=EffectiveConfigCompiler.canonical(json.readTree("{\"b\":2,\"a\":1}"));var b=EffectiveConfigCompiler.canonical(json.readTree("{\"a\":1,\"b\":2}"));
  assertEquals(EffectiveConfigCompiler.hash(a.toString()),EffectiveConfigCompiler.hash(b.toString()));
  assertNotEquals(EffectiveConfigCompiler.hash(a.toString()),EffectiveConfigCompiler.hash("{}"));
 }
 @Test void graphSnapshotsRetainBranchRulesAndTicketsOnlyAuthorizeModelNodes()throws Exception{
  ObjectMapper json=new ObjectMapper();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/fusion-internal/catalog/resolve",exchange->{byte[] data="{\"models\":{\"m1\":{}},\"prompts\":{}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();});server.start();
  try{
   JsonNode draft=json.readTree("""
    {"defaultModelRevisionId":"m1","temperature":0.4,"role":{"kind":"inline","body":"role"},"tasks":[{"key":"main","name":"routing","instructions":"task","nodes":[
     {"id":"condition","name":"condition","kind":"condition","instructions":"","condition":{"source":"input","operator":"contains","value":"report"}},
     {"id":"yes","name":"yes","instructions":"yes rules"},{"id":"no","name":"no","instructions":"no rules"}],
     "workflow":{"version":1,"positions":[],"edges":[{"source":"__start__","target":"condition"},{"source":"condition","target":"yes","sourceHandle":"true"},{"source":"condition","target":"no","sourceHandle":"false"},{"source":"yes","target":"__end__"},{"source":"no","target":"__end__"}]}}]}
   """);
   var compiler=new EffectiveConfigCompiler(json,"http://127.0.0.1:"+server.getAddress().getPort(),"test-token");var result=compiler.compile("p","development","d","definition",draft);
   assertEquals(draft.at("/tasks/0/workflow"),result.at("/tasks/0/workflow"));assertEquals("report",result.at("/tasks/0/nodes/0/condition/value").asText());
   assertFalse(result.at("/tasks/0/nodes/0").has("modelRevisionId"));assertEquals("m1",result.at("/tasks/0/nodes/1/modelRevisionId").asText());
   var signer=new ExecutionSigner(json,"test-signing-key-01234567890123456789",true);
   String ticket=signer.sign(new FusionTypes.Context("user","p","development",true),"d","r","run",result.at("/tasks/0"));
   JsonNode claims=signer.verify("Bearer "+ticket);assertEquals(2,claims.path("nodes").size());assertFalse(claims.path("nodes").has("condition"));
  }finally{server.stop(0);}
 }
}
