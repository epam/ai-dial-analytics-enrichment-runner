package com.epam.aidial.enrichment.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * States the runner's sensitive-data identity posture in the startup logs.
 *
 * <p>Diagnostic value only: it names the identity a {@code 403} on a body-reading pipeline points at, and the
 * fails-open case that hides such a misconfiguration locally. The rationale for the posture, what the
 * entitlement does and does not widen, and the narrower-grant follow-up live in the {@code service-runtime}
 * spec and {@code docs/configuration.md} — not here.
 *
 * <p>Logged rather than enforced: the runner cannot verify its own entitlement without making a call, and a
 * config flag asserting it would only ever mislead.
 */
@Slf4j
@Component
public class SensitivePostureLogger {

    @EventListener(ApplicationReadyEvent.class)
    public void logPosture() {
        log.info("Sensitive-data posture: pipelines referencing a sensitive column require an ADAS identity "
                + "entitled to read them (FULL_ADMIN); ADAS rejects rather than strips. Note ADAS's check "
                + "fails OPEN when ADAS security is disabled.");
    }
}
