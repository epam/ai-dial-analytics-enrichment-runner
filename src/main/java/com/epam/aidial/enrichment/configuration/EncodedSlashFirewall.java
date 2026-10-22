package com.epam.aidial.enrichment.configuration;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * A {@link StrictHttpFirewall} that admits a percent-encoded slash and, in exchange, refuses a request URI that has a
 * dot segment once percent-decoded.
 *
 * <p>The stock normalisation check cannot see {@code x%2F..%2F..%2Fcache}: the raw URI has no literal {@code /../},
 * and Tomcat has already normalised the decoded servlet path the firewall also checks. Decoding the URI here exposes
 * the {@code ..} segments the encoded slashes create. Every other check stays at its default. {@link UriUtils#decode}
 * leaves {@code +} alone, and a malformed escape is refused rather than guessed at.
 */
public class EncodedSlashFirewall extends StrictHttpFirewall {

    public EncodedSlashFirewall() {
        setAllowUrlEncodedSlash(true);
    }

    @Override
    public @NonNull FirewalledRequest getFirewalledRequest(@NonNull HttpServletRequest request) {
        FirewalledRequest firewalled = super.getFirewalledRequest(request);
        if (hasDotSegment(decode(request.getRequestURI()))) {
            throw new RequestRejectedException("The request was rejected because the decoded URL was not normalized.");
        }
        return firewalled;
    }

    private static String decode(String requestUri) {
        try {
            return UriUtils.decode(requestUri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new RequestRejectedException("The request was rejected because the URL could not be decoded.");
        }
    }

    /** A {@code .} or {@code ..} segment anywhere: {@code /./}, {@code /../}, or a trailing {@code /.} or {@code /..}. */
    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
