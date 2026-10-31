package com.queuepilot.controller;

import com.queuepilot.entity.Job;
import com.queuepilot.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        Boolean hasIncompletePrereq = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM job_dependencies d
                    JOIN jobs prerequisite
                      ON prerequisite.job_id = d.depends_on_job_id
                    WHERE d.job_id = ?
                      AND prerequisite.status <> 'COMPLETED'
                )
                """, Boolean.class, jobId);

        String nextStatus;
        if (Boolean.TRUE.equals(hasIncompletePrereq)) {
            nextStatus = "BLOCKED";
        } else if (job.getScheduledAt() != null && job.getScheduledAt().isAfter(OffsetDateTime.now())) {
            nextStatus = "SCHEDULED";
        } else {
            nextStatus = "QUEUED";
        }

        int updated = jdbc.update("""
                UPDATE jobs
                SET status = ?,
                    progress = 0,
                    available_at = NOW(),
                    started_at = NULL,
                    completed_at = NULL,
                    error_message = NULL
                WHERE job_id = ? AND status = 'FAILED'
                """, nextStatus, jobId);

        if (updated == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Job status changed; refresh and try again.");
        }

        addEvent(jobId, "JOB_MANUAL_RETRY",
                "{\"source\":\"API\",\"targetStatus\":\"" + nextStatus + "\"}");

        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "status", nextStatus,
                "message", "Job queued for manual retry with status " + nextStatus
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
        var prereqOptional = jobRepository.findById(dependsOnJobId);

        if (jobOptional.isEmpty() || prereqOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String jobStatus = jobOptional.get().getStatus();
        Set<String> validStates = Set.of("QUEUED", "SCHEDULED", "BLOCKED");

        if (!validStates.contains(jobStatus.toUpperCase())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Dependencies can only be added to unstarted jobs (QUEUED, SCHEDULED, BLOCKED).");
        }

        String prereqStatus = prereqOptional.get().getStatus();
        if ("FAILED".equalsIgnoreCase(prereqStatus) || "CANCELLED".equalsIgnoreCase(prereqStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Cannot depend on job " + dependsOnJobId + " because its status is " + prereqStatus);
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

            // If the prerequisite is not completed, transition job to BLOCKED
            if (!"COMPLETED".equalsIgnoreCase(prereqStatus) && !"BLOCKED".equalsIgnoreCase(jobStatus)) {
                jdbc.update("""
                        UPDATE jobs
                        SET status = 'BLOCKED'
                        WHERE job_id = ? AND status IN ('QUEUED', 'SCHEDULED')
                        """, jobId);

                addEvent(jobId, "JOB_BLOCKED",
                        "{\"reason\":\"UNMET_PREREQUISITE\",\"prerequisiteJobId\":" + dependsOnJobId + "}");
            }
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
