
package com.queuepilot.controller;

import com.queuepilot.repository.JobRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobHistoryController {

    private final JdbcTemplate jdbc;
    private final JobRepository jobRepository;

    public JobHistoryController(
            JdbcTemplate jdbc,
            JobRepository jobRepository) {
        this.jdbc = jdbc;
        this.jobRepository = jobRepository;
    }

    @GetMapping("/{jobId}/attempts")
    public ResponseEntity<?> getAttempts(@PathVariable Long jobId) {
        if (!jobRepository.existsById(jobId)) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> attempts = jdbc.queryForList("""
                SELECT attempt_id, job_id, worker_id,
                       attempt_number, status, lease_expires_at,
                       started_at, finished_at,
                       output_data::text AS output_data,
                       error_message
                FROM execution_attempts
                WHERE job_id = ?
                ORDER BY attempt_number
                """, jobId);

        return ResponseEntity.ok(attempts);
    }

    @GetMapping("/{jobId}/events")
    public ResponseEntity<?> getEvents(@PathVariable Long jobId) {
        if (!jobRepository.existsById(jobId)) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_id, job_id, event_type,
                       details::text AS details, created_at
                FROM job_events
                WHERE job_id = ?
                ORDER BY created_at, event_id
                """, jobId);

        return ResponseEntity.ok(events);
    }
}
