package com.epam.aidial.enrichment.worker;

import org.springframework.web.client.RestClientResponseException;

/**
 * Classifies an HTTP failure as worth retrying. Shared by every outbound call the workers make — DIAL
 * chat completions, ADAS group assembly, ADAS write-back — because the pipeline is the same in each case: a
 * 5xx, a 429, a 408 or a 425 is the server saying "later", anything else in the 4xx range is it saying
 * "never".
 *
 * <p>One implementation on purpose: a second copy would be free to disagree about 429, which is exactly
 * the classification whose cost (a dead-lettered item that should have been retried, or a retry storm
 * against a rejecting server) is highest.
 *
 * <p><strong>This was knowingly wrong for one case, and ADAS fixed it at the source.</strong> A capacity
 * refusal used to arrive as a 4xx for two unrelated reasons: ADAS's own read ceiling, and ClickHouse
 * running out of memory under unrelated load. The second is transient, so classifying it as "never" turned
 * ordinary node contention into dead letters at the rate ADAS produced it, none of them ADAS's own
 * ceiling.
 *
 * <p>ADAS now answers the second case with {@code 503 clickhouse_unavailable} and keeps the {@code 422}
 * for its own ceiling, which is deterministic and genuinely must not be retried. So the rule above is
 * correct as written, with <em>no</em> branching on ADAS's {@code error} machine code — which is the point:
 * that branching would have made a machine-code vocabulary load-bearing across a service boundary, and
 * this capability's spec forbids it for that reason. The right fix was the status, not a vocabulary.
 *
 * <p><b>One arm of that is still load-sensitive, so the rule is not perfect.</b> ADAS also answers a 4xx
 * when a read breaches its wall-clock ceiling, and a wall clock is not deterministic the way a memory
 * ceiling is: the identical read can exceed it purely because the node is contended and finish comfortably
 * once that clears. It is a far narrower hole than the one that was fixed — the ceiling is generous, so a
 * breach usually is the read's own cost — and closing it would mean ADAS distinguishing a self-inflicted
 * timeout from a contended one, which is an upstream change, not a branch to add here.
 *
 * <p>That residue is exactly why the mitigation below is <b>not</b> contingent on this rule being right: a
 * group dead-letter is archived with its payload whichever way this classifies, so a wrong ruling costs a
 * requeue rather than the work.
 */
public final class RetryableStatus {

    /** "Later" statuses outside the 5xx range: a rate limit, a timed-out request, and a too-early one. */
    private static final int REQUEST_TIMEOUT = 408;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int TOO_EARLY = 425;

    private RetryableStatus() {
    }

    public static boolean isRetryable(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        return e.getStatusCode().is5xxServerError()
                || status == TOO_MANY_REQUESTS || status == REQUEST_TIMEOUT || status == TOO_EARLY;
    }
}
