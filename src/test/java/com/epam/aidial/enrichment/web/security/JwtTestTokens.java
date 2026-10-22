package com.epam.aidial.enrichment.web.security;

import io.jsonwebtoken.Jwts;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mints locally-signed (HMAC HS256) JWTs for security slice tests, so they need no live JWKS endpoint.
 * The same shared secret backs {@link TestSecurityConfig}'s decoder, so tokens minted here verify.
 */
public final class JwtTestTokens {

    /** At least 256 bits, as required for HS256. */
    static final String SECRET = "test-secret-key-for-runner-auth-tests-0123456789";

    /** The issuer the auth tests configure as an identity provider. */
    public static final String ISSUER = "https://test-issuer.example";

    /** The audience the auth tests configure as accepted. */
    public static final String AUDIENCE = "enrichment-runner";

    private JwtTestTokens() {
    }

    public static SecretKey hmacSecretKey() {
        return new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** A valid token for the configured issuer and audience, carrying the given provider roles. */
    public static String tokenWithRoles(String... roles) {
        return token(ISSUER, AUDIENCE, Map.of("oid", "test-principal", "roles", java.util.List.of(roles)));
    }

    public static String token(String issuer, String audience, Map<String, Object> claims) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .claims().add(claims).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + 900_000))
                .signWith(hmacSecretKey(), Jwts.SIG.HS256)
                .compact();
    }

    public static String expiredToken(String issuer, String audience, Map<String, Object> claims) {
        long past = System.currentTimeMillis() - 3_600_000;
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .claims().add(claims).and()
                .issuedAt(new Date(past))
                .expiration(new Date(past + 60_000))
                .signWith(hmacSecretKey(), Jwts.SIG.HS256)
                .compact();
    }
}
