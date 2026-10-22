package com.epam.aidial.enrichment.web.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bounds on the introspection settings. Without them a negative value fails startup with an exception out
 * of {@code Duration} or Caffeine that names neither the property nor the variable, and a zero timeout
 * fails nothing at all — it is how {@code SimpleClientHttpRequestFactory} spells "no timeout", so it
 * reinstates the held-serving-thread failure the setting exists to prevent.
 */
class OpaqueTokenPropertiesTest {

    @Test
    void acceptsTheShippedDefaults() {
        assertThatCode(() -> new OpaqueTokenProperties(60, 10000, 3000)).doesNotThrowAnyException();
    }

    /** Documented as the way to turn caching off where immediate revocation matters, so it must stay legal. */
    @Test
    void acceptsZeroTtlAsTheDisableSwitch() {
        assertThatCode(() -> new OpaqueTokenProperties(0, 10000, 3000)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNegativeTtlNamingTheVariable() {
        assertThatThrownBy(() -> new OpaqueTokenProperties(-1, 10000, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPAQUE_TOKEN_CACHE_TTL_SECONDS");
    }

    @Test
    void rejectsNegativeMaxSizeNamingTheVariable() {
        assertThatThrownBy(() -> new OpaqueTokenProperties(60, -1, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPAQUE_TOKEN_CACHE_MAX_SIZE");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveTimeoutNamingTheVariable(int requestTimeoutMs) {
        assertThatThrownBy(() -> new OpaqueTokenProperties(60, 10000, requestTimeoutMs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPAQUE_TOKEN_REQUEST_TIMEOUT_MS");
    }
}
