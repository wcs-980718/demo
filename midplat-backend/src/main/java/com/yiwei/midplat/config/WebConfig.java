package com.yiwei.midplat.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
class WebConfig {
    private final String[] allowedOrigins;

    WebConfig(@Value("${midplat.fusion.web-origins:http://127.0.0.1:8000,http://localhost:8000,http://portal.example.com:30200,http://portal.example.com:31010}") String origins) {
        allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toArray(String[]::new);
        if (allowedOrigins.length == 0 || Arrays.stream(allowedOrigins).anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException("midplat.fusion.web-origins must contain explicit trusted origins for credentialed browser requests");
        }
    }

    @Bean
    FilterRegistrationBean<CorsFilter> apiCorsFilter() {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(allowedOrigins));
        cors.setAllowCredentials(true);
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        var registration = new FilterRegistrationBean<>(new CorsFilter(source));
        // Authentication errors must retain CORS headers so browsers can renew expired sessions.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
