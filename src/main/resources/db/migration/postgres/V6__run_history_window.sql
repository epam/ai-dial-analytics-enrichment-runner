-- The version window a backfill covers.
--
-- A run is inspected after the fact, often to answer "did we already re-derive that range?" — and a window that
-- exists only in the caller's shell history cannot answer it. Recording it makes a past run self-describing.
--
-- Both nullable: null means unbounded, which is what every run created before windows existed was. Additive,
-- backwards-compatible, no backfill needed.
ALTER TABLE run_history ADD COLUMN window_from TIMESTAMP WITH TIME ZONE;
ALTER TABLE run_history ADD COLUMN window_to TIMESTAMP WITH TIME ZONE;
