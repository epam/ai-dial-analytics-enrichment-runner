-- Group-grain dead-lettering. Two additive changes to dlq_item.

-- 1. Admit the group stages. The constraint is kept rather than dropped: it is what makes an unrecognised
--    stage a database error instead of a stored typo, and the transaction tests rely on being able to force
--    a failure with an invalid stage.
ALTER TABLE dlq_item DROP CONSTRAINT dlq_item_stage_check;

ALTER TABLE dlq_item ADD CONSTRAINT dlq_item_stage_check
    CHECK (stage IN ('input_map', 'dial_call', 'validate', 'output_map', 'upsert',
                     'group_fetch', 'group_map'));

-- 2. Record which task an archived payload belongs to. Requeue deserializes `payload` into a work item and
--    re-enqueues it, so it must know whether that JSON is a ChunkPayload (enrich-chunk) or a GroupPayload
--    (enrich-group) — without this it would deserialize a group payload as a chunk and enqueue a work item
--    with no rows. Existing rows predate group work entirely, hence the enrich-chunk default.
ALTER TABLE dlq_item ADD COLUMN task_name TEXT NOT NULL DEFAULT 'enrich-chunk';
