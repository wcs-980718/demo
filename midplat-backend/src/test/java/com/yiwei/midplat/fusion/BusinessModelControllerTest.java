package com.yiwei.midplat.fusion;

import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.PlatformCredentialService;
import com.yiwei.midplat.platform.PlatformCredentialService.CallerPlatform;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BusinessModelControllerTest {
    @Test void relativeTimeoutHeaderIsBoundWithoutRequiringACallerClock() throws Exception {
        var credentials=mock(PlatformCredentialService.class);var service=mock(BusinessModelService.class);var project=mock(ManagedPlatform.class);
        var caller=new CallerPlatform(project,"client-test",null);
        when(credentials.requireCallerManagedPlatform("Bearer project-token")).thenReturn(caller);
        var mvc=MockMvcBuilders.standaloneSetup(new BusinessModelController(credentials,service)).build();
        mvc.perform(post("/api/runtime/agent/tasks/answer/v1/chat/completions")
            .header("Authorization","Bearer project-token").header("X-Request-Id","one-call")
            .header("X-Request-Timeout-Ms","119000").contentType("application/json").content("{\"messages\":[{\"role\":\"user\",\"content\":\"query\"}]}"))
            .andExpect(status().isOk());
        verify(service).chat(eq(project),eq("client-test"),eq("answer"),eq("one-call"),any(),any(),isNull(),eq(119000L));
        clearInvocations(service);
        mvc.perform(post("/api/runtime/agent/tasks/answer/v1/chat/completions")
            .header("X-Request-Timeout-Ms","invalid").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
