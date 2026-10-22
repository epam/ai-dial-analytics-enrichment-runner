package com.epam.aidial.enrichment.web.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.ClaimAccessor;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts provider role names from a claim set, reading each configured dotted claim path (e.g.
 * {@code roles} or {@code resource_access.roles}). A path may resolve to a single string or a list of
 * strings; both forms are collected. The raw role names are returned as authorities (with an optional
 * prefix) for later mapping onto {@link UserRole}s. Kept generic over {@link ClaimAccessor} — as in
 * ADAS — so the same extraction would serve opaque-token introspection attributes if that path is
 * added later.
 *
 * @param <T> the claim source (a {@code Jwt} for the JWT path)
 */
@Data
@Slf4j
public class MultiPathGrantedAuthoritiesConverter<T extends ClaimAccessor>
        implements Converter<T, Collection<GrantedAuthority>> {

    private static final String DEFAULT_AUTHORITY_PREFIX = "SCOPE_";
    private String authorityPrefix = DEFAULT_AUTHORITY_PREFIX;

    private List<String> authoritiesPaths;

    @Override
    public List<GrantedAuthority> convert(@NonNull T token) {
        var authorities = getAuthorities(token);
        return authorities.stream()
                .map(authority -> (GrantedAuthority) new SimpleGrantedAuthority(authorityPrefix + authority))
                .toList();
    }

    private Set<String> getAuthorities(T token) {
        Set<String> authorities = new HashSet<>();
        final Map<String, Object> claims = token.getClaims();
        authoritiesPaths.stream()
                .map(path -> extractPath(path, claims))
                .filter(Objects::nonNull)
                .forEach(c -> extractRoles(c, authorities));
        return authorities;
    }

    /**
     * Logged at {@code debug} rather than ADAS's {@code warn}: a token that carries only some of the
     * configured paths is the normal case, not a fault. With the documented multi-path configuration
     * ({@code role-claims=roles,resource_access.roles}) a perfectly good Azure or Keycloak token misses one
     * of them, so warning here emits a line per configured path per request — log volume driven by whatever
     * an unauthenticated caller chooses to send.
     */
    private Object extractPath(String path, Map<String, Object> claims) {
        Object current = claims;
        for (String partPath : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                log.debug("Authority path '{}' failed at '{}'", path, partPath);
                return null;
            }
            current = map.get(partPath);
        }

        if (current == null) {
            log.debug("Authority path '{}' extract as null", path);
        }
        return current;
    }

    private void extractRoles(Object value, Set<String> authorities) {
        if (value instanceof String role) {
            authorities.add(role);
        } else if (value instanceof Collection<?> listRoles) {
            for (Object role : listRoles) {
                if (role instanceof String roleString) {
                    authorities.add(roleString);
                } else {
                    log.warn("Unsupported authority value type: {}", role.getClass());
                }
            }
        }
    }
}
