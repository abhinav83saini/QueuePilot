package com.queuepilot.controller;

import com.queuepilot.entity.Job;
import com.queuepilot.executor.*;
import com.queuepilot.repository.JobRepository;
import com.queuepilot.repository.QueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private QueueRepository queueRepository;

    @Mock
    private JdbcTemplate jdbc;

    private JobExecutorRegistry executorRegistry;
    private JobController controller;

    @BeforeEach
    void setUp() {
        executorRegistry = new JobExecutorRegistry(List.of(
                new SortingJobExecutor(),
                new TextAnalysisJobExecutor(),
                new MathJobExecutor(),
                new DemoJobExecutor(),
                new SimulatedEmailJobExecutor()
        ));

        controller = new JobController(jobRepository, executorRegistry, queueRepository, jdbc);
    }

    @Test
    void rejectsSubmissionWhenRequiredFieldsAreMissing() {
        JobController.CreateJobRequest request = new JobController.CreateJobRequest();

        ResponseEntity<?> response = controller.createJob(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("queueId is required", response.getBody());

        request.setQueueId(1L);
        response = controller.createJob(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("jobType is required", response.getBody());

        request.setJobType("MATH");
        response = controller.createJob(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("inputData is required", response.getBody());
    }

    @Test
    void rejectsSubmissionWhenQueueDoesNotExist() {
        JobController.CreateJobRequest request = new JobController.CreateJobRequest();
        request.setQueueId(999L);
        request.setJobType("MATH");
        request.setInputData(JsonSupport.parse("{\"operation\":\"ADD\",\"a\":1,\"b\":2}"));

        when(queueRepository.existsById(999L)).thenReturn(false);

        ResponseEntity<?> response = controller.createJob(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Queue with ID 999 does not exist", response.getBody());
    }

    @Test
    void rejectsSubmissionWithUnsupportedJobType() {
        JobController.CreateJobRequest request = new JobController.CreateJobRequest();
        request.setQueueId(1L);
        request.setJobType("UNKNOWN_TASK");
        request.setInputData(JsonSupport.parse("{\"foo\":\"bar\"}"));

        when(queueRepository.existsById(1L)).thenReturn(true);

        ResponseEntity<?> response = controller.createJob(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Unsupported job type"));
    }

    @Test
    void rejectsSubmissionWithMalformedInputData() {
        JobController.CreateJobRequest request = new JobController.CreateJobRequest();
        request.setQueueId(1L);
        request.setJobType("MATH");
        // Missing "operation", "a", and "b"
        request.setInputData(JsonSupport.parse("{\"values\":[1,2]}"));

        when(queueRepository.existsById(1L)).thenReturn(true);

        ResponseEntity<?> response = controller.createJob(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("operation"));
    }

    @Test
    void createsQueuedJobWhenReadyImmediately() {
        JobController.CreateJobRequest request = new JobController.CreateJobRequest();
        request.setQueueId(1L);
        request.setJobType("MATH");
        request.setInputData(JsonSupport.parse("{\"operation\":\"ADD\",\"a\":10,\"b\":20}"));
        request.setPriority(5);
        request.setMaxRetries(3);

        when(queueRepository.existsById(1L)).thenReturn(true);
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(42L);
            return job;
        });

        ResponseEntity<?> response = controller.createJob(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        Job saved = (Job) response.getBody();
        assertNotNull(saved);
        assertEquals(42L, saved.getId());
        assertEquals("QUEUED", saved.getStatus());
        assertEquals(5, saved.getPriority());

        verify(jdbc).update(contains("INSERT INTO job_events"), eq(42L), contains("QUEUED"));
    }

    @Test
    void createsScheduledJobWhenStartTimeIsInTheFuture() {
        JobController.CreateJobRequest request = new JobController.CreateJobRequest();
        request.setQueueId(1L);
        request.setJobType("SORT");
        request.setInputData(JsonSupport.parse("{\"values\":[3,1,2]}"));
        request.setScheduledAt(OffsetDateTime.now().plusHours(2));

        when(queueRepository.existsById(1L)).thenReturn(true);
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createJob(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        Job saved = (Job) response.getBody();
        assertNotNull(saved);
        assertEquals("SCHEDULED", saved.getStatus());
    }

    @Test
    void createsBlockedJobWhenPrerequisitesAreNotCompleted() {
        JobController.CreateJobRequest request = new JobController.CreateJobRequest();
        request.setQueueId(1L);
        request.setJobType("TEXT_ANALYSIS");
        request.setInputData(JsonSupport.parse("{\"text\":\"Sample text\"}"));
        request.setDependsOnJobIds(List.of(100L));

        Job prereq = new Job();
        prereq.setStatus("RUNNING");

        when(queueRepository.existsById(1L)).thenReturn(true);
        when(jobRepository.findById(100L)).thenReturn(Optional.of(prereq));
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createJob(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        Job saved = (Job) response.getBody();
        assertNotNull(saved);
        assertEquals("BLOCKED", saved.getStatus());
    }

    @Test
    void allowsCancellingUnstartedJobs() {
        for (String status : List.of("QUEUED", "SCHEDULED", "BLOCKED")) {
            Job job = new Job();
            job.setId(10L);
            job.setStatus(status);

            when(jobRepository.findById(10L)).thenReturn(Optional.of(job));
            when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResponseEntity<?> response = controller.cancelJob(10L);
            assertEquals(HttpStatus.OK, response.getStatusCode());

            Job cancelled = (Job) response.getBody();
            assertEquals("CANCELLED", cancelled.getStatus());
            assertNotNull(cancelled.getCompletedAt());
        }
    }

    @Test
    void rejectsCancellingRunningOrCompletedJobs() {
        for (String status : List.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED")) {
            Job job = new Job();
            job.setId(20L);
            job.setStatus(status);

            when(jobRepository.findById(20L)).thenReturn(Optional.of(job));

            ResponseEntity<?> response = controller.cancelJob(20L);
            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        }
    }

    @Test
    void getJobReturnsFullJobDetails() {
        Job job = new Job();
        job.setId(5L);
        job.setQueueId(1L);
        job.setJobType("SORT");
        job.setStatus("COMPLETED");
        job.setProgress(100);
        job.setOutputData(JsonSupport.parse("{\"sortedValues\":[1,2,3],\"count\":3}"));
        job.setStartedAt(OffsetDateTime.now().minusMinutes(1));
        job.setCompletedAt(OffsetDateTime.now());

        when(jobRepository.findById(5L)).thenReturn(Optional.of(job));

        ResponseEntity<Job> response = controller.getJob(5L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("COMPLETED", response.getBody().getStatus());
        assertNotNull(response.getBody().getOutputData());
        assertNotNull(response.getBody().getStartedAt());
        assertNotNull(response.getBody().getCompletedAt());
    }
}
