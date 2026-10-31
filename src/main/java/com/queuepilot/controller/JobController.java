package com.queuepilot.controller;

import com.queuepilot.entity.Job;
import com.queuepilot.executor.InvalidJobInputException;
import com.queuepilot.executor.JobExecutorRegistry;
import com.queuepilot.executor.UnsupportedJobTypeException;
import com.queuepilot.repository.JobRepository;
import com.queuepilot.repository.QueueRepository;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepository jobRepository;
    private final JobExecutorRegistry executorRegistry;
    private final QueueRepository queueRepository;
    private final JdbcTemplate jdbc;

    public JobController(
            JobRepository jobRepository,
            JobExecutorRegistry executorRegistry,
            QueueRepository queueRepository,
            JdbcTemplate jdbc) {
        this.jobRepository = jobRepository;
        this.executorRegistry = executorRegistry;
        this.queueRepository = queueRepository;
        this.jdbc = jdbc;
    }

    @PostMapping
    public ResponseEntity<?> createJob(@RequestBody CreateJobRequest request) {
        if (request.getQueueId() == null) {
            return ResponseEntity.badRequest().body("queueId is required");
        }

        if (request.getJobType() == null || request.getJobType().isBlank()) {
            return ResponseEntity.badRequest().body("jobType is required");
        }

        if (request.getInputData() == null || request.getInputData().isNull()) {
            return ResponseEntity.badRequest().body("inputData is required");
        }

        if (!queueRepository.existsById(request.getQueueId())) {
            return ResponseEntity.badRequest()
                    .body("Queue with ID " + request.getQueueId() + " does not exist");
        }

        String jobType = request.getJobType().trim();

        try {
            executorRegistry.validate(jobType, request.getInputData());
        } catch (UnsupportedJobTypeException | InvalidJobInputException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }

        if (request.getEstimatedDurationMs() != null && request.getEstimatedDurationMs() <= 0) {
            return ResponseEntity.badRequest().body("estimatedDurationMs must be positive");
        }

        if (request.getPriority() != null && request.getPriority() < 0) {
            return ResponseEntity.badRequest().body("priority must be non-negative");
        }

        if (request.getMaxRetries() != null && request.getMaxRetries() < 0) {
            return ResponseEntity.badRequest().body("maxRetries must be non-negative");
        }

        if (request.getRetryDelaySeconds() != null && request.getRetryDelaySeconds() < 0) {
            return ResponseEntity.badRequest().body("retryDelaySeconds must be non-negative");
        }

        OffsetDateTime scheduledAt = request.getScheduledAt();
        OffsetDateTime deadlineAt = request.getDeadlineAt();

        if (scheduledAt != null && deadlineAt != null && deadlineAt.isBefore(scheduledAt)) {
            return ResponseEntity.badRequest().body("deadlineAt must not be before scheduledAt");
        }

        List<Long> prerequisiteIds = request.getDependsOnJobIds();
        boolean hasIncompletePrerequisites = false;

        if (prerequisiteIds != null && !prerequisiteIds.isEmpty()) {
            Set<Long> uniqueIds = new LinkedHashSet<>(prerequisiteIds);

            for (Long prereqId : uniqueIds) {
                var prereqOpt = jobRepository.findById(prereqId);
                if (prereqOpt.isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body("Prerequisite job with ID " + prereqId + " does not exist");
                }

                Job prereq = prereqOpt.get();
                String prereqStatus = prereq.getStatus();

                if ("FAILED".equalsIgnoreCase(prereqStatus) || "CANCELLED".equalsIgnoreCase(prereqStatus)) {
                    return ResponseEntity.badRequest()
                            .body("Cannot depend on job " + prereqId + " because its status is " + prereqStatus);
                }

                if (!"COMPLETED".equalsIgnoreCase(prereqStatus)) {
                    hasIncompletePrerequisites = true;
                }
            }
        }

        // Determine initial state (SRS Use Case 1 Step 4)
        String initialStatus;
        if (hasIncompletePrerequisites) {
            initialStatus = "BLOCKED";
        } else if (scheduledAt != null && scheduledAt.isAfter(OffsetDateTime.now())) {
            initialStatus = "SCHEDULED";
        } else {
            initialStatus = "QUEUED";
        }

        Job job = new Job();
        job.setQueueId(request.getQueueId());
        job.setJobType(jobType);
        job.setInputData(request.getInputData());
        job.setStatus(initialStatus);

        if (request.getPriority() != null) {
            job.setPriority(request.getPriority());
        }

        if (request.getMaxRetries() != null) {
            job.setMaxRetries(request.getMaxRetries());
        }

        if (request.getRetryDelaySeconds() != null) {
            job.setRetryDelaySeconds(request.getRetryDelaySeconds());
        }

        job.setEstimatedDurationMs(request.getEstimatedDurationMs());
        job.setScheduledAt(scheduledAt);
        job.setDeadlineAt(deadlineAt);

        Job savedJob = jobRepository.save(job);

        // Record dependencies if provided
        if (prerequisiteIds != null && !prerequisiteIds.isEmpty()) {
            Set<Long> uniqueIds = new LinkedHashSet<>(prerequisiteIds);
            for (Long prereqId : uniqueIds) {
                jdbc.update("""
                        INSERT INTO job_dependencies (job_id, depends_on_job_id)
                        VALUES (?, ?)
                        ON CONFLICT DO NOTHING
                        """, savedJob.getId(), prereqId);
            }
        }

        // Record initial event in job_events (SRS Use Case 1 Step 3)
        jdbc.update("""
                INSERT INTO job_events (job_id, event_type, details)
                VALUES (?, 'JOB_CREATED', CAST(? AS jsonb))
                """,
                savedJob.getId(),
                "{\"initialStatus\":\"" + initialStatus
                        + "\",\"queueId\":" + savedJob.getQueueId()
                        + ",\"jobType\":\"" + savedJob.getJobType() + "\"}"
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(savedJob);
    }

    @GetMapping
    public List<Job> getAllJobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long queueId) {

        boolean hasStatus = status != null && !status.isBlank();
        boolean hasQueue = queueId != null;

        if (hasStatus && hasQueue) {
            return jobRepository.findByQueueIdAndStatusOrderByCreatedAtDesc(
                    queueId, status.trim().toUpperCase(Locale.ROOT));
        } else if (hasStatus) {
            return jobRepository.findByStatusOrderByCreatedAtDesc(
                    status.trim().toUpperCase(Locale.ROOT));
        } else if (hasQueue) {
            return jobRepository.findByQueueIdOrderByCreatedAtDesc(queueId);
        }

        return jobRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelJob(@PathVariable Long id) {
        var optionalJob = jobRepository.findById(id);

        if (optionalJob.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Job job = optionalJob.get();
        String currentStatus = job.getStatus();

        // Cancellation is permitted before execution starts: QUEUED, SCHEDULED, BLOCKED
        Set<String> cancellableStates = Set.of("QUEUED", "SCHEDULED", "BLOCKED");

        if (!cancellableStates.contains(currentStatus.toUpperCase(Locale.ROOT))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Job cannot be cancelled in state '" + currentStatus
                            + "'. Only unstarted jobs (QUEUED, SCHEDULED, BLOCKED) can be cancelled.");
        }

        job.setStatus("CANCELLED");
        job.setCompletedAt(OffsetDateTime.now());
        Job savedJob = jobRepository.save(job);

        // Record cancellation event in job_events (SRS Use Case 1 Alternative Flow)
        jdbc.update("""
                INSERT INTO job_events (job_id, event_type, details)
                VALUES (?, 'JOB_CANCELLED', '{\"source\":\"API\"}'::jsonb)
                """, savedJob.getId());

        // Fail any jobs waiting on this cancelled job
        List<Long> cascadedJobIds = jdbc.queryForList("""
                UPDATE jobs j
                SET status = 'FAILED',
                    error_message = 'A prerequisite job failed or was cancelled',
                    completed_at = NOW()
                WHERE j.status IN ('QUEUED', 'SCHEDULED', 'BLOCKED')
                  AND EXISTS (
                      SELECT 1
                      FROM job_dependencies d
                      WHERE d.job_id = j.job_id
                        AND d.depends_on_job_id = ?
                  )
                RETURNING j.job_id
                """, Long.class, savedJob.getId());

        for (Long cascadedId : cascadedJobIds) {
            jdbc.update("""
                    INSERT INTO job_events (job_id, event_type, details)
                    VALUES (?, 'JOB_FAILED', CAST(? AS jsonb))
                    """, cascadedId, "{\"reason\":\"PREREQUISITE_CANCELLED\",\"prerequisiteJobId\":" + savedJob.getId() + "}");
        }

        return ResponseEntity.ok(savedJob);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Job> getJob(@PathVariable Long id) {
        return jobRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public static class CreateJobRequest {
        private Long queueId;
        private String jobType;
        private JsonNode inputData;
        private Integer priority;
        private Integer maxRetries;
        private Integer retryDelaySeconds;
        private Long estimatedDurationMs;
        private OffsetDateTime scheduledAt;
        private OffsetDateTime deadlineAt;
        private List<Long> dependsOnJobIds;

        public Long getQueueId() {
            return queueId;
        }

        public void setQueueId(Long queueId) {
            this.queueId = queueId;
        }

        public String getJobType() {
            return jobType;
        }

        public void setJobType(String jobType) {
            this.jobType = jobType;
        }

        public JsonNode getInputData() {
            return inputData;
        }

        public void setInputData(JsonNode inputData) {
            this.inputData = inputData;
        }

        public Integer getPriority() {
            return priority;
        }

        public void setPriority(Integer priority) {
            this.priority = priority;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Integer getRetryDelaySeconds() {
            return retryDelaySeconds;
        }

        public void setRetryDelaySeconds(Integer retryDelaySeconds) {
            this.retryDelaySeconds = retryDelaySeconds;
        }

        public Long getEstimatedDurationMs() {
            return estimatedDurationMs;
        }

        public void setEstimatedDurationMs(Long estimatedDurationMs) {
            this.estimatedDurationMs = estimatedDurationMs;
        }

        public OffsetDateTime getScheduledAt() {
            return scheduledAt;
        }

        public void setScheduledAt(OffsetDateTime scheduledAt) {
            this.scheduledAt = scheduledAt;
        }

        public OffsetDateTime getDeadlineAt() {
            return deadlineAt;
        }

        public void setDeadlineAt(OffsetDateTime deadlineAt) {
            this.deadlineAt = deadlineAt;
        }

        public List<Long> getDependsOnJobIds() {
            return dependsOnJobIds;
        }

        public void setDependsOnJobIds(List<Long> dependsOnJobIds) {
            this.dependsOnJobIds = dependsOnJobIds;
        }
    }
}
