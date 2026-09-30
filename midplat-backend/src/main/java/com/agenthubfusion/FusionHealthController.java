package com.agenthubfusion;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Readiness for the internal runtime container; no configuration or credentials are returned. */
@RestController
public class FusionHealthController {
    private final JdbcTemplate database;

    public FusionHealthController(JdbcTemplate database) {
        this.database = database;
    }

    @GetMapping("/internal/fusion/health/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/internal/fusion/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            database.queryForObject("select count(*) from fusion_deployment where 1=0", Long.class);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (RuntimeException unavailable) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
        }
    }
}
