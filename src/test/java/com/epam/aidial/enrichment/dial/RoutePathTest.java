package com.epam.aidial.enrichment.dial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePathTest {

    @ParameterizedTest
    @ValueSource(strings = {"insights", "v1/insights", "a.b/c_d/e-f/~g", "Insights"})
    void wellFormedRoutesAreAccepted(String route) {
        assertThat(RoutePath.problem(route)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "../admin", "a/./b", "a/..", "/insights", "insights/", "a//b", "insights?x=1",
        "insights#top", "%2e%2e/admin", "in sights"})
    void malformedRoutesAreRefused(String route) {
        assertThat(RoutePath.problem(route)).isPresent();
    }

    @Test
    void lengthIsBoundedAt256() {
        assertThat(RoutePath.problem("a".repeat(256))).isEmpty();
        assertThat(RoutePath.problem("a".repeat(257))).isPresent();
    }
}
