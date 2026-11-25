package com.epam.aidial.enrichment.adas;

import org.springframework.http.HttpHeaders;

/**
 * The credential an inbound request authenticated with — a bearer token or a DIAL API key — carried so the dry run
 * can read ADAS as the person who asked for it. Never logged: {@link #toString()} names only the header.
 *
 * @param header the header it arrived in, {@code Authorization} or {@code Api-Key}
 * @param value  the header's value, exactly as sent
 */
public record InboundCredential(String header, String value) {

    /** The DIAL API-key header, as the runner accepts it inbound and ADAS accepts it in turn. */
    public static final String API_KEY_HEADER = "Api-Key";

    /**
     * The credential a request carries: its {@code Authorization} header, else its {@code Api-Key} header — the order
     * the runner's own authentication prefers them in — or null when it carries neither.
     */
    public static InboundCredential of(String authorization, String apiKey) {
        if (authorization != null && !authorization.isBlank()) {
            return new InboundCredential(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return new InboundCredential(API_KEY_HEADER, apiKey);
        }
        return null;
    }

    @Override
    public String toString() {
        return "InboundCredential[header=" + header + ", value=***]";
    }
}
