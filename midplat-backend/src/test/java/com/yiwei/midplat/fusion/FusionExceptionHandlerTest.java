package com.yiwei.midplat.fusion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class FusionExceptionHandlerTest {
    @Test
    void preservesGatewayTimeoutAtHttpBoundary() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new TimeoutController())
                .setControllerAdvice(new FusionExceptionHandler()).build();
        mvc.perform(get("/deadline-regression"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value(504))
                .andExpect(jsonPath("$.detail").value("模型调用已超过执行时限"));
    }

    @Test
    void preservesGoneForRetiredGrantEndpoints() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GoneController())
                .setControllerAdvice(new FusionExceptionHandler()).build();
        mvc.perform(get("/gone-regression"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.detail").value("项目模型授权接口已下线"));
    }

    @RestController
    static class TimeoutController {
        @GetMapping("/deadline-regression")
        void timeout() {
            throw new FusionUpstreamFault(504, "模型调用已超过执行时限");
        }
    }

    @RestController
    static class GoneController {
        @GetMapping("/gone-regression")
        void gone() {
            throw new FusionUpstreamFault(410, "项目模型授权接口已下线");
        }
    }
}
