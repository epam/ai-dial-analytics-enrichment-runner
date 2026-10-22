package com.epam.aidial.enrichment.web.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a credential into a stable cache key that is not the credential. Both authentication caches —
 * {@link CachingOpaqueTokenIntrospector} for opaque bearer tokens and
 * {@code apikey.ApiKeyCache} for DIAL API keys — key their entries by this digest rather than by the raw
 * secret, so a heap dump or a cache-inspection tool never yields a usable credential.
 *
 * <p>Runner-only, with no ADAS counterpart: ADAS caches neither.
 */
public final class TokenDigest {

    private TokenDigest() {
    }

    /** The SHA-256 digest of {@code value}, lowercase hex. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a mandated JDK algorithm; its absence is not recoverable.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
