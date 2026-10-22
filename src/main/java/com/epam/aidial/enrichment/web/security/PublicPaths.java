package com.epam.aidial.enrichment.web.security;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * The paths served without a credential. Probes are how an orchestrator decides whether the process is
 * alive, so gating them on the identity provider would turn an IdP outage into a restart loop; metrics carry
 * identifiers and counts only, never enrichment content. The API description is public only when
 * {@link SecurityProperties#disableSwaggerAuthorization()} says so: it maps every functional endpoint, which
 * is reconnaissance an anonymous caller has no use for.
 *
 * <p>Held here rather than inside {@link OidcSecurityConfiguration} because two places must agree on the
 * list: the authorization rules permit these paths, and
 * {@link com.epam.aidial.enrichment.web.security.apikey.ApiKeyAuthenticationFilter} skips them entirely.
 * A filter that ran on a public path would let a caller presenting a <em>bad</em> {@code Api-Key} — a
 * scrape agent misconfigured with a DIAL key, say — turn a metrics scrape into a 401, or into a 503 while
 * Core is unreachable. Splitting the list across two files is how those two views drift apart.
 *
 * <p>Note that {@code /actuator/health/**} also matches {@code /actuator/health} itself, since {@code /**}
 * spans zero or more segments — so the aggregate endpoint and both probe groups are covered.
 */
public final class PublicPaths {

    /** Always public, in every security mode. */
    public static final String[] OPERATIONAL_PATTERNS = {
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
    };

    /** The OpenAPI description and the Swagger UI rendered from it. */
    public static final String[] API_DOCS_PATTERNS = {
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/v3/api-docs/**",
    };

    private PublicPaths() {
    }

    /** The public patterns: the operational ones, plus the API description when it is served openly. */
    public static String[] patterns(boolean apiDocsPublic) {
        return apiDocsPublic
                ? Stream.concat(Arrays.stream(OPERATIONAL_PATTERNS), Arrays.stream(API_DOCS_PATTERNS)).toArray(String[]::new)
                : OPERATIONAL_PATTERNS.clone();
    }

    /**
     * A matcher over {@link #patterns(boolean)} for use outside the authorization rules. Uses the same
     * {@code PathPattern} semantics {@code authorizeHttpRequests} applies to the identical patterns, so the
     * two cannot disagree about what a pattern covers.
     */
    public static RequestMatcher matcher(boolean apiDocsPublic) {
        PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(Arrays.stream(patterns(apiDocsPublic))
                .<RequestMatcher>map(builder::matcher)
                .toList());
    }
}
