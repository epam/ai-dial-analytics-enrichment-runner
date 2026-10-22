## 1. Startup posture

- [x] 1.1 `configuration/SensitivePostureLogger` — on `ApplicationReadyEvent`, log the identity expectation
  (an entitlement to read `sensitive` columns is required for any body-reading rule) and the caveat that
  ADAS's check fails **open** when ADAS security is disabled, so local development cannot surface an identity
  misconfigured on a running deployment
- [x] 1.2 No config knob and no runtime probe — the runner cannot verify its own entitlement without a call,
  and a flag asserting it would only ever mislead. Recorded in `design.md`

## 2. Body-handling discipline

- [x] 2.1 `worker/group/GroupPayload` — javadoc the keys-only payload as a **privacy** boundary, not only a
  size one, so a future change that adds member rows "for convenience" is visibly wrong
- [x] 2.2 `worker/group/GroupWorkerService` — javadoc that assembled input and rendered prompts are never
  logged at any level, and that the dead-letter raw response is the sole persistence point for model output
- [x] 2.3 Audit the group and worker paths for any log statement that could carry member content or a
  rendered prompt; fix anything found

## 3. Documentation

- [x] 3.1 `README.md` — replace the stale row-only "How it works" framing with the three execution lanes,
  and add the identity posture (what forced it, what still bounds the runner, the deferred narrower grant)
- [x] 3.2 `docs/configuration.md` — document the identity requirement and the `security.mode=none` fail-open
  caveat under the ADAS section; note that no runner setting controls it
- [x] 3.3 `openspec/config.yaml` `context:` — rewrite the stale scope / deferred paragraph, which now
  misstates what is shipped, and record the `FULL_ADMIN` posture

## 4. Verification

- [x] 4.1 Test: a Log4j2 in-memory appender captures every level while the group worker evaluates a group
  with identifiable member content; assert no record contains it. Cover the failure paths too, since a
  dead-letter is where a payload is most tempting to log
- [x] 4.2 Test: a serialized `GroupPayload` contains no member content
- [x] 4.3 Test: the startup posture line is logged
- [x] 4.4 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes
- [x] 4.5 `openspec validate add-sensitive-posture --strict` passes
