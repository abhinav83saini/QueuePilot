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
                SELECT w.worker_id, w.name, w.health_status, w.capacity,
                       w.last_heartbeat, w.created_at,
                       COALESCE(string_agg(wc.capability, ', ' ORDER BY wc.capability), '') AS capabilities,
                       COALESCE(active.cnt, 0) AS active_jobs
                FROM workers w
                LEFT JOIN worker_capabilities wc ON wc.worker_id = w.worker_id
                LEFT JOIN (
                    SELECT worker_id, COUNT(*) AS cnt
                    FROM execution_attempts
                    WHERE status = 'RUNNING'
                    GROUP BY worker_id
                ) active ON active.worker_id = w.worker_id
                GROUP BY w.worker_id, w.name, w.health_status, w.capacity, w.last_heartbeat, w.created_at, active.cnt
                ORDER BY w.worker_id ASC
                """);
    }

    @PutMapping("/workers/{workerId}/health")
    public ResponseEntity<?> setWorkerHealth(
            @PathVariable Long workerId,
            @RequestParam String status) {
        String health = status.trim().toUpperCase();

        if (!Set.of("HEALTHY", "UNHEALTHY", "OFFLINE").contains(health)) {
            return ResponseEntity.badRequest()
                    .body("Health status must be HEALTHY, UNHEALTHY, or OFFLINE");
        }

        int updated = jdbc.update("""
                UPDATE workers
                SET health_status = ?, last_heartbeat = NOW()
                WHERE worker_id = ?
                """, health, workerId);

        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "workerId", workerId,
                "healthStatus", health
        ));
    }

    @PutMapping("/workers/{workerId}/capacity")
    public ResponseEntity<?> setWorkerCapacity(
            @PathVariable Long workerId,
            @RequestParam int capacity) {
        if (capacity <= 0) {
            return ResponseEntity.badRequest().body("Capacity must be positive");
        }

        int updated = jdbc.update("""
                UPDATE workers
                SET capacity = ?
                WHERE worker_id = ?
                """, capacity, workerId);

        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "workerId", workerId,
                "capacity", capacity
        ));
    }

    @PostMapping("/workers/{workerId}/capabilities")
    public ResponseEntity<?> addWorkerCapability(
            @PathVariable Long workerId,
            @RequestParam String capability) {
        String cap = capability.trim().toUpperCase();
        if (cap.isBlank()) {
            return ResponseEntity.badRequest().body("Capability cannot be blank");
        }

        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM workers WHERE worker_id = ?)", Boolean.class, workerId);

        if (!Boolean.TRUE.equals(exists)) {
            return ResponseEntity.notFound().build();
        }

        jdbc.update("""
                INSERT INTO worker_capabilities (worker_id, capability)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, workerId, cap);

        return ResponseEntity.ok(Map.of(
                "workerId", workerId,
                "capability", cap
        ));
    }
}
