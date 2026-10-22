package com.epam.aidial.enrichment.web.security.apikey;

import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Authentication produced by {@link ApiKeyAuthenticationFilter} for a validated DIAL {@code Api-Key}.
 * The principal is the project name (project-key callers) or the user id resolved from the configured
 * principal claim (JWT-rooted per-request keys). Credentials are never retained.
 *
 * <p>ADAS additionally retains the caller's project and raw user claims here for per-table grant
 * evaluation. The runner authorizes solely on {@code FULL_ADMIN}, so only the principal, the mapped
 * authorities, and the key shape (useful in logs) are kept.
 */
@Getter
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String principal;
    private final boolean projectKey;

    public ApiKeyAuthenticationToken(String principal,
                                     Collection<? extends GrantedAuthority> authorities,
                                     boolean projectKey) {
        super(authorities);
        this.principal = principal;
        this.projectKey = projectKey;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public @NonNull String getName() {
        return principal;
    }
}
