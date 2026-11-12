package com.epam.aidial.enrichment.dial;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The grammar of a DIAL application route, as ADAS enforces it for {@code transform.route}: one or more
 * {@code [A-Za-z0-9._~-]+} segments separated by single {@code /}, with no leading or trailing {@code /}, no
 * {@code .} or {@code ..} segment, no {@code ?}, {@code #} or {@code %}, and at most 256 characters. The runner checks
 * it again at admission because the route becomes part of a URL it calls with its own credentials, and a remote
 * invariant is not a safe guard for that.
 */
public final class RoutePath {

    public static final int MAX_LENGTH = 256;

    private static final Pattern GRAMMAR = Pattern.compile("[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*");

    private RoutePath() {
    }

    /** Why {@code route} is not a valid application route, or empty when it is. */
    public static Optional<String> problem(String route) {
        if (route == null || route.isBlank()) {
            return Optional.of("is absent");
        }
        if (route.length() > MAX_LENGTH) {
            return Optional.of("is longer than " + MAX_LENGTH + " characters");
        }
        if (!GRAMMAR.matcher(route).matches()) {
            return Optional.of("is not a relative path of '/'-separated segments of letters, digits and '._~-'");
        }
        for (String segment : route.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return Optional.of("contains a '.' or '..' segment");
            }
        }
        return Optional.empty();
    }
}
