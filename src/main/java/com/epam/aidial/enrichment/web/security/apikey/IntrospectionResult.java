package com.epam.aidial.enrichment.web.security.apikey;

import java.util.List;

/**
 * Result of a DIAL Core {@code /v1/user/info} introspection.
 *
 * <p>Core returns one of two shapes depending on what minted the key:</p>
 * <ul>
 *     <li><b>Project-key auth</b> ({@code fromProjectKey == true}) — response has {@code project};
 *         {@code principal} is the project name and {@code email} is {@code null} (a project key carries
 *         no user claims).</li>
 *     <li><b>JWT-rooted per-request key</b> ({@code fromProjectKey == false}) — response has
 *         {@code userClaims}; {@code principal} and {@code email} are extracted from the configured
 *         default claims.</li>
 * </ul>
 *
 * <p>The shape decides which roles mapping applies, since a JWT-rooted key carries the same role names
 * its user would carry calling with a JWT directly. ADAS additionally retains the normalized user claims
 * here for per-table grant evaluation; the runner has no per-target authorization, so they are omitted.
 */
public record IntrospectionResult(String principal, String email, List<String> rawRoles, boolean fromProjectKey) {
}
