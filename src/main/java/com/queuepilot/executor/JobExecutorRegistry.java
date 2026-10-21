package com.queuepilot.executor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the {@link JobExecutor} responsible for a job type.
 *
 * <p>Every {@code JobExecutor} bean is registered automatically, so adding a
 * new task type means adding one class; no scheduler change is needed. Only
 * executors that exist as beans can run, so a job can never cause arbitrary
 * or client-supplied code to execute.</p>
 */
@Component
public class JobExecutorRegistry {

    private final Map<String, JobExecutor> executorsByType;

    public JobExecutorRegistry(List<JobExecutor> executors) {
        Map<String, JobExecutor> registered = new LinkedHashMap<>();

        for (JobExecutor executor : executors) {
            for (String type : new TreeSet<>(executor.supportedJobTypes())) {
                String key = normalize(type);

                if (key.isEmpty()) {
                    throw new IllegalStateException(
                            executor.getClass().getSimpleName()
                                    + " declares a blank job type");
                }

                JobExecutor previous = registered.putIfAbsent(key, executor);

                if (previous != null) {
                    throw new IllegalStateException(
                            "Job type " + key + " is claimed by both "
                                    + previous.getClass().getSimpleName()
                                    + " and "
                                    + executor.getClass().getSimpleName());
                }
            }
        }

        this.executorsByType = Collections.unmodifiableMap(registered);
    }

    /** Looks up the executor for a job type (case-insensitive). */
    public Optional<JobExecutor> find(String jobType) {
        return Optional.ofNullable(executorsByType.get(normalize(jobType)));
    }

    /** The registered job types in alphabetical order. */
    public Set<String> supportedJobTypes() {
        return new TreeSet<>(executorsByType.keySet());
    }

    /**
     * Runs the executor registered for {@code jobType}.
     *
     * @throws UnsupportedJobTypeException if no executor handles the type
     * @throws JobExecutionException if the executor rejects the input or
     *         fails; unexpected runtime errors are wrapped so only a safe
     *         message reaches the job's error field
     */
    public JobExecutionResult execute(String jobType, JsonNode input) {
        JobExecutor executor = find(jobType).orElseThrow(() ->
                new UnsupportedJobTypeException(
                        "Unsupported job type: " + jobType
                                + ". Supported types: "
                                + String.join(", ", supportedJobTypes())));

        try {
            return executor.execute(input);
        } catch (JobExecutionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new JobExecutionException(
                    "Job executor for type " + normalize(jobType)
                            + " failed unexpectedly", ex);
        }
    }

    static String normalize(String jobType) {
        return jobType == null
                ? ""
                : jobType.trim().toUpperCase(Locale.ROOT);
    }
}
