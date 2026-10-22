package com.epam.aidial.enrichment.adas;

/**
 * The version of the ADAS ↔ Enrichment Runner contract this build speaks, and the header that carries it.
 *
 * <p><b>Only equality is defined.</b> The number is opaque: it is not a semver, not a range, and carries no
 * ordering a reader may act on. An ADAS answering anything other than {@link #VERSION} is treated as
 * incompatible rather than read on a best-effort basis, because the two services deliberately delete a
 * vocabulary instead of deprecating one — there is no "compatible range" to express when the rule is that
 * both halves ship together. A comparison other than {@code ==} would be a design change, not a relaxation.
 *
 * <p><b>A property of the build, not configuration.</b> There is no environment variable for it, for the same
 * reason {@code view=compiled} is hard-coded in {@link AdasRegistryClient}: the only wrong setting disables
 * every pipeline at once, and silently. A skew is then a deploy fact, visible in the image tags, rather than
 * a value somebody can set to a number that happens to match.
 *
 * <p><b>It must match ADAS's constant exactly.</b> The far side writes the same number down in its own
 * {@code RunnerContract.VERSION}, and nothing at build time can compare one repo's constant with the
 * other's — the pair's equality is verified in the deploy window, by calling a data-plane endpoint with and
 * without the header while this service is still scaled to zero. Raising it here without raising it there
 * (or the reverse) is exactly the skew this constant exists to detect, so the bump ships in the same window
 * as the feed change that needs it.
 */
public final class AdasContract {

    /**
     * The contract version this build was written against. Sent on every ADAS request and required, byte for
     * byte, on every ADAS response.
     *
     * <p><b>2 since ADAS's evaluator fold.</b> That change reshaped the compiled projection — the inlined
     * {@code evaluator} object removed, the top-level {@code vars} removed, the authored {@code transform}
     * and a derived top-level {@code response_schema} added — which is exactly the condition this number
     * exists to be raised for, and raising it is the <em>whole</em> of that change's deploy-order guard on
     * this side. Because the echo is verified before a response body is interpreted, a build still speaking
     * 1 meeting the new feed classifies it incompatible and never reads it: it binds no variables, renders no
     * template, makes no model call. No query parameter, status code or body member was added for it.
     *
     * <p><b>3 since ADAS retired {@code on_ingest} and {@code advanced.scan_every}.</b> Every row-grain
     * pipeline now arrives as {@code schedule} with a cron, and no entry carries {@code scan_every}. A build
     * still speaking 2 would schedule such a feed correctly on its cron, but it would also keep a fixed-delay
     * branch that the feed no longer feeds and lane defaults that no longer apply. The bump keeps the pair
     * shipping together, as it did for the fold.
     *
     * <p><b>4 since ADAS aligned row-grain enrichments with their source's partitions.</b> For a row-trigger
     * pipeline whose target is aligned, the scan returns a {@code __partition_v} member in every row and the
     * write-back requires it back, unchanged, on every row. A build still speaking 3 drops the member, so every
     * write-back to such a target would be refused row by row and dead-lettered after its model call was paid
     * for; the bump turns that into one refusal of the whole build instead.
     */
    public static final int VERSION = 4;

    /** {@link #VERSION} on the wire, resolved once so the header value and the comparison cannot disagree. */
    public static final String VERSION_VALUE = Integer.toString(VERSION);

    /**
     * The header that carries it, on the request for every ADAS call and on the response for every one ADAS
     * serves under {@code /v1/pipelines}.
     *
     * <p>Its <b>absence</b> from a response is the signal that matters. An unknown request header is silently
     * ignored by any Spring service, so nothing the runner sends can prove the far side understood it; an
     * unknown response header is the one signal an ADAS older than the contract cannot forge.
     */
    public static final String HEADER = "Adas-Contract-Version";

    private AdasContract() {
    }
}
