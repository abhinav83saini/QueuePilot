package com.queuepilot.controller;

import com.queuepilot.entity.Job;
import com.queuepilot.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobOperationsControllerTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private JobRepository jobRepository;

    private JobOperationsController controller;

    @BeforeEach
    void setUp() {
        controller = new JobOperationsController(jdbc, jobRepository);
    }

    @Test
    void rejectsRetryWhenJobIsNotFailed() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus("COMPLETED");

        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        ResponseEntity<?> response = controller.retryJob(1L);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Only failed jobs"));
    }

    @Test
    void rejectsRetryWhenPrerequisiteFailed() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus("FAILED");

        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(jdbc.queryForObject(contains("prerequisite.status IN ('FAILED', 'CANCELLED')"), eq(Boolean.class), eq(1L)))
                .thenReturn(true);

        ResponseEntity<?> response = controller.retryJob(1L);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("prerequisite has failed"));
    }

    @Test
    void retriesFailedJobIntoQueuedWhenPrerequisitesAreCompleted() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus("FAILED");

        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(jdbc.queryForObject(contains("prerequisite.status IN ('FAILED', 'CANCELLED')"), eq(Boolean.class), eq(1L)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("prerequisite.status <> 'COMPLETED'"), eq(Boolean.class), eq(1L)))
                .thenReturn(false);
        when(jdbc.update(contains("UPDATE jobs"), eq("QUEUED"), eq(1L))).thenReturn(1);

        ResponseEntity<?> response = controller.retryJob(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("QUEUED", body.get("status"));
    }

    @Test
    void retriesFailedJobIntoBlockedWhenPrerequisitesAreStillIncomplete() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus("FAILED");

        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(jdbc.queryForObject(contains("prerequisite.status IN ('FAILED', 'CANCELLED')"), eq(Boolean.class), eq(1L)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("prerequisite.status <> 'COMPLETED'"), eq(Boolean.class), eq(1L)))
                .thenReturn(true);
        when(jdbc.update(contains("UPDATE jobs"), eq("BLOCKED"), eq(1L))).thenReturn(1);

        ResponseEntity<?> response = controller.retryJob(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("BLOCKED", body.get("status"));
    }

    @Test
    void rejectsSelfDependency() {
        ResponseEntity<?> response = controller.addDependency(5L, 5L);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("cannot depend on itself"));
    }

    @Test
    void rejectsAddingDependencyToRunningJob() {
        Job target = new Job();
        target.setStatus("RUNNING");
        Job prereq = new Job();
        prereq.setStatus("COMPLETED");

        when(jobRepository.findById(10L)).thenReturn(Optional.of(target));
        when(jobRepository.findById(20L)).thenReturn(Optional.of(prereq));

        ResponseEntity<?> response = controller.addDependency(10L, 20L);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("unstarted jobs"));
    }

    @Test
    void rejectsDependencyThatWouldCreateACycle() {
        Job target = new Job();
        target.setStatus("QUEUED");
        Job prereq = new Job();
        prereq.setStatus("QUEUED");

        when(jobRepository.findById(10L)).thenReturn(Optional.of(target));
        when(jobRepository.findById(20L)).thenReturn(Optional.of(prereq));
        when(jdbc.queryForObject(contains("WITH RECURSIVE dependency_walk"), eq(Boolean.class), eq(20L), eq(10L)))
                .thenReturn(true);

        ResponseEntity<?> response = controller.addDependency(10L, 20L);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("create a cycle"));
    }

    @Test
    void addsDependencyAndTransitionsToBlockedIfPrereqIncomplete() {
        Job target = new Job();
        target.setStatus("QUEUED");
        Job prereq = new Job();
        prereq.setStatus("RUNNING");

        when(jobRepository.findById(10L)).thenReturn(Optional.of(target));
        when(jobRepository.findById(20L)).thenReturn(Optional.of(prereq));
        when(jdbc.queryForObject(contains("WITH RECURSIVE dependency_walk"), eq(Boolean.class), eq(20L), eq(10L)))
                .thenReturn(false);
        when(jdbc.update(contains("INSERT INTO job_dependencies"), eq(10L), eq(20L), eq(10L), eq(20L)))
                .thenReturn(1);

        ResponseEntity<?> response = controller.addDependency(10L, 20L);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        verify(jdbc).update(contains("SET status = 'BLOCKED'"), eq(10L));
    }
}
