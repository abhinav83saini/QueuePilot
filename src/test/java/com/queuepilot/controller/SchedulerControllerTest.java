package com.queuepilot.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerControllerTest {

    @Mock
    private JdbcTemplate jdbc;

    private SchedulerController controller;

    @BeforeEach
    void setUp() {
        controller = new SchedulerController(jdbc);
    }

    @Test
    void getsActivePolicy() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of("PRIORITY_AGING"));

        Map<String, String> result = controller.getPolicy();
        assertEquals("PRIORITY_AGING", result.get("activePolicy"));
    }

    @Test
    void setsValidPolicy() {
        when(jdbc.update(contains("UPDATE scheduler_settings"), eq("PRIORITY_AGING"))).thenReturn(1);

        ResponseEntity<?> response = controller.setPolicy("PRIORITY_AGING");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("PRIORITY_AGING", body.get("activePolicy"));
    }

    @Test
    void normalizesPriorityToPriorityAging() {
        when(jdbc.update(contains("UPDATE scheduler_settings"), eq("PRIORITY_AGING"))).thenReturn(1);

        ResponseEntity<?> response = controller.setPolicy("PRIORITY");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("PRIORITY_AGING", body.get("activePolicy"));
    }

    @Test
    void rejectsInvalidPolicy() {
        ResponseEntity<?> response = controller.setPolicy("RANDOM");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Policy must be"));
    }

    @Test
    void listsWorkersWithCapabilitiesAndCapacity() {
        Map<String, Object> worker = Map.of(
                "worker_id", 1L,
                "name", "worker-1",
                "health_status", "HEALTHY",
                "capacity", 1,
                "capabilities", "MATH, SORT, TEXT_ANALYSIS",
                "active_jobs", 0
        );

        when(jdbc.queryForList(contains("FROM workers w"))).thenReturn(List.of(worker));

        List<Map<String, Object>> result = controller.getWorkers();
        assertEquals(1, result.size());
        assertEquals("MATH, SORT, TEXT_ANALYSIS", result.get(0).get("capabilities"));
        assertEquals(1, result.get(0).get("capacity"));
    }

    @Test
    void updatesWorkerHealthStatus() {
        when(jdbc.update(contains("SET health_status = ?"), eq("UNHEALTHY"), eq(1L)))
                .thenReturn(1);

        ResponseEntity<?> response = controller.setWorkerHealth(1L, "UNHEALTHY");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("UNHEALTHY", body.get("healthStatus"));
    }

    @Test
    void rejectsInvalidWorkerHealthStatus() {
        ResponseEntity<?> response = controller.setWorkerHealth(1L, "INVALID_STATE");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updatesWorkerCapacity() {
        when(jdbc.update(contains("SET capacity = ?"), eq(3), eq(1L)))
                .thenReturn(1);

        ResponseEntity<?> response = controller.setWorkerCapacity(1L, 3);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(3, body.get("capacity"));
    }

    @Test
    void rejectsNonPositiveWorkerCapacity() {
        ResponseEntity<?> response = controller.setWorkerCapacity(1L, 0);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());

        ResponseEntity<?> responseNegative = controller.setWorkerCapacity(1L, -2);
        assertEquals(HttpStatus.BAD_REQUEST, responseNegative.getStatusCode());
    }

    @Test
    void addsWorkerCapability() {
        when(jdbc.queryForObject(contains("SELECT EXISTS"), eq(Boolean.class), eq(2L)))
                .thenReturn(true);
        when(jdbc.update(contains("INSERT INTO worker_capabilities"), eq(2L), eq("IMAGE_RECOGNITION")))
                .thenReturn(1);

        ResponseEntity<?> response = controller.addWorkerCapability(2L, "IMAGE_RECOGNITION");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("IMAGE_RECOGNITION", body.get("capability"));
    }
}
