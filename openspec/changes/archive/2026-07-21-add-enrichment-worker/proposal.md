## Why

`add-incremental-scan` enqueues chunk work items but their handler is a placeholder. This change
implements the worker: it turns each chunk row into an enrichment write by calling DIAL Core,
validating and mapping the structured response, and upserting through ADAS — completing the
end-to-end row → enrichment path.

## What Changes

- **DIAL client**: `DialClient` calling `POST {DIAL}/openai/deployments/{model}/chat/completions`
  (Api-Key), plus a minimal `ChatResponse` view.
- **Request assembly**: `TemplateRenderer` (`{{input-var}}` substitution) and `DialRequestBuilder`
  (overlay evaluator `params` + `response_format = json_schema`; build the repair request).
- **Validation + repair**: `ResponseValidator` (networknt JSON Schema); one repair-retry that
  re-sends the request with the validation error as a corrective message.
- **Output mapping**: `OutputMapper` evaluates each output variable's JSONata over the response and
  maps it to the bound enrichment column.
- **Write-back**: `AdasUpsertClient` posting grain key + output columns + provenance
  (`evaluator_version`, `model`, `enriched_at`) to `POST /v1/rules/{id}/rows`.
- **Worker orchestration**: `WorkerService` processes a chunk with per-row failure isolation
  (non-retryable DIAL 4xx → `dial_call`, schema-invalid-after-repair → `validate`, output-map error →
  `output_map`; DIAL 429/5xx and upsert failures propagate for chunk retry) and stale-evaluator +
  rule-lifecycle guards. The `enrich-chunk` handler now delegates to it.
- **Config**: `dial.*` (base URL, api-key, timeouts) and `worker.*` (repair-retries, row-parallelism).

## Capabilities

### New Capabilities
- `enrichment-worker`: turning a chunk row into a validated, output-mapped, rule-scoped enrichment
  upsert with provenance, under the per-row/per-chunk failure taxonomy and the stale/lifecycle guards.

### Modified Capabilities
<!-- none: the incremental-scan capability's enrich-chunk handler is filled in; its spec is unchanged -->

## Impact

- New dep: `com.networknt:json-schema-validator:1.5.6`.
- New code under `com.epam.aidial.enrichment`: `dial` (client + dto), `worker` (`TemplateRenderer`,
  `DialRequestBuilder`, `ResponseValidator`, `OutputMapper`, `WorkerService`), `adas.AdasUpsertClient`
  (+ `WriteRowsResponse`), `exception.{OutputMappingException,ResponseValidationException}`,
  DIAL/worker configuration.
- No new migration. The full failure taxonomy (backoff→Delayed, DLQ archive, DLQ circuit breaker)
  and the rate limiter arrive in `add-resilience-and-dlq`.
