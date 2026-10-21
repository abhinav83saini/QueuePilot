
package com.queuepilot.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobMetricsController {

    private final JdbcTemplate jdbc;

    public JobMetricsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        Map<String, Long> byStatus = jdbc.query(
                """
                SELECT status, COUNT(*) AS job_count
                FROM jobs
                GROUP BY status
                """,
                rs -> {
                    Map<String, Long> result = new LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(
                                rs.getString("status"),
                                rs.getLong("job_count")
                        );
                    }
                    return result;
                }
        );

        long totalJobs = byStatus.values()
                .stream()
                .mapToLong(Long::longValue)
                .sum();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalJobs", totalJobs);
        response.put("byStatus", byStatus);

        return response;
    }
}
