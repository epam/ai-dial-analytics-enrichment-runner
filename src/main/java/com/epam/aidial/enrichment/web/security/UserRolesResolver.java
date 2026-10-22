package com.epam.aidial.enrichment.web.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the raw provider-role authorities extracted from a token onto the application's
 * {@link UserRole} authorities using a configured mapping. Roles with no mapping entry are dropped;
 * a token that yields no application role ends up with no authorities and is denied access to
 * protected endpoints by the URL rules.
 */
@Slf4j
@RequiredArgsConstructor
public class UserRolesResolver {

    private final Map<String, Set<UserRole>> rolesMapping;

    public Collection<GrantedAuthority> resolve(Collection<? extends GrantedAuthority> providerAuthorities) {
        Set<UserRole> userRoles = new HashSet<>();
        for (GrantedAuthority authority : providerAuthorities) {
            Set<UserRole> mapped = rolesMapping.get(authority.getAuthority());
            if (mapped != null) {
                userRoles.addAll(mapped);
            }
        }
        List<GrantedAuthority> result = userRoles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.name()))
                .toList();
        if (result.isEmpty()) {
            // Debug, and without the mapping itself, where ADAS warns with it: this fires on every request
            // from a caller that maps to nothing, so warning lets a rejected caller drive log volume and
            // writes the security configuration into the log aggregator on each attempt. The authorities
            // that failed to map are the part an operator needs; the mapping is in the configuration.
            log.debug("No application role mapped from provider authorities {}", providerAuthorities);
        }
        return result;
    }
}
