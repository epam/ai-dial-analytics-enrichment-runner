package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.web.handler.ErrorView;
import com.epam.aidial.enrichment.web.security.ErrorViewWriter;
import com.epam.aidial.enrichment.web.security.PublicPaths;
import com.epam.aidial.enrichment.web.security.SecurityProperties;
import com.epam.aidial.enrichment.web.security.UserSecurityDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Authenticates a DIAL {@code Api-Key} presented in the {@code Api-Key} header by introspecting it
 * against DIAL Core (see {@link CoreApiKeyIntrospector}), caching the result, and populating the
 * {@link SecurityContext} with an {@link ApiKeyAuthenticationToken}. Layered before the Bearer filter
 * in {@code oidc} mode: if an {@code Authorization} header is present the JWT path wins and this filter
 * defers; a blank or absent {@code Api-Key} also passes through unauthenticated, to be resolved by the
 * URL rules.
 *
 * <p>A rejected/malformed key produces {@code 401 unauthorized}; an unreachable Core produces
 * {@code 503 service_unavailable}; both as the standard {@link ErrorView} JSON. A key that authenticates
 * but maps to no application role is still authenticated and is then denied {@code 403} by the URL rules,
 * which require {@code FULL_ADMIN}.
 */
@Slf4j
@Component
@ConditionalOnExpression(ApiKeyProperties.ACTIVE_CONDITION)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final CoreApiKeyIntrospector introspector;
    private final ApiKeyCache cache;
    private final ApiKeyAuthorityResolver authorityResolver;
    private final ErrorViewWriter errorViewWriter;
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();
    private final RequestMatcher publicPaths;

    public ApiKeyAuthenticationFilter(CoreApiKeyIntrospector introspector,
                                      ApiKeyCache cache,
                                      ApiKeyAuthorityResolver authorityResolver,
                                      ErrorViewWriter errorViewWriter,
                                      SecurityProperties securityProperties) {
        this.introspector = introspector;
        this.cache = cache;
        this.authorityResolver = authorityResolver;
        this.errorViewWriter = errorViewWriter;
        this.publicPaths = PublicPaths.matcher(securityProperties.disableSwaggerAuthorization());
    }

    /**
     * Leaves the credential-free operational surface alone. Those paths are permitted by the authorization
     * rules regardless, so introspecting a key there could only ever turn a probe or a metrics scrape into a
     * 401 — or a 503 while Core is unreachable — for a caller that happened to send a bad {@code Api-Key}.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return publicPaths.matches(request);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (StringUtils.isNotBlank(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(CoreApiKeyIntrospector.API_KEY_HEADER);
        if (StringUtils.isBlank(apiKey)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Authentication authentication = cache.getOrAuthenticate(apiKey, () -> authenticate(apiKey));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            chain.doFilter(request, response);
        } catch (BadCredentialsException ex) {
            SecurityContextHolder.clearContext();
            writeError(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid API key");
        } catch (AuthenticationServiceException ex) {
            SecurityContextHolder.clearContext();
            writeError(request, response, HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
                    "API key validation is temporarily unavailable");
        }
    }

    private Authentication authenticate(String apiKey) {
        IntrospectionResult result = introspector.introspect(apiKey);
        Collection<GrantedAuthority> appRoles = authorityResolver.resolve(result.rawRoles(), result.fromProjectKey());
        ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(
                result.principal(), List.copyOf(appRoles), result.fromProjectKey());
        token.setDetails(new UserSecurityDetails(result.email()));
        if (appRoles.isEmpty()) {
            log.debug("Api-key principal '{}' (fromProjectKey={}) maps to no application role; it will be denied "
                    + "by the URL rules", result.principal(), result.fromProjectKey());
        }
        return token;
    }

    private void writeError(HttpServletRequest request,
                            HttpServletResponse response,
                            HttpStatus status,
                            String error,
                            String message) throws IOException {
        ErrorView errorView = new ErrorView(request, status, error, message);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        errorViewWriter.write(response, errorView);
    }
}
