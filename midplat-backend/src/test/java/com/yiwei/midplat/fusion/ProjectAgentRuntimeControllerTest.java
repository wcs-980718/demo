package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.common.api.UnauthorizedException;
import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.ManagedPlatformRepository;
import com.yiwei.midplat.platform.PlatformCredentialService;
import com.yiwei.midplat.platform.PlatformCredentialService.CallerPlatform;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectAgentRuntimeControllerTest {
    final ObjectMapper json=new ObjectMapper();
    private CallerPlatform caller(ManagedPlatform project){return new CallerPlatform(project,"client-test",null);}
    @Test void credentialSelectsProjectAndRunPayloadIsForwardedUnchanged() throws Exception {
        var credentials=mock(PlatformCredentialService.class);var bridge=mock(FusionRuntimeBridge.class);var project=mock(ManagedPlatform.class);
        when(credentials.requireCallerManagedPlatform("Bearer project-token")).thenReturn(caller(project));
        var payload=json.readTree("{\"input\":\"synthetic input\",\"taskKey\":\"quality_check\",\"idempotencyKey\":\"one-request\"}");
        when(bridge.command(caller(project),"startRun",payload)).thenReturn(json.readTree("{\"id\":\"run-1\",\"session_id\":\"session-1\",\"status\":\"queued\"}"));
        var mvc=MockMvcBuilders.standaloneSetup(new ProjectAgentRuntimeController(credentials,bridge)).build();
        mvc.perform(post("/api/runtime/agent/runs").header("Authorization","Bearer project-token").contentType("application/json").content(payload.toString()))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("run-1")).andExpect(jsonPath("$.status").value("queued"));
        verify(bridge).command(caller(project),"startRun",payload);
    }
    @Test void resultCursorCancellationAndSessionCloseKeepTheCredentialScope() throws Exception {
        var credentials=mock(PlatformCredentialService.class);var bridge=mock(FusionRuntimeBridge.class);var project=mock(ManagedPlatform.class);var gateway=mock(ModelInvocationGateway.class);
        when(credentials.requireCallerManagedPlatform("Bearer project-token")).thenReturn(caller(project));
        var controller=new ProjectAgentRuntimeController(credentials,bridge);ReflectionTestUtils.setField(controller,"modelGateway",gateway);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/runtime/agent/runs/run-1").header("Authorization","Bearer project-token")).andExpect(status().isOk());
        mvc.perform(get("/api/runtime/agent/runs/run-1/events?after=200").header("Authorization","Bearer project-token")).andExpect(status().isOk());
        mvc.perform(post("/api/runtime/agent/runs/run-1/cancel").header("Authorization","Bearer project-token")).andExpect(status().isOk());
        mvc.perform(post("/api/runtime/agent/sessions/session-1/close").header("Authorization","Bearer project-token")).andExpect(status().isOk());
        verify(bridge).command(caller(project),"run",Map.of("id","run-1"));verify(bridge).command(caller(project),"events",Map.of("id","run-1","after",200L));
        verify(bridge).command(caller(project),"cancelRun",Map.of("id","run-1"));verify(gateway).cancelAuthorizedRun("run-1");
        verify(bridge).command(caller(project),"closeSession",Map.of("id","session-1"));
    }
    @Test void invalidCredentialAndSessionIdentifierCannotReachRuntime() {
        var credentials=mock(PlatformCredentialService.class);var bridge=mock(FusionRuntimeBridge.class);
        when(credentials.requireCallerManagedPlatform(null)).thenThrow(new UnauthorizedException("缺少中台凭证"));
        var controller=new ProjectAgentRuntimeController(credentials,bridge);
        assertThrows(UnauthorizedException.class,()->controller.closeSession(null,"session-1"));
        assertThrows(IllegalArgumentException.class,()->controller.closeSession("Bearer token","invalid/session"));
        verifyNoInteractions(bridge);
    }
    @Test void statusExposesTheConfiguredBusinessEnvironment() throws Exception {
        var controller=new FusionController(mock(FusionAccess.class),mock(FusionState.class),mock(FusionAgentClient.class),mock(FusionCatalog.class),mock(ManagedPlatformRepository.class));
        ReflectionTestUtils.setField(controller,"runtimeEnvironment","staging");ReflectionTestUtils.setField(controller,"executionEnabled",true);
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/api/fusion/status"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.runtimeEnvironment").value("staging")).andExpect(jsonPath("$.executionEnabled").value(true));
    }
}
