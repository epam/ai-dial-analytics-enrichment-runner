package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.web.security.SecurityProperties;
import com.epam.aidial.enrichment.web.security.SecurityProperties.Defaults;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates a DIAL {@code Api-Key} by introspecting it against DIAL Core's {@code /v1/user/info}
 * endpoint and turning the response into an {@link IntrospectionResult}. The body is read as a raw
 * string and parsed with the application {@link JsonMapper} because Core labels its JSON response as
 * {@code application/octet-stream}, which content-negotiated deserialization would reject.
 *
 * <p>A key Core rejects (any 4xx/5xx) or a malformed response becomes a {@link BadCredentialsException}
 * (rendered as 401); a connection failure or timeout becomes an {@link AuthenticationServiceException}
 * (rendered as 503). That distinction is the point: a 401 says the caller is wrong, a 503 says the
 * runner cannot currently tell.
 *
 * <p>{@code SECURITY_REQUIRE_EMAIL} is enforced on the JWT-rooted shape only — see
 * {@link #introspect(String)} for why a project key is exempt.
 */
@Slf4j
@Component
@ConditionalOnExpression(ApiKeyProperties.ACTIVE_CONDITION)
public class CoreApiKeyIntrospector {

    public static final String API_KEY_HEADER = "Api-Key";
    private static final String USER_INFO_PATH = "/v1/user/info";
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() { };

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String userInfoUrl;
    private final String defaultPrincipalClaim;
    private final String defaultEmailClaim;
    private final boolean startupProbe;
    private final boolean requireEmail;

    /**
     * Reads the claim names from {@link SecurityProperties} rather than re-reading
     * {@code security.defaults.*} directly: a JWT-rooted key carries the same claims its user's JWT would,
     * so the two paths must resolve the principal and email from the same configuration or a caller's
     * identity would depend on which credential they presented.
     */
    @Autowired
    public CoreApiKeyIntrospector(ApiKeyProperties properties,
                                  SecurityProperties securityProperties,
                                  JsonMapper jsonMapper) {
        this(buildRestClient(properties.getRequestTimeoutMs()), jsonMapper, properties.getCoreUrl(),
                requiredPrincipalClaim(securityProperties), emailClaim(securityProperties),
                properties.isStartupProbe(), securityProperties.requireEmail());
    }

    CoreApiKeyIntrospector(RestClient restClient,
                           JsonMapper jsonMapper,
                           String coreUrl,
                           String defaultPrincipalClaim,
                           String defaultEmailClaim,
                           boolean startupProbe,
                           boolean requireEmail) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
        this.userInfoUrl = Strings.CS.removeEnd(coreUrl, "/") + USER_INFO_PATH;
        this.defaultPrincipalClaim = defaultPrincipalClaim;
        this.defaultEmailClaim = defaultEmailClaim;
        this.startupProbe = startupProbe;
        this.requireEmail = requireEmail;
    }

    /**
     * The principal claim is the only way to name a JWT-rooted caller, so a blank one would reject every
     * such key at request time with nothing to point at. Fail at startup instead.
     */
    private static String requiredPrincipalClaim(SecurityProperties securityProperties) {
        Defaults defaults = securityProperties.defaults();
        String principalClaim = defaults != null ? defaults.principalClaim() : null;
        if (StringUtils.isBlank(principalClaim)) {
            throw new IllegalStateException(
                    "API-key authentication needs a principal claim to read a JWT-rooted caller's user id from "
                            + "Core's userClaims response: set SECURITY_USER_CLAIM "
                            + "(security.defaults.principal-claim).");
        }
        return principalClaim;
    }

    private static String emailClaim(SecurityProperties securityProperties) {
        Defaults defaults = securityProperties.defaults();
        return defaults != null ? defaults.emailClaim() : null;
    }

    private static RestClient buildRestClient(int requestTimeoutMs) {
        Duration timeout = Duration.ofMillis(requestTimeoutMs);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    @PostConstruct
    public void probeCore() {
        if (!startupProbe) {
            return;
        }
        try {
            fetchUserInfo("enrichment-runner-startup-probe");
            log.info("DIAL Core /v1/user/info reachable at {}", userInfoUrl);
        } catch (BadCredentialsException ex) {
            // Core reached and answered (rejected the probe key) — that is what we want to confirm.
            log.info("DIAL Core /v1/user/info reachable at {} (rejected probe key)", userInfoUrl);
        } catch (AuthenticationServiceException ex) {
            throw new IllegalStateException(
                    "DIAL Core /v1/user/info is unreachable at " + userInfoUrl
                            + ". Disable the startup probe via API_KEY_STARTUP_PROBE=false to skip this check.",
                    ex);
        }
    }

    public IntrospectionResult introspect(String apiKey) {
        Map<String, Object> response = fetchUserInfo(apiKey);
        List<String> rawRoles = extractRoles(response.get("roles"));

        if (response.get("project") instanceof String project && StringUtils.isNotBlank(project)) {
            return new IntrospectionResult(project, null, rawRoles, true);
        }

        if (response.get("userClaims") instanceof Map<?, ?> userClaimsRaw && !userClaimsRaw.isEmpty()) {
            Map<String, List<String>> userClaims = normalizeUserClaims(userClaimsRaw);
            String principal = firstNonBlank(userClaims.get(defaultPrincipalClaim));
            if (StringUtils.isBlank(principal)) {
                log.warn("Core /v1/user/info userClaims response is missing the configured principal claim '{}'",
                        defaultPrincipalClaim);
                throw new BadCredentialsException("Malformed Core user-info response");
            }
            String email = firstNonBlank(userClaims.get(defaultEmailClaim));
            // SECURITY_REQUIRE_EMAIL applies here but not to a project key: this shape is a user identity,
            // carrying the same claims that user's JWT would, so it is held to the same rule. A project key
            // is a service identity with no user behind it, and Core never returns an email for one — gating
            // it on an email would make the flag silently incompatible with project-key auth.
            if (requireEmail && StringUtils.isBlank(email)) {
                log.warn("Core /v1/user/info userClaims response is missing the configured email claim '{}' and "
                        + "SECURITY_REQUIRE_EMAIL is enabled", defaultEmailClaim);
                throw new BadCredentialsException("Email claim is required");
            }
            return new IntrospectionResult(principal, email, rawRoles, false);
        }

        log.warn("Core /v1/user/info response contains neither 'project' nor 'userClaims'");
        throw new BadCredentialsException("Malformed Core user-info response");
    }

    private Map<String, Object> fetchUserInfo(String apiKey) {
        String body;
        try {
            body = restClient.get()
                    .uri(userInfoUrl)
                    .header(API_KEY_HEADER, apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            log.debug("Core /v1/user/info rejected API key with status {}", ex.getStatusCode());
            throw new BadCredentialsException("Invalid API key");
        } catch (ResourceAccessException ex) {
            log.warn("Failed to reach Core /v1/user/info at {}", userInfoUrl, ex);
            throw new AuthenticationServiceException("Failed to validate API key with DIAL Core", ex);
        }
        if (StringUtils.isBlank(body)) {
            log.debug("Core /v1/user/info returned an empty body");
            throw new BadCredentialsException("Invalid API key");
        }
        try {
            return jsonMapper.readValue(body, STRING_OBJECT_MAP);
        } catch (JacksonException ex) {
            log.debug("Core /v1/user/info returned an unparseable body");
            throw new BadCredentialsException("Malformed Core user-info response");
        }
    }

    private List<String> extractRoles(Object rolesClaim) {
        if (rolesClaim instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    private static Map<String, List<String>> normalizeUserClaims(Map<?, ?> raw) {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                List<String> strings = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
                result.put(name, strings);
            } else if (value instanceof String s) {
                result.put(name, List.of(s));
            }
        }
        return result;
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }
}
