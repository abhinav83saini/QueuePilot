-- ====================================================================
-- QueuePilot Database Schema & Seed Data
-- PostgreSQL 14+ compatible
-- ====================================================================

-- 1. Queues
CREATE TABLE IF NOT EXISTS queues (
    queue_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL
);

-- 2. Jobs
CREATE TABLE IF NOT EXISTS jobs (
    job_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue_id BIGINT NOT NULL REFERENCES queues(queue_id) ON DELETE RESTRICT,
    job_type VARCHAR(50) NOT NULL,
    input_data JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'QUEUED' NOT NULL,
    priority INTEGER DEFAULT 0 NOT NULL,
    progress INTEGER DEFAULT 0 NOT NULL,
    max_retries INTEGER DEFAULT 3 NOT NULL,
    retry_delay_seconds INTEGER DEFAULT 5 NOT NULL,
    estimated_duration_ms BIGINT,
    scheduled_at TIMESTAMP WITH TIME ZONE,
    deadline_at TIMESTAMP WITH TIME ZONE,
    available_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    output_data JSONB,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT jobs_progress_check CHECK (progress >= 0 AND progress <= 100),
    CONSTRAINT jobs_max_retries_check CHECK (max_retries >= 0),
    CONSTRAINT jobs_retry_delay_seconds_check CHECK (retry_delay_seconds >= 0),
    CONSTRAINT jobs_estimated_duration_ms_check CHECK (estimated_duration_ms IS NULL OR estimated_duration_ms > 0),
    CONSTRAINT jobs_status_check CHECK (status IN (
        'QUEUED', 'SCHEDULED', 'BLOCKED', 'ASSIGNED', 'RUNNING', 'RETRYING', 'COMPLETED', 'FAILED', 'CANCELLED'
    ))
);

CREATE INDEX IF NOT EXISTS idx_jobs_status_available ON jobs(status, available_at);
CREATE INDEX IF NOT EXISTS idx_jobs_queue_status ON jobs(queue_id, status);

-- 3. Job Dependencies (DAG)
CREATE TABLE IF NOT EXISTS job_dependencies (
    job_id BIGINT NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
    depends_on_job_id BIGINT NOT NULL REFERENCES jobs(job_id),
    PRIMARY KEY (job_id, depends_on_job_id),
    CONSTRAINT job_dependencies_check CHECK (job_id <> depends_on_job_id)
);

-- 4. Job Events Audit Log
CREATE TABLE IF NOT EXISTS job_events (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    details JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_job_events_job_id ON job_events(job_id);

-- 5. Workers
CREATE TABLE IF NOT EXISTS workers (
    worker_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    health_status VARCHAR(20) DEFAULT 'HEALTHY' NOT NULL,
    capacity INTEGER DEFAULT 1 NOT NULL,
    last_heartbeat TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT workers_capacity_check CHECK (capacity > 0),
    CONSTRAINT workers_health_status_check CHECK (health_status IN ('HEALTHY', 'UNHEALTHY', 'OFFLINE'))
);

-- 6. Worker Capabilities
CREATE TABLE IF NOT EXISTS worker_capabilities (
    worker_id BIGINT NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    capability VARCHAR(100) NOT NULL,
    PRIMARY KEY (worker_id, capability)
);

-- 7. Execution Attempts
CREATE TABLE IF NOT EXISTS execution_attempts (
    attempt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
    worker_id BIGINT REFERENCES workers(worker_id) ON DELETE SET NULL,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(20) DEFAULT 'ASSIGNED' NOT NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    output_data JSONB,
    error_message TEXT,
    CONSTRAINT execution_attempts_attempt_number_check CHECK (attempt_number > 0),
    CONSTRAINT execution_attempts_status_check CHECK (status IN (
        'ASSIGNED', 'RUNNING', 'COMPLETED', 'FAILED', 'EXPIRED', 'CANCELLED'
    )),
    CONSTRAINT execution_attempts_job_id_attempt_number_key UNIQUE (job_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS idx_execution_attempts_running ON execution_attempts(status, lease_expires_at);

-- 8. Scheduler Settings
CREATE TABLE IF NOT EXISTS scheduler_settings (
    setting_id INTEGER DEFAULT 1 NOT NULL PRIMARY KEY,
    active_policy VARCHAR(20) DEFAULT 'FCFS' NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT scheduler_settings_setting_id_check CHECK (setting_id = 1),
    CONSTRAINT scheduler_settings_active_policy_check CHECK (active_policy IN ('FCFS', 'PRIORITY_AGING', 'SJF'))
);

-- ====================================================================
-- Initial Seed Data
-- ====================================================================

-- Default Queue (queue_id = 1)
INSERT INTO queues (name, description)
VALUES ('default', 'Default job processing queue')
ON CONFLICT (name) DO NOTHING;

-- Default Scheduler Policy (setting_id = 1, policy = FCFS)
INSERT INTO scheduler_settings (setting_id, active_policy)
VALUES (1, 'FCFS')
ON CONFLICT (setting_id) DO NOTHING;
