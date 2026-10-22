package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.web.security.UserRole;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Typed binding for API-key authentication ({@code security.api-key.*}), active only when
 * {@code security.api-key.enabled=true}. Validation delegates to DIAL Core {@code /v1/user/info}
 * (see {@link CoreApiKeyIntrospector}); Core role names are mapped onto the application roles
 * ({@code FULL_ADMIN} / {@code READ_ONLY_ADMIN}) via two separate mappings — {@code roles-mapping}
 * for project-key callers and the OIDC {@code security.defaults.roles-mapping} for JWT-rooted
 * per-request keys, which carry the same role names a JWT caller would.
 *
 * <p>Property descriptions live in {@code docs/configuration.md}; env-driven via {@code application.yml}.
 */
@Getter
@Setter
@Slf4j
@Component
@ConfigurationProperties(prefix = "security.api-key")
@ConditionalOnExpression(ApiKeyProperties.ACTIVE_CONDITION)
public class ApiKeyProperties {

    /**
     * SpEL guard shared by every API-key bean: they exist only when the security mode is {@code oidc}
     * and {@code security.api-key.enabled=true}. This enforces that API-key auth is layered onto the
     * OIDC resource server and never activates in {@code none} mode — so enabling it can never be the
     * only thing standing between an open instance and a closed one.
     */
    public static final String ACTIVE_CONDITION =
            "'${security.mode:none}' == 'oidc' and ${security.api-key.enabled:false}";

    private final JsonMapper jsonMapper;
    private final String defaultRolesMappingJson;

    private boolean enabled;
    private String coreUrl;
    private int cacheTtlSeconds;
    private int cacheMaxSize;
    private int requestTimeoutMs;
    private String rolesMapping;
    private boolean startupProbe;

    private Map<String, Set<UserRole>> parsedRolesMapping = Collections.emptyMap();
    private Map<String, Set<UserRole>> parsedDefaultRolesMapping = Collections.emptyMap();

    public ApiKeyProperties(JsonMapper jsonMapper,
                            @Value("${security.defaults.roles-mapping:}") String defaultRolesMappingJson) {
        this.jsonMapper = jsonMapper;
        this.defaultRolesMappingJson = defaultRolesMappingJson;
    }

    @PostConstruct
    public void validate() {
        if (!enabled) {
            log.debug("API-key authentication is disabled");
            return;
        }

        if (StringUtils.isBlank(coreUrl)) {
            throw new IllegalStateException(
                    "security.api-key.enabled=true requires security.api-key.core-url to be set");
        }

        parsedRolesMapping = parseRolesMapping(rolesMapping, "security.api-key.roles-mapping");
        parsedDefaultRolesMapping = parseRolesMapping(defaultRolesMappingJson, "security.defaults.roles-mapping");
        if (parsedRolesMapping.isEmpty() && parsedDefaultRolesMapping.isEmpty()) {
            throw new IllegalStateException(
                    "security.api-key.enabled=true requires at least one of "
                            + "security.api-key.roles-mapping (used for project-key callers) or "
                            + "security.defaults.roles-mapping (used for JWT-rooted per-request keys) "
                            + "to map at least one role to an application authority (FULL_ADMIN / READ_ONLY_ADMIN). "
                            + "Otherwise every authenticated API-key caller would be rejected with 403.");
        }

        log.info("API-key authentication is enabled. Core URL: {}, cache TTL: {}s, "
                        + "project-key mapped roles: {}, default mapped roles (JWT-root path): {}",
                coreUrl, cacheTtlSeconds, parsedRolesMapping.keySet(), parsedDefaultRolesMapping.keySet());
    }

    private Map<String, Set<UserRole>> parseRolesMapping(String json, String propertyName) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyMap();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<>() { });
        } catch (JacksonException ex) {
            throw new IllegalStateException("Invalid " + propertyName + " JSON: " + ex.getMessage(), ex);
        }
    }
}
