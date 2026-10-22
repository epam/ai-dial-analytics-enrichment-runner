-- Re-key the two runner-state tables that carried an evaluator version onto the pipeline's `generation`.
--
-- ADAS has folded its evaluator registry into the pipeline declaration, so there is no evaluator version
-- left to key on. The replacement is the pipeline's own `generation`, which this runner already caches and
-- diffs on. Two columns held the retired one, both as part of a uniqueness key:
--
--   * dlq_item.evaluator_version      — third component of the partial unique index dlq_item_row_identity_uk
--   * spend_counter.evaluator_version — middle component of spend_counter's primary key
--
-- RENAMED RATHER THAN REUSED UNDER THE OLD NAME. Leaving the name would reproduce, inside this service's own
-- database, the exact defect ADAS's fold refuses to allow on its wire: a column named for an evaluator
-- version holding generation numbers, with pre-cut-over rows carrying evaluator versions and post-cut-over
-- rows carrying generations in the same column, drawn from the same small-integer space and with nothing to
-- say which of the two a given value belongs to. ADAS declined that for one provenance column and paid for
-- a new one; declining it here costs this migration.
--
-- WIDENED INDEPENDENTLY OF THE RENAME. A `generation` is a BIGINT counter in ADAS and the runner's
-- DeterministicId now takes it as a `long`. An INT column would truncate silently at 2^31 — a bound no live
-- pipeline will reach, and a latent defect with no upside.
--
-- NO ROW IS REWRITTEN, DELIBERATELY. A pre-cut-over value records which evaluator version produced that
-- item; no generation can be invented for it after the fact, and inventing one would be a fabrication in a
-- column whose whole purpose is provenance. The consequences are stated rather than left to be discovered:
--
--   * Pre- and post-cut-over rows for one grain are no longer comparable, because the column's NAME now
--     means something else while its VALUES are drawn from the same small-integer space. Both spaces start
--     at 1 and neither is reserved, so the two CAN collide: a grain that already has a pre-cut-over dlq_item
--     row and fails again under a generation equal to that row's old evaluator version is UPSERTED onto it
--     by dlq_item_row_identity_uk, and a spend_counter day at a colliding value has its counters merged onto
--     the pre-cut-over row. Where the values differ — the common case, since a long-lived pipeline's
--     generation has outrun its evaluator versions — a second row appears beside the first instead, and the
--     30-day retention sweep clears the older one. Neither outcome is harmful: an overwritten dlq_item is a
--     stale failure replaced by a current one, and merged spend counters are summed either way. What is lost
--     is only the ability to read a value in this column without knowing which era wrote it.
--   * Per-generation spend reporting therefore straddles two number spaces permanently. That is a reporting
--     caveat and not a defect, because the daily pause gate reads SUM(...) WHERE pipeline_name = ? AND
--     day = ? and therefore sums across every value of this component: a generation bump spreads a day's
--     total over more rows, it does not reset an exhausted budget.
--
-- The partial unique index and the primary key FOLLOW the renamed column automatically, so neither is
-- dropped and recreated. dlq_item_row_identity_uk keeps its name for the same reason it kept it through V8:
-- it describes the identity it enforces, not the columns it is built from.
--
-- This header is checksummed by Flyway. Do not edit it once the migration has been applied anywhere.

ALTER TABLE dlq_item RENAME COLUMN evaluator_version TO pipeline_generation;
ALTER TABLE dlq_item ALTER COLUMN pipeline_generation TYPE BIGINT;

ALTER TABLE spend_counter RENAME COLUMN evaluator_version TO pipeline_generation;
ALTER TABLE spend_counter ALTER COLUMN pipeline_generation TYPE BIGINT;
