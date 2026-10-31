
package com.queuepilot.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final JdbcTemplate jdbc;

    public SchedulerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/policy")
    public Map<String, String> getPolicy() {
        List<String> policies = jdbc.query(
                """
                SELECT active_policy
                FROM scheduler_settings
                ORDER BY setting_id
                LIMIT 1
                """,
                (rs, row) -> rs.getString(1)
        );

        return Map.of(
                "activePolicy",
                policies.isEmpty() ? "FCFS" : policies.get(0)
        );
    }

    @PutMapping("/policy")
    public ResponseEntity<?> setPolicy(@RequestParam String policy) {
        String selected = policy.trim().toUpperCase();

        if ("PRIORITY".equals(selected)) {
            selected = "PRIORITY_AGING";
        }

        if (!Set.of("FCFS", "PRIORITY_AGING", "SJF").contains(selected)) {
            return ResponseEntity.badRequest()
                    .body("Policy must be FCFS, PRIORITY_AGING (or PRIORITY), or SJF");
        }

        int updated = jdbc.update("""
                UPDATE scheduler_settings
                SET active_policy = ?, updated_at = NOW()
                WHERE setting_id = 1
                """, selected);

        if (updated == 0) {
            return ResponseEntity.internalServerError()
                    .body("No scheduler settings row exists with setting_id = 1");
        }

        return ResponseEntity.ok(Map.of("activePolicy", selected));
    }

    @GetMapping("/workers")
    public List<Map<String, Object>> getWorkers() {
        return jdbc.queryForList("""
                SELECT worker_id, name, health_status,
                       capacity, last_heartbeat, created_at
                FROM workers
                ORDER BY worker_id DESC
                """);
    }
}
