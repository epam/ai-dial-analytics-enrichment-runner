## Context

This change fills the `enrich-chunk` handler stubbed in `add-incremental-scan`. Each work item is one chunk of rows carrying
already-extracted input variables; the worker turns each into an enrichment write. Exactly-once
*effect* is backstopped by the enrichment table's grain-key RMT collapse on the ADAS side, so
duplicate model calls (from retries) cost money but never corrupt results.

## Goals / Non-Goals

**Goals:** render → DIAL → validate (+1 repair) → output-map → provenance → rule-scoped upsert, with
the per-row/per-chunk failure taxonomy and stale/lifecycle guards.

**Non-Goals:** the rate limiter and backoff→Delayed retry scheduling, the DLQ failure-handler/requeue
API and DLQ circuit breaker (`add-resilience-and-dlq`); spend counting (`add-spend-and-backfill`); row parallelism (config carried, wired in
`add-resilience-and-dlq`).

## Decisions

- **Body assembly via overlay.** The rendered template supplies `messages`; the builder overlays
  evaluator `params` and a `response_format = json_schema` from the response schema. The repair
  request re-uses the built body and appends a corrective user message — keeping the schema and
  params intact across the retry.
- **String-escape template substitution.** `{{var}}` string values are JSON-string-escaped (via the
  app mapper) for safe embedding in a JSON string; numbers/booleans become JSON literals; null renders
  empty. This covers the initial chat-prompt case; a structured templating engine is future work.
- **networknt validates a JSON string directly.** `getSchema(schemaJson)` + `validate(content,
  InputFormat.JSON)` avoids manual Jackson-node juggling. Missing/malformed content is treated as a
  validation failure so the repair/validate gate handles it uniformly.
- **Failure taxonomy at two levels.** Retryable DIAL (429/5xx) and upsert failures propagate to fail
  the chunk (db-scheduler retries — refined in `add-resilience-and-dlq`); permanent per-row errors are dead-lettered by
  stage. Successful rows in a chunk upsert regardless of sibling failures.
- **Guards at start and before upsert.** The stale/lifecycle check runs both up front (skip cheaply)
  and again just before the write (a disable/re-pin mid-flight stops the side effect promptly);
  last-write-wins by `_updated_at` is the ultimate arbiter.

## Risks / Trade-offs

- **Recompute-on-retry** → a chunk retry re-calls DIAL for rows already computed (duplicate cost,
  surfaced in the `add-spend-and-backfill` spend counter). Accepted for now; the two-phase compute/upsert split is
  deferred.
- **Template escaping assumes in-string embedding** → documented; richer templating deferred.
- **Per-row schema compile** → `ResponseValidator` compiles the schema per validate call; acceptable
  at initial volumes, cacheable later.
