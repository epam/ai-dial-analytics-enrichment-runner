package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.web.security.UserRolesResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Maps the raw role names Core returns for an API key onto application authorities, choosing the
 * mapping by key shape: project-key responses ({@code project} present) use
 * {@code security.api-key.roles-mapping}; JWT-rooted per-request keys ({@code userClaims} present)
 * use the OIDC {@code security.defaults.roles-mapping}, because those role names are the same ones
 * the user would carry when calling the runner directly with a JWT.
 */
@Slf4j
@Component
@ConditionalOnExpression(ApiKeyProperties.ACTIVE_CONDITION)
public class ApiKeyAuthorityResolver {

    private final UserRolesResolver projectKeyResolver;
    private final UserRolesResolver userClaimsResolver;

    public ApiKeyAuthorityResolver(ApiKeyProperties properties) {
        this.projectKeyResolver = new UserRolesResolver(properties.getParsedRolesMapping());
        this.userClaimsResolver = new UserRolesResolver(properties.getParsedDefaultRolesMapping());
    }

    public Collection<GrantedAuthority> resolve(List<String> rawRoles, boolean fromProjectKey) {
        UserRolesResolver delegate = fromProjectKey ? projectKeyResolver : userClaimsResolver;
        List<GrantedAuthority> granted = rawRoles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                .toList();
        return delegate.resolve(granted);
    }
}
