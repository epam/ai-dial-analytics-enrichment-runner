package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.security.apikey.ApiKeyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * States the runner's inbound authentication posture in the startup logs.
 *
 * <p>Logged at WARN when authentication is disabled, because {@code none} is the default: an instance
 * whose deployment forgot to set {@code SECURITY_MODE} is otherwise indistinguishable from a secured one,
 * and would look healthy. At WARN the open state is greppable in a log aggregator, which is how such a
 * misconfiguration is actually found.
 *
 * <p>Diagnostic value only. The rationale for the posture, the authorization rule, and the deliberate
 * deviations from ADAS live in the {@code api-authentication} spec and {@code docs/configuration.md} —
 * not here, because none of it can be acted on from a log line.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityPostureLogger {

    private final SecurityProperties securityProperties;
    private final IdentityProvidersProperties providersProperties;

    /**
     * Reports on the presence of the bean rather than re-reading {@code security.api-key.enabled}: the
     * property alone does not decide whether API-key authentication runs — the beans are additionally
     * conditional on the mode — so re-reading it could state that a credential works when nothing is wired
     * to accept it.
     */
    private final ObjectProvider<ApiKeyProperties> apiKeyProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void logPosture() {
        if (!SecurityProperties.MODE_OIDC.equals(securityProperties.mode())) {
            log.warn("Inbound authentication posture: DISABLED (security.mode={}). Every /v1 endpoint is open to "
                            + "any caller that can reach this port. Set SECURITY_MODE=oidc to enforce authentication.",
                    securityProperties.mode());
            return;
        }

        // Named by kind, because the two differ in where a token is verified: a JWT provider verifies
        // locally against its JWKS, while an opaque provider calls out to its userinfo endpoint on every
        // cache miss. Which of the two is configured is the first thing to know when introspection traffic
        // or its absence needs explaining.
        Set<String> jwtProviders = new TreeSet<>(IdentityProviderUtils.jwtProviders(providersProperties).keySet());
        Set<String> opaqueProviders = new TreeSet<>(IdentityProviderUtils.opaqueProviders(providersProperties).keySet());
        boolean apiKeyEnabled = apiKeyProperties.getIfAvailable() != null;
        log.info("Inbound authentication posture: ENFORCED (security.mode=oidc). Every /v1 endpoint requires the "
                        + "{} role. JWT identity providers: {}. Opaque identity providers: {}. "
                        + "DIAL Api-Key authentication: {}.",
                UserRole.FULL_ADMIN, jwtProviders, opaqueProviders, apiKeyEnabled ? "enabled" : "disabled");
    }
}
