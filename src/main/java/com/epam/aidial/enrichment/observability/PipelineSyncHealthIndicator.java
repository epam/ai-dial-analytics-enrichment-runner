package com.epam.aidial.enrichment.observability;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness contributor (health key {@code pipelineSync}): UP once the runner has completed at least one
 * successful ADAS pipeline sync, DOWN until then. Included in the readiness group so the pod is only
 * considered ready after its pipeline cache is populated.
 */
@Component("pipelineSync")
@RequiredArgsConstructor
public class PipelineSyncHealthIndicator implements HealthIndicator {

    private final ReadinessState readinessState;

    @Override
    public Health health() {
        if (readinessState.isPipelineSyncSucceeded()) {
            return Health.up().build();
        }
        return Health.down().withDetail("reason", "no successful pipeline sync yet").build();
    }
}
