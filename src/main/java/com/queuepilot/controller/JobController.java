
package com.queuepilot.controller;

import java.util.List;
import java.time.OffsetDateTime;
import com.queuepilot.entity.Job;
import com.queuepilot.repository.JobRepository;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepository jobRepository;

    public JobController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @PostMapping
    public ResponseEntity<?> createJob(@RequestBody CreateJobRequest request) {
        if (request.getQueueId() == null
                || request.getJobType() == null
                || request.getJobType().isBlank()
                || request.getInputData() == null
                || request.getInputData().isNull()) {
            return ResponseEntity.badRequest()
                    .body("queueId, jobType and inputData are required");
        }
        if (request.getEstimatedDurationMs() != null
            && request.getEstimatedDurationMs() <= 0) {
            return ResponseEntity.badRequest()
                    .body("estimatedDurationMs must be positive");
            }

        if (request.getScheduledAt() != null
                && request.getDeadlineAt() != null
                && request.getDeadlineAt().isBefore(request.getScheduledAt())) {
            return ResponseEntity.badRequest()
                    .body("deadlineAt must not be before scheduledAt");
        }
        Job job = new Job();
        job.setQueueId(request.getQueueId());
        job.setJobType(request.getJobType().trim());
        job.setInputData(request.getInputData());

        if (request.getPriority() != null) {
            job.setPriority(request.getPriority());
        }

        if (request.getMaxRetries() != null) {
            job.setMaxRetries(request.getMaxRetries());
        }
        job.setEstimatedDurationMs(request.getEstimatedDurationMs());
        job.setScheduledAt(request.getScheduledAt());
        job.setDeadlineAt(request.getDeadlineAt());

        Job savedJob = jobRepository.save(job);

        return ResponseEntity.status(HttpStatus.CREATED).body(savedJob);
    }
    
    @GetMapping
    public List<Job> getAllJobs(
            @RequestParam(required = false) String status) {

        if (status != null && !status.isBlank()) {
            return jobRepository.findByStatus(status.trim().toUpperCase());
        }

        return jobRepository.findAll();
    }

    
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelJob(@PathVariable Long id) {
        var optionalJob = jobRepository.findById(id);

        if (optionalJob.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Job job = optionalJob.get();

        if (!"QUEUED".equalsIgnoreCase(job.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Only queued jobs can be cancelled.");
        }

        job.setStatus("CANCELLED");
        Job savedJob = jobRepository.save(job);

        return ResponseEntity.ok(savedJob);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Job> getJob(@PathVariable Long id) {
        return jobRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public static class CreateJobRequest {
        private Long estimatedDurationMs;
        private OffsetDateTime scheduledAt;
        private OffsetDateTime deadlineAt;
        private Long queueId;
        private String jobType;
        private JsonNode inputData;
        private Integer priority;
        private Integer maxRetries;

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
    }
}
