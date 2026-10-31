package com.queuepilot.controller;

import com.queuepilot.entity.Queue;
import com.queuepilot.repository.QueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueControllerTest {

    @Mock
    private QueueRepository queueRepository;

    private QueueController controller;

    @BeforeEach
    void setUp() {
        controller = new QueueController(queueRepository);
    }

    @Test
    void listsAllQueues() {
        Queue q1 = new Queue("default", "Default queue");
        q1.setId(1L);
        Queue q2 = new Queue("high-priority", "High priority tasks");
        q2.setId(2L);

        when(queueRepository.findAll()).thenReturn(List.of(q1, q2));

        List<Queue> queues = controller.getQueues();
        assertEquals(2, queues.size());
        assertEquals("default", queues.get(0).getName());
        assertEquals("high-priority", queues.get(1).getName());
    }

    @Test
    void createsQueueSuccessfully() {
        QueueController.CreateQueueRequest request = new QueueController.CreateQueueRequest();
        request.setName("critical");
        request.setDescription("Urgent jobs");

        when(queueRepository.existsByName("critical")).thenReturn(false);
        when(queueRepository.save(any(Queue.class))).thenAnswer(invocation -> {
            Queue q = invocation.getArgument(0);
            q.setId(3L);
            return q;
        });

        ResponseEntity<?> response = controller.createQueue(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        Queue created = (Queue) response.getBody();
        assertNotNull(created);
        assertEquals(3L, created.getId());
        assertEquals("critical", created.getName());
    }

    @Test
    void rejectsQueueCreationWhenNameAlreadyExists() {
        QueueController.CreateQueueRequest request = new QueueController.CreateQueueRequest();
        request.setName("default");

        when(queueRepository.existsByName("default")).thenReturn(true);

        ResponseEntity<?> response = controller.createQueue(request);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("already exists"));
    }

    @Test
    void rejectsQueueCreationWhenNameIsBlank() {
        QueueController.CreateQueueRequest request = new QueueController.CreateQueueRequest();
        request.setName("   ");

        ResponseEntity<?> response = controller.createQueue(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getsQueueById() {
        Queue q = new Queue("default", "Default queue");
        q.setId(1L);

        when(queueRepository.findById(1L)).thenReturn(Optional.of(q));
        when(queueRepository.findById(2L)).thenReturn(Optional.empty());

        ResponseEntity<Queue> found = controller.getQueue(1L);
        assertEquals(HttpStatus.OK, found.getStatusCode());
        assertEquals("default", found.getBody().getName());

        ResponseEntity<Queue> notFound = controller.getQueue(2L);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    }
}
