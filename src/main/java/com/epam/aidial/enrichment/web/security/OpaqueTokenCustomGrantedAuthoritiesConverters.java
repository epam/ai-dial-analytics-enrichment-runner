package com.epam.aidial.enrichment.web.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Named authority converters for opaque providers whose userinfo response carries no role information,
 * so roles must be derived from another source. A provider opts in by setting its {@code role-claims}
 * to the single value {@code fn:<name>} instead of claim paths; {@link DefaultOpaqueTokenIntrospector}
 * then calls the named converter with the caller's token rather than reading claim paths.
 *
 * <p>The only converter today is {@code fn:getGoogleWorkspaceGroups}, which resolves a Google caller's
 * <b>direct</b> Google Workspace group memberships via the Cloud Identity API and returns each group's
 * id (its email address) as a provider role name — which the provider's {@code roles-mapping} then maps
 * onto application roles, exactly as a claim-derived role name would be.
 *
 * <p>Ported from ADAS for cross-service parity; like it, only the first page of memberships is read (a
 * caller in more direct groups than one page holds is logged, not paged through).
 */
@Slf4j
public final class OpaqueTokenCustomGrantedAuthoritiesConverters {

    /** Marks a {@code role-claims} entry as a converter name rather than a claim path. */
    public static final String CUSTOM_CONVERTER_PREFIX = "fn:";

    /** Derives roles from the caller's direct Google Workspace group memberships. */
    public static final String GOOGLE_WORKSPACE_GROUPS = "fn:getGoogleWorkspaceGroups";

    public static final Map<String, Function<OpaqueAuthorityExtractionContext, Collection<GrantedAuthority>>> CONVERTERS =
            Map.of(GOOGLE_WORKSPACE_GROUPS, OpaqueTokenCustomGrantedAuthoritiesConverters::googleWorkspaceGroups);

    private static final String GOOGLE_DIRECT_GROUPS_URL = "https://content-cloudidentity.googleapis.com"
            + "/v1/groups/-/memberships:searchDirectGroups?query=member_key_id=='{email}'";

    private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new ParameterizedTypeReference<>() {
            };

    private OpaqueTokenCustomGrantedAuthoritiesConverters() {
    }

    /**
     * Whether the configured {@code role-claims} select a custom converter: exactly one entry, prefixed
     * {@code fn:}. Any other shape is read as ordinary dotted claim paths.
     */
    public static boolean isCustomConverterSelection(List<String> roleClaims) {
        return roleClaims != null && roleClaims.size() == 1 && roleClaims.getFirst().startsWith(CUSTOM_CONVERTER_PREFIX);
    }

    /** The converter registered under {@code name}, or {@code null} when no such converter exists. */
    public static Function<OpaqueAuthorityExtractionContext, Collection<GrantedAuthority>> converterFor(String name) {
        return CONVERTERS.get(name);
    }

    private static Collection<GrantedAuthority> googleWorkspaceGroups(OpaqueAuthorityExtractionContext context) {
        String email = extractEmail(context);
        Map<String, Object> response = searchDirectGroups(context, email);
        return extractGroupAuthorities(response);
    }

    private static String extractEmail(OpaqueAuthorityExtractionContext context) {
        for (String claim : context.emailClaims()) {
            Object value = context.attributes().get(claim);
            if (value != null) {
                return value.toString();
            }
        }
        throw new OAuth2IntrospectionException("Email claim is missing in the userinfo response");
    }

    private static Map<String, Object> searchDirectGroups(OpaqueAuthorityExtractionContext context, String email) {
        try {
            Map<String, Object> body = context.restClient().get()
                    .uri(GOOGLE_DIRECT_GROUPS_URL, email)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + context.token())
                    .retrieve()
                    .body(STRING_OBJECT_MAP);
            if (body == null) {
                throw new OAuth2IntrospectionException("Empty group-membership response for " + email);
            }
            return body;
        } catch (OAuth2IntrospectionException ex) {
            throw ex;
        } catch (Exception ex) {
            log.debug("Failed to retrieve Google Workspace groups for {}", email, ex);
            throw new OAuth2IntrospectionException("Failed to retrieve Google Workspace groups", ex);
        }
    }

    private static Collection<GrantedAuthority> extractGroupAuthorities(Map<String, Object> response) {
        if (response.get("nextPageToken") instanceof String token && !token.isBlank()) {
            log.warn("Google Workspace group membership response is paginated; only the first page is read");
        }
        if (!(response.get("memberships") instanceof Collection<?> memberships)) {
            return List.of();
        }

        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (Object membership : memberships) {
            if (membership instanceof Map<?, ?> entry
                    && entry.get("groupKey") instanceof Map<?, ?> groupKey
                    && groupKey.get("id") instanceof String id) {
                authorities.add(new SimpleGrantedAuthority(id));
            }
        }
        return authorities;
    }
}
