package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.web.security.UserRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup validation of the API-key configuration. The point of each check is to turn a configuration
 * that would authenticate callers and then deny all of them with 403 into a startup failure that names
 * the missing property.
 */
class ApiKeyPropertiesTest {

    private static final JsonMapper MAPPER = new JsonMapperConfiguration().jsonMapper();

    private static ApiKeyProperties properties(String defaultRolesMapping) {
        ApiKeyProperties properties = new ApiKeyProperties(MAPPER, defaultRolesMapping);
        properties.setEnabled(true);
        properties.setCoreUrl("http://core.example");
        properties.setCacheTtlSeconds(60);
        properties.setCacheMaxSize(100);
        properties.setRequestTimeoutMs(3000);
        return properties;
    }

    @Test
    void disabledConfigurationSkipsValidationEntirely() {
        ApiKeyProperties properties = new ApiKeyProperties(MAPPER, "");
        properties.setEnabled(false);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void enabledWithoutCoreUrlFailsFast() {
        ApiKeyProperties properties = properties("{\"admin\":[\"FULL_ADMIN\"]}");
        properties.setCoreUrl("  ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.api-key.core-url");
    }

    @Test
    void enabledWithNoRoleMappingAtAllFailsFast() {
        ApiKeyProperties properties = properties("");
        properties.setRolesMapping("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roles-mapping");
    }

    @Test
    void theDefaultMappingAloneIsEnough() {
        ApiKeyProperties properties = properties("{\"admin\":[\"FULL_ADMIN\"]}");
        properties.setRolesMapping("");

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getParsedDefaultRolesMapping())
                .containsEntry("admin", Set.of(UserRole.FULL_ADMIN));
        assertThat(properties.getParsedRolesMapping()).isEmpty();
    }

    @Test
    void theProjectKeyMappingAloneIsEnough() {
        ApiKeyProperties properties = properties("");
        properties.setRolesMapping("{\"project-admin\":[\"FULL_ADMIN\"]}");

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getParsedRolesMapping())
                .containsEntry("project-admin", Set.of(UserRole.FULL_ADMIN));
    }

    @Test
    void bothMappingsAreParsedSeparately() {
        ApiKeyProperties properties = properties("{\"admin\":[\"FULL_ADMIN\"]}");
        properties.setRolesMapping("{\"project-admin\":[\"READ_ONLY_ADMIN\"]}");

        properties.validate();

        assertThat(properties.getParsedDefaultRolesMapping()).containsOnlyKeys("admin");
        assertThat(properties.getParsedRolesMapping())
                .containsEntry("project-admin", Set.of(UserRole.READ_ONLY_ADMIN));
    }

    @Test
    void invalidMappingJsonFailsFastNamingTheProperty() {
        ApiKeyProperties properties = properties("");
        properties.setRolesMapping("{not json");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.api-key.roles-mapping");
    }
}
