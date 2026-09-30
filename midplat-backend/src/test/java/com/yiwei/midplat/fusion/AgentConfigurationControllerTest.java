package com.yiwei.midplat.fusion;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yiwei.midplat.common.api.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AgentConfigurationControllerTest {
 final ObjectMapper json=new ObjectMapper();
 final FusionState state=mock(FusionState.class);
 final FusionAgentClient agent=mock(FusionAgentClient.class);
 final FusionController catalog=new FusionController(null,state,agent,null,null);
 final ProjectAgentBindingController controller=new ProjectAgentBindingController(agent,null,null,state);
 MockHttpServletRequest request(boolean admin){var request=new MockHttpServletRequest();request.setAttribute("fusion.identity",new FusionAccess.Identity("user",Set.of("mine"),true,admin));return request;}
 @Test void globalCatalogIsAdminOnlyWhileProjectCatalogRetainsProjectAccess(){
  var data=Map.<String,Object>of("models",List.of(),"availableModels",List.of(),"prompts",List.of());when(state.configurationCatalog()).thenReturn(data);
  assertEquals(data,catalog.agentCatalog(request(true)));
  assertThrows(ForbiddenException.class,()->catalog.agentCatalog(request(false)));
  assertEquals(data,catalog.catalog(request(false),"mine","development"));
  assertThrows(ForbiddenException.class,()->catalog.catalog(request(false),"other","development"));
  assertThrows(ForbiddenException.class,()->catalog.agentCatalog(new MockHttpServletRequest()));
 }
 @Test void oldGrantEndpointsAreExplicitlyGoneAndNeverWriteState(){
  assertEquals(410,assertThrows(FusionUpstreamFault.class,()->catalog.grant(request(true),"mine","model","development",json.createObjectNode())).status());
  assertEquals(410,assertThrows(FusionUpstreamFault.class,()->catalog.grantPrompt(request(true),"mine","prompt","development",json.createObjectNode())).status());
  verifyNoInteractions(state,agent);
 }
 @Test void invalidConfigurationCannotBeSavedOrPublishedThroughIndependentApi(){
  ObjectNode draft=json.createObjectNode(),payload=json.createObjectNode();payload.set("draft",draft);
  doThrow(new IllegalArgumentException("请选择默认模型")).when(state).validateAgentConfiguration(draft);
  assertThrows(IllegalArgumentException.class,()->controller.saveAgentDraft(request(true),"agent",payload));verifyNoInteractions(agent);
  ObjectNode current=json.createObjectNode().put("expectedRevision",3);current.set("draft",draft);
  when(agent.callAgent(any(),eq("agentDraft"),any())).thenReturn(current);
  assertThrows(IllegalArgumentException.class,()->controller.publishAgentVersion(request(true),"agent",new ProjectAgentBindingController.PublishAgentCmd(3,"note","key")));
  verify(agent,never()).callAgent(any(),eq("publishAgentVersion"),any());
 }
 @Test void idempotentReplayIsForwardedWithoutRevalidatingChangedResourceAvailability(){
  ObjectNode current=json.createObjectNode().put("expectedRevision",3).put("publicationExists",true);current.set("draft",json.createObjectNode());
  when(agent.callAgent(any(),eq("agentDraft"),any())).thenReturn(current);
  controller.publishAgentVersion(request(true),"agent",new ProjectAgentBindingController.PublishAgentCmd(3,"note","key"));
  verifyNoInteractions(state);
  verify(agent).callAgent(any(),eq("publishAgentVersion"),eq(Map.of("agentId","agent","expectedRevision",3L,"note","note","idempotencyKey","key")));
 }
}
