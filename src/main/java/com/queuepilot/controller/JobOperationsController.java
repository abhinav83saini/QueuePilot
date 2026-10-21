
package com.queuepilot.controller;

import com.queuepilot.entity.Job;
import com.queuepilot.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobOperationsController {

    private final JdbcTemplate jdbc;
    private final JobRepository jobRepository;

    public JobOperationsController(
            JdbcTemplate jdbc,
            JobRepository jobRepository) {
        this.jdbc = jdbc;
        this.jobRepository = jobRepository;
    }

    @PostMapping("/{jobId}/retry")
    public ResponseEntity<?> retryJob(@PathVariable Long jobId) {
        var optional = jobRepository.findById(jobId);

        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Job job = optional.get();

        if (!"FAILED".equalsIgnoreCase(job.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Only failed jobs can be manually retried.");
        }

        Boolean failedPrerequisite = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM job_dependencies d
                    JOIN jobs prerequisite
                      ON prerequisite.job_id = d.depends_on_job_id
                    WHERE d.job_id = ?
                      AND prerequisite.status IN ('FAILED', 'CANCELLED')
                )
                """, Boolean.class, jobId);

        if (Boolean.TRUE.equals(failedPrerequisite)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("A prerequisite has failed or been cancelled.");
        }

        int updated = jdbc.update("""
                UPDATE jobs
                SET status = 'QUEUED',
                    progress = 0,
                    available_at = NOW(),
                    started_at = NULL,
                    completed_at = NULL,
                    error_message = NULL
                WHERE job_id = ? AND status = 'FAILED'
                """, jobId);

        if (updated == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Job status changed; refresh and try again.");
        }

        addEvent(jobId, "JOB_MANUAL_RETRY",
                "{\"source\":\"API\"}");

        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "status", "QUEUED",
                "message", "Job queued for manual retry"
        ));
    }

    @PostMapping("/{jobId}/dependencies")
    public ResponseEntity<?> addDependency(
            @PathVariable Long jobId,
            @RequestParam Long dependsOnJobId) {

        if (jobId.equals(dependsOnJobId)) {
            return ResponseEntity.badRequest()
                    .body("A job cannot depend on itself.");
        }

        var jobOptional = jobRepository.findById(jobId);

        if (jobOptional.isEmpty()
                || !jobRepository.existsById(dependsOnJobId)) {
            return ResponseEntity.notFound().build();
        }

        String jobStatus = jobOptional.get().getStatus();

        if (!"QUEUED".equalsIgnoreCase(jobStatus)
                && !"SCHEDULED".equalsIgnoreCase(jobStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Dependencies can only be added to queued or scheduled jobs.");
        }

        Boolean wouldCycle = jdbc.queryForObject("""
                WITH RECURSIVE dependency_walk(job_id) AS (
                    SELECT depends_on_job_id
                    FROM job_dependencies
                    WHERE job_id = ?

                    UNION

                    SELECT d.depends_on_job_id
                    FROM job_dependencies d
                    JOIN dependency_walk w ON d.job_id = w.job_id
                )
                SELECT EXISTS (
                    SELECT 1 FROM dependency_walk WHERE job_id = ?
                )
                """, Boolean.class, dependsOnJobId, jobId);

        if (Boolean.TRUE.equals(wouldCycle)) {
            return ResponseEntity.badRequest()
                    .body("This dependency would create a cycle.");
        }

        int inserted = jdbc.update("""
                INSERT INTO job_dependencies (job_id, depends_on_job_id)
                SELECT ?, ?
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM job_dependencies
                    WHERE job_id = ? AND depends_on_job_id = ?
                )
                """, jobId, dependsOnJobId, jobId, dependsOnJobId);

        if (inserted > 0) {
            addEvent(jobId, "JOB_DEPENDENCY_ADDED",
                    "{\"dependsOnJobId\":" + dependsOnJobId + "}");
        }

        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "dependsOnJobId", dependsOnJobId,
                "message", inserted > 0
                        ? "Dependency added" : "Dependency already exists"
        ));
    }

    @GetMapping("/{jobId}/dependencies")
    public ResponseEntity<?> getDependencies(@PathVariable Long jobId) {
        if (!jobRepository.existsById(jobId)) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> dependencies = jdbc.queryForList("""
                SELECT d.depends_on_job_id, j.job_type, j.status
                FROM job_dependencies d
                JOIN jobs j ON j.job_id = d.depends_on_job_id
                WHERE d.job_id = ?
                ORDER BY d.depends_on_job_id
                """, jobId);

        return ResponseEntity.ok(dependencies);
    }

    private void addEvent(
            Long jobId, String eventType, String details) {
        jdbc.update("""
                INSERT INTO job_events (job_id, event_type, details)
                VALUES (?, ?, CAST(? AS jsonb))
                """, jobId, eventType, details);
    }
}
