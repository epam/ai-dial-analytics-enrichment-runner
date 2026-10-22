package com.epam.aidial.enrichment.web.security;

import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Set;

/**
 * What a custom role converter is given to derive a caller's provider role names: the caller's own token
 * (so the converter can call a further API on their behalf), the claims the userinfo endpoint returned,
 * the claim names the caller's email may be read from, and a client to make that call with.
 *
 * @param restClient  client used for any further call the converter makes
 * @param token       the caller's opaque access token
 * @param attributes  the claims returned by the provider's userinfo endpoint
 * @param emailClaims claim names to read the caller's email from, tried in order
 */
public record OpaqueAuthorityExtractionContext(
        RestClient restClient,
        String token,
        Map<String, Object> attributes,
        Set<String> emailClaims) {
}
