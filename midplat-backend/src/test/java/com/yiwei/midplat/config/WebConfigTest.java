package com.yiwei.midplat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(WebConfigTest.TestWebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = "midplat.fusion.web-origins=http://portal.example.com:30200,http://portal.example.com:31010")
class WebConfigTest {
    private static final String PORTAL_ORIGIN = "http://portal.example.com:30200";
    @Autowired WebApplicationContext context;
    @Autowired FilterRegistrationBean<CorsFilter> cors;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(cors.getFilter()).build();
    }

    @Test
    void portalCanReadApiWithBrowserCredentials() throws Exception {
        mvc.perform(get("/api/cors-probe").header(HttpHeaders.ORIGIN, PORTAL_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PORTAL_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void standaloneMidplatOriginRemainsAllowed() throws Exception {
        String origin = "http://portal.example.com:31010";
        mvc.perform(get("/api/cors-probe").header(HttpHeaders.ORIGIN, origin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void portalPreflightAllowsManagementCsrfHeader() throws Exception {
        mvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, PORTAL_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Fusion-CSRF,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PORTAL_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("X-Fusion-CSRF")));
    }

    @Test
    void unconfiguredOriginCannotReadCredentialedApi() throws Exception {
        mvc.perform(get("/api/cors-probe").header(HttpHeaders.ORIGIN, "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void serverToServerCallDoesNotRequireBrowserOrigin() throws Exception {
        mvc.perform(get("/api/cors-probe"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void credentialedCorsRejectsWildcardOrEmptyConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new WebConfig("*"));
        assertThrows(IllegalArgumentException.class, () -> new WebConfig(" , "));
    }

    @Configuration
    @EnableWebMvc
    @Import(WebConfig.class)
    static class TestWebConfiguration {
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @RestController
    static class ProbeController {
        @RequestMapping(path = "/api/cors-probe", method = {RequestMethod.GET, RequestMethod.PUT})
        String probe() { return "ok"; }
    }
}
