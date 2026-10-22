package com.epam.aidial.enrichment.web.security;

/**
 * Caller details carried on the authentication token's {@code details} slot. Kept separate from the
 * authorities so non-authorization attributes (currently the email) can be read from the security
 * context without re-parsing the token.
 *
 * @param email the caller's email if present in the token, otherwise {@code null}
 */
public record UserSecurityDetails(String email) {
}
