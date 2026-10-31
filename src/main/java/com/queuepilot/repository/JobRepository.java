package com.queuepilot.repository;

import com.queuepilot.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByStatus(String status);

    List<Job> findByQueueId(Long queueId);

    List<Job> findByQueueIdAndStatus(Long queueId, String status);

    List<Job> findAllByOrderByCreatedAtDesc();

    List<Job> findByStatusOrderByCreatedAtDesc(String status);

    List<Job> findByQueueIdOrderByCreatedAtDesc(Long queueId);

    List<Job> findByQueueIdAndStatusOrderByCreatedAtDesc(Long queueId, String status);

    long countByStatus(String status);
}
