package com.epam.aidial.enrichment.web.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation of the security mode value. The point is that only the two canonical lowercase values leave
 * the service in a configuration someone chose: anything else activates neither security configuration
 * (so Spring Boot's default chain demands HTTP Basic on every path, probes included), and a
 * differently-cased value would enforce authentication while silently dropping every API-key bean.
 */
class SecurityPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {SecurityProperties.MODE_NONE, SecurityProperties.MODE_OIDC})
    void acceptsTheCanonicalModes(String mode) {
        assertThatCode(() -> new SecurityProperties(mode, false, false, null)).doesNotThrowAnyException();
    }

    /** An absent property is the case {@code matchIfMissing = true} covers, and means {@code none}. */
    @Test
    void acceptsAnAbsentMode() {
        assertThatCode(() -> new SecurityProperties(null, false, false, null)).doesNotThrowAnyException();
    }

    /**
     * {@code ""} is included deliberately: an empty {@code SECURITY_MODE} is a *present* property, so
     * {@code matchIfMissing} does not rescue it and neither configuration activates.
     */
    @ParameterizedTest
    @ValueSource(strings = {"OIDC", "Oidc", "None", "oidcc", "disabled", " oidc", ""})
    void rejectsAnythingElseNamingTheVariable(String mode) {
        assertThatThrownBy(() -> new SecurityProperties(mode, false, false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECURITY_MODE")
                .hasMessageContaining("lowercase");
    }
}
