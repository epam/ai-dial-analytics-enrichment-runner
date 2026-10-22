package com.epam.aidial.enrichment.configuration;

import com.epam.aidial.enrichment.support.CapturingAppender;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The posture must be visible in the logs of the deployment that actually has it — the runner cannot verify its
 * own entitlement without making a call. Only the diagnostic facts belong there; the rationale and the deferred
 * follow-up belong to the spec and docs, so the second test pins them out of the log.
 */
class SensitivePostureLoggerTest {

    @Test
    void statesTheIdentityExpectationAndTheFailOpenCaveat() {
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            new SensitivePostureLogger().logPosture();

            assertThat(appender.containsText("FULL_ADMIN")).isTrue();
            assertThat(appender.containsText("rejects rather than strips")).isTrue();
            // The part that saves an afternoon: local development cannot surface an identity misconfigured on a running deployment.
            assertThat(appender.containsText("fails OPEN")).isTrue();
        } finally {
            appender.detach(Level.INFO);
        }
    }

    @Test
    void doesNotNarrateRationaleOrRoadmapIntoTheLog() {
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            new SensitivePostureLogger().logPosture();

            assertThat(appender.containsText("supersedes")).isFalse();
            assertThat(appender.containsText("least-privilege")).isFalse();
            assertThat(appender.containsText("follow-up")).isFalse();
            assertThat(appender.containsText("Verify the posture")).isFalse();
        } finally {
            appender.detach(Level.INFO);
        }
    }
}
