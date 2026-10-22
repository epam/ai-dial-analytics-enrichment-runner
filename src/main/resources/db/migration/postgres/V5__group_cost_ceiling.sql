-- Per-group daily evaluation counter, enforcing a group rule's ready_when.cost_ceiling.
--
-- The count lives on the group rather than in a spend_counter-style table because it is per group, it is
-- read by the same per-rule query that already selects dirty and ready groups, and it is removed by the
-- eviction that already deletes quiet groups — a separate table would need its own retention sweep for
-- exactly those rows, plus a join on the sweep's hot path.
--
-- The day is stored alongside the count so the rollover needs no scheduled reset: a count recorded against
-- a previous day simply is not today's count. Both the sweep's budget predicate and the charge treat a
-- stale or absent day as "no evaluations today".
--
-- Additive and backwards-compatible: existing groups start counting at their next evaluation.
ALTER TABLE group_state ADD COLUMN evaluations_day DATE;
ALTER TABLE group_state ADD COLUMN evaluations INT NOT NULL DEFAULT 0;
