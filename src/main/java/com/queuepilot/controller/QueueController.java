package com.queuepilot.controller;

import com.queuepilot.entity.Queue;
import com.queuepilot.repository.QueueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/queues")
public class QueueController {

    private final QueueRepository queueRepository;

    public QueueController(QueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @GetMapping
    public List<Queue> getQueues() {
        return queueRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Queue> getQueue(@PathVariable Long id) {
        return queueRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createQueue(@RequestBody CreateQueueRequest request) {
        if (request.getName() == null || request.getName().trim().isBlank()) {
            return ResponseEntity.badRequest().body("Queue name is required");
        }

        String name = request.getName().trim();

        if (queueRepository.existsByName(name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Queue with name '" + name + "' already exists");
        }

        Queue queue = new Queue();
        queue.setName(name);
        queue.setDescription(request.getDescription());

        Queue saved = queueRepository.save(queue);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    public static class CreateQueueRequest {
        private String name;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
