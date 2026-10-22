package com.epam.aidial.enrichment.web.security;

/**
 * Application authorization roles. Provider-specific token roles are mapped onto these via the
 * configured roles mapping; the granted {@link org.springframework.security.core.GrantedAuthority}
 * names are exactly these enum names.
 *
 * <ul>
 *   <li>{@code FULL_ADMIN} — required by every functional endpoint under {@code /v1}.</li>
 *   <li>{@code READ_ONLY_ADMIN} — accepted as a mapping target but grants nothing on the runner.</li>
 * </ul>
 *
 * <p>Unlike ADAS the runner has no read-versus-write split: it holds no per-target grants, its reads are
 * operator information, and every write spends budget or changes scheduling. {@code READ_ONLY_ADMIN} is
 * kept only so an ADAS-shaped roles mapping — which commonly names it — binds instead of failing startup
 * on an unknown enum value.
 */
public enum UserRole {
    FULL_ADMIN,
    READ_ONLY_ADMIN,
}
