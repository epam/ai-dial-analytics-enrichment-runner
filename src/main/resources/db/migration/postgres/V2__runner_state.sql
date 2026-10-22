-- Runner-owned runtime state (no definitions, no ClickHouse). All source reads and enrichment
-- writes go through ADAS; these tables hold only the runner's own cursors, run bookkeeping,
-- dead-letter items and best-effort spend counters.

-- One opaque scan cursor per rule (ADAS owns the cursor internals; the runner stores it verbatim).
-- The live incremental scan advances this cursor atomically with chunk enqueue.
CREATE TABLE rule_cursor (
    rule_id       TEXT PRIMARY KEY,
    cursor        TEXT,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_scan_at  TIMESTAMP WITH TIME ZONE,
    lag_seconds   BIGINT
);

-- Backfill / schedule run bookkeeping. A backfill tracks its own cursor here, independent of the
-- live rule_cursor, so live and backfill scans of the same rule do not interfere.
CREATE TABLE run_history (
    id           TEXT PRIMARY KEY,
    rule_id      TEXT   NOT NULL,
    kind         TEXT   NOT NULL CHECK (kind IN ('live', 'backfill', 'schedule')),
    state        TEXT   NOT NULL CHECK (state IN ('running', 'paused', 'done', 'cancelled')),
    rows_done    BIGINT NOT NULL DEFAULT 0,
    rows_total   BIGINT,
    cursor       TEXT,
    started_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX run_history_rule_id_idx ON run_history (rule_id);

-- Dead-letter items: permanent per-row failures (un-mappable input, schema-invalid output, etc.)
-- and exhausted chunk executions the FailureHandler archives instead of dropping. TTL-swept (30d).
-- `payload` holds the serialized re-runnable work item for archived chunks (so they can be
-- requeued); it is null for per-row poison items, which are not re-runnable.
CREATE TABLE dlq_item (
    id                 BIGSERIAL PRIMARY KEY,
    rule_id            TEXT NOT NULL,
    grain_key          TEXT,
    evaluator_version  INT,
    stage              TEXT NOT NULL CHECK (stage IN ('input_map', 'dial_call', 'validate', 'output_map', 'upsert')),
    error              TEXT,
    raw_response       TEXT,
    payload            TEXT,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Browse (list by rule, newest first) filters on rule_id and sorts on created_at; the composite
-- serves both. The plain created_at index backs the retention sweep (WHERE created_at < cutoff).
CREATE INDEX dlq_item_rule_id_created_at_idx ON dlq_item (rule_id, created_at DESC);
CREATE INDEX dlq_item_created_at_idx ON dlq_item (created_at);

-- Per-row DLQ items are upserted on chunk retry (see DlqItemWriter): a chunk runs at-least-once, so
-- this partial unique key collapses a repeated per-row failure to one row instead of accumulating
-- duplicates. Partial on grain_key IS NOT NULL so chunk-level archives (null grain_key, one per
-- exhausted chunk) are exempt.
CREATE UNIQUE INDEX dlq_item_row_identity_uk
    ON dlq_item (rule_id, grain_key, evaluator_version)
    WHERE grain_key IS NOT NULL;

-- Best-effort spend accounting per rule/evaluator-version/day. No cost or pricing — usage only.
CREATE TABLE spend_counter (
    rule_id            TEXT   NOT NULL,
    evaluator_version  INT    NOT NULL,
    day                DATE   NOT NULL,
    calls              BIGINT NOT NULL DEFAULT 0,
    usage_amount       BIGINT NOT NULL DEFAULT 0,
    usage_unit         TEXT   NOT NULL CHECK (usage_unit IN ('tokens', 'calls', 'rows')),
    failures           BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (rule_id, evaluator_version, day)
);

-- The spend pause-gate reads by (rule_id, day); day is non-contiguous in the PK, so add the covering
-- composite for that lookup.
CREATE INDEX spend_counter_rule_id_day_idx ON spend_counter (rule_id, day);
