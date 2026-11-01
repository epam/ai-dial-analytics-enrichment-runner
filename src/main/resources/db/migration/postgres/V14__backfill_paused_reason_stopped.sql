-- Adds pipeline_stopped to run_history.paused_reason; see openspec/specs/backfill/spec.md.

ALTER TABLE run_history DROP CONSTRAINT run_history_paused_reason_check;
ALTER TABLE run_history ADD CONSTRAINT run_history_paused_reason_check
    CHECK (paused_reason IS NULL OR paused_reason IN (
        'operator', 'pipeline_paused_operator', 'pipeline_paused_breaker', 'pipeline_stopped',
        'spend_budget_exhausted', 'cursor_stalled', 'pipeline_absent', 'pipeline_changed'));
