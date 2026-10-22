## 1. DIAL client & config

- [x] 1.1 `DialProperties`, `WorkerProperties`, `DialClientConfiguration`
- [x] 1.2 `DialClient` + `ChatResponse`/`Choice`/`Message`/`Usage` DTOs
- [x] 1.3 `dial.*` / `worker.*` in `application.yml` + `docs/configuration.md`

## 2. Request assembly, validation, mapping

- [x] 2.1 `TemplateRenderer` (`{{var}}` substitution with JSON-safe escaping)
- [x] 2.2 `DialRequestBuilder` (params + `response_format`; corrective repair message)
- [x] 2.3 `ResponseValidator` (networknt JSON Schema)
- [x] 2.4 `OutputMapper` (output-var JSONata → columns) + `OutputMappingException`

## 3. Worker & write-back

- [x] 3.1 `AdasUpsertClient` (`POST /v1/rules/{id}/rows`) + `WriteRowsResponse`
- [x] 3.2 `WorkerService` (per-row DIAL → validate + repair → map → provenance; failure taxonomy;
  stale/lifecycle guards; chunk upsert) + `ResponseValidationException`
- [x] 3.3 Wire `EnrichChunkTask` → `WorkerService` via `ObjectProvider`

## 4. Verification

- [x] 4.1 Unit tests: `TemplateRenderer`, `ResponseValidator`, `OutputMapper`
- [x] 4.2 Integration test: chunk → DIAL → upsert end-to-end; invalid → DLQ; stale chunk dropped
  (WireMock DIAL + ADAS + Testcontainers PG)
- [x] 4.3 `./gradlew clean build` passes
- [x] 4.4 `openspec validate add-enrichment-worker --strict` passes
