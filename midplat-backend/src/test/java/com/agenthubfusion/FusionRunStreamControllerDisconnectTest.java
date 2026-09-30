package com.agenthubfusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.fusion.FusionAccess;
import com.yiwei.midplat.fusion.FusionAgentClient;
import com.yiwei.midplat.fusion.FusionRunStreamController;
import com.yiwei.midplat.fusion.FusionState;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** F14/R08: an observer disconnect must only remove the observer, never cancel the business run. */
class FusionRunStreamControllerDisconnectTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void observerDisconnectDoesNotCancelTheBusinessRun()throws Exception{
        FusionState state=mock(FusionState.class);FusionAgentClient agent=mock(FusionAgentClient.class);
        when(state.binding("project-a","development")).thenReturn(Map.of("status","ready","deployment_id","dep-1"));
        when(agent.call(any(),eq("project-a"),eq("development"),eq("dep-1"),eq("run"),any())).thenReturn(json.readTree("{\"id\":\"run-1\",\"status\":\"running\",\"last_event\":0}"));
        when(agent.call(any(),eq("project-a"),eq("development"),eq("dep-1"),eq("events"),any())).thenReturn(json.createArrayNode());
        var controller=new FusionRunStreamController(state,agent);
        var request=new MockHttpServletRequest();
        request.setAttribute("fusion.identity",new FusionAccess.Identity("service:project-a",Set.of("project-a"),true,false));
        ResponseEntity<StreamingResponseBody> response=controller.stream(request,"project-a","run-1","development",0);
        OutputStream broken=new OutputStream(){public void write(int value)throws IOException{throw new IOException("client gone");}};
        response.getBody().writeTo(broken);
        verify(agent,atLeastOnce()).call(any(),eq("project-a"),eq("development"),eq("dep-1"),eq("run"),any());
        verify(agent,atLeastOnce()).call(any(),eq("project-a"),eq("development"),eq("dep-1"),eq("events"),any());
        verify(agent,never()).call(any(),eq("project-a"),eq("development"),eq("dep-1"),eq("cancelRun"),any());
    }
}