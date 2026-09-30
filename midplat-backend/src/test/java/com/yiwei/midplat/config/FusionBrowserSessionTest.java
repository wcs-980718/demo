package com.yiwei.midplat.config;

import com.yiwei.midplat.fusion.FusionAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = FusionBrowserSessionTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"midplat.fusion.enabled=true", "midplat.fusion.intranet-management=true",
                "midplat.fusion.service-token=browser-test-token-01234567890123456789",
                "midplat.fusion.web-origins=http://portal.example.com:30200"})
class FusionBrowserSessionTest {
    private static final String ORIGIN = "http://portal.example.com:30200";
    @Autowired TestRestTemplate client;

    @BeforeEach
    void sendBrowserOriginHeaders() {
        client.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String cookie, String csrf) {
        var headers = new HttpHeaders();
        headers.setOrigin(ORIGIN);
        if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
        if (csrf != null) headers.set("X-Fusion-CSRF", csrf);
        return client.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    @Test
    void portalSessionCookieCannotReplaceTheMiddlePlatformSession() {
        var session = call(HttpMethod.GET, "/api/fusion/session", "JSESSIONID=portal-session", null);
        assertEquals(HttpStatus.OK, session.getStatusCode());
        var cookie = session.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("MIDPLAT_SESSION="), "Middle platform needs a distinct cookie name");
        assertTrue(cookie.contains("HttpOnly"));
        var response = call(HttpMethod.GET, "/api/fusion/projects", "JSESSIONID=changed-portal-session; " + cookie.split(";", 2)[0], null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void missingSessionReturnsReadable401ToTheTrustedBrowser() {
        var response = call(HttpMethod.GET, "/api/fusion/projects", null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(ORIGIN, response.getHeaders().getAccessControlAllowOrigin());
        assertTrue(response.getHeaders().getAccessControlAllowCredentials());
    }

    @Test
    void invalidCsrfReturnsReadable403AndDoesNotReachTheController() {
        var session = call(HttpMethod.GET, "/api/fusion/session", null, null);
        var cookie = session.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0];
        var response = call(HttpMethod.PUT, "/api/fusion/projects", cookie, "invalid-csrf");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(ORIGIN, response.getHeaders().getAccessControlAllowOrigin());
        assertTrue(response.getBody().contains("Invalid CSRF token"));
    }

    @Test
    void untrustedBrowserStillCannotEstablishASession() {
        var headers = new HttpHeaders();
        headers.setOrigin("https://untrusted.example");
        var response = client.exchange("/api/fusion/session", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getHeaders().getAccessControlAllowOrigin());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({WebConfig.class, FusionAccess.class, ProbeController.class})
    static class Application {}

    @RestController
    static class ProbeController {
        @GetMapping("/api/fusion/session")
        Map<String, Object> session(HttpServletRequest request) {
            return Map.of("csrf", request.getSession().getAttribute("fusion.csrf"));
        }
        @RequestMapping(path = "/api/fusion/projects", method = {RequestMethod.GET, RequestMethod.PUT})
        String projects() { return "controller reached"; }
    }
}
