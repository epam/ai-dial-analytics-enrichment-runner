package com.epam.aidial.enrichment.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one INFO line every operator action logs: {@code operator action: action=… pipeline=… <target> principal=…}.
 * It names the verb, the pipeline, the affected run or item where there is one, and who took it — never a request
 * body. One logger and one prefix, so "who changed what" is a single query. An operator decision and a machine
 * decision call for opposite responses, and a log-only reader can tell them apart only if the line says who took it.
 *
 * <p>The principal is the authenticated one's name — the configured principal claim for a token, the project or user
 * for an API key. Under {@code SECURITY_MODE=none} there is none (at most Spring's anonymous token), and the field
 * reads {@value #NONE} rather than being omitted, so a query over it never silently misses a line. It is read when the
 * action is recorded, on the request thread that holds the security context.
 *
 * <p>Inside a transaction the line is written once it commits, so a verb that rolls back leaves no line claiming it
 * happened.
 */
@Slf4j
public final class OperatorActionLog {

    /** A field's value when there is nothing to name: no principal, or no pipeline. */
    public static final String NONE = "-";

    private OperatorActionLog() {
    }

    /**
     * Logs one operator action.
     *
     * @param action   the verb, e.g. {@code pipeline_pause}
     * @param pipeline the pipeline acted on, or {@code null} when the action names none
     * @param target   the affected run or item as {@code key=value} pairs, or {@code null} when there is none
     */
    public static void record(String action, String pipeline, String target) {
        String principal = currentPrincipal();
        Runnable line = () -> log.info("operator action: action={} pipeline={}{} principal={}", action,
                pipeline == null ? NONE : pipeline, target == null ? "" : " " + target, principal);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            line.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                line.run();
            }
        });
    }

    private static String currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return NONE;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? NONE : name;
    }
}
