package com.queuepilot.repository;

import com.queuepilot.entity.Queue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface QueueRepository extends JpaRepository<Queue, Long> {

    Optional<Queue> findByName(String name);

    boolean existsByName(String name);
}
