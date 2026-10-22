package com.epam.aidial.enrichment.exception;

import java.util.List;

/**
 * A request template naming a variable nothing bound for this unit of work. Raised <strong>before</strong>
 * the DIAL call, so the unit of work is dead-lettered instead of the model being asked a question containing
 * literal {@code {{placeholder}}} text — which would be charged, would pass a schema check as readily as any
 * other reply, and would be stored as a label.
 *
 * <p>This is not defence in depth for the contract-version check. That catches "wrong build"; this catches
 * "right build, wrong declaration", which is reachable without any skew: ADAS validates placeholders at arm,
 * at preview and on a write to an already-enabled pipeline, but deliberately not on a write that leaves the
 * pipeline disabled — so "stored" does not imply "renderable", only "enabled" does. A variable can also fail
 * to resolve for one particular row, which no static check can see.
 *
 * <p>It names the offending placeholders because they point at the cause: an unbound variable is a fault in
 * the declaration, not in the row, so the DLQ entry has to say which name the template asked for.
 */
public class UnresolvedPlaceholderException extends RuntimeException {

    public UnresolvedPlaceholderException(List<String> placeholders) {
        super("request template names " + placeholders.size() + " variable(s) nothing bound for this unit of "
                + "work: " + String.join(", ", placeholders));
    }
}
