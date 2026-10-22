-- Canonical db-scheduler schema for PostgreSQL (priority-enabled variant).
--
-- Ships the job-queue table db-scheduler persists its scheduled/recurring tasks in. The runner
-- wires the Scheduler bean (JacksonSerializer + TransactionAwareDataSourceProxy) in a later
-- milestone; this migration only provisions the table so Flyway owns the full schema from boot.
-- The `priority` column + index back db-scheduler's priority feature, which the runner uses to
-- keep backfill work from starving live scans. Keep this DDL in lockstep with the db-scheduler
-- version pinned in build.gradle when the library lands.

CREATE TABLE scheduled_tasks (
    task_name             TEXT                     NOT NULL,
    task_instance         TEXT                     NOT NULL,
    task_data             BYTEA,
    execution_time        TIMESTAMP WITH TIME ZONE NOT NULL,
    picked                BOOLEAN                  NOT NULL,
    picked_by             TEXT,
    last_success          TIMESTAMP WITH TIME ZONE,
    last_failure          TIMESTAMP WITH TIME ZONE,
    consecutive_failures  INT,
    last_heartbeat        TIMESTAMP WITH TIME ZONE,
    version               BIGINT                   NOT NULL,
    priority              SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
