package com.epam.aidial.enrichment.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransitionLogTest {

    private final TransitionLog<Boolean> transitions = new TransitionLog<>(Boolean.FALSE);

    @Test
    void theRestingStateIsNoTransition() {
        assertThat(transitions.changed("p", false)).isFalse();
    }

    @Test
    void eachChangeIsReportedOnceAndPerKey() {
        assertThat(transitions.changed("p", true)).isTrue();
        assertThat(transitions.changed("p", true)).isFalse();
        assertThat(transitions.changed("q", true)).isTrue();
        assertThat(transitions.changed("p", false)).isTrue();
        assertThat(transitions.changed("p", false)).isFalse();
    }

    @Test
    void forgottenKeyReturnsToTheRestingState() {
        transitions.changed("p", true);
        transitions.forget("p");
        assertThat(transitions.changed("p", true)).isTrue();
    }
}
