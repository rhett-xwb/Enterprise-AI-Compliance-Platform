package com.enterprise.compliance.health;

import com.enterprise.compliance.common.ApiResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/health")
    ResponseEntity<?> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(ApiResponse.ok("UP"));
        } catch (DataAccessException ex) {
            return ResponseEntity.status(503).body(ApiResponse.error(503, "Service unavailable"));
        }
    }
}
