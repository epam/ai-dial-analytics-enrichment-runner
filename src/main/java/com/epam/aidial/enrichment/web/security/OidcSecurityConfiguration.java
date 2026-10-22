package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.security.IdentityProvidersProperties.ProviderConfig;
import com.epam.aidial.enrichment.web.security.apikey.ApiKeyAuthenticationFilter;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Security wiring for {@code oidc} mode: an OAuth2 resource server that authenticates Bearer tokens from
 * several configured providers and requires {@link UserRole#FULL_ADMIN} on every functional endpoint.
 * Both token shapes are supported, and the choice is made per request by an
 * {@link AuthenticationManagerResolver} reading the token itself: a token that parses as a JWS is verified
 * locally against the matching issuer's JWKS, and anything else is introspected against each opaque
 * provider's userinfo endpoint. Either way the per-provider converter then maps the caller's provider roles
 * onto application roles. The credential-free surface is {@link PublicPaths}; the API description joins it
 * only when {@link SecurityProperties#disableSwaggerAuthorization()} is set, and otherwise needs
 * {@code FULL_ADMIN} like the endpoints it describes.
 *
 * <p>Every {@code /v1} endpoint — reads included — requires {@code FULL_ADMIN}. The runner holds no
 * per-target grants, so there is no narrower caller to authorize; see the {@code api-authentication}
 * spec. Authorization is enforced here at the URL layer rather than by method security, because
 * db-scheduler executes scans, sweeps and worker items on background threads that carry no
 * {@link org.springframework.security.core.context.SecurityContext} — a {@code @PreAuthorize} on a shared
 * service method would pass for an HTTP caller and throw for scheduled work.
 *
 * <p>Authentication and authorization failures are rendered as the standard {@code ErrorView} JSON by
 * {@link RestAuthenticationEntryPoint} (401) and {@link RestAccessDeniedHandler} (403).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnProperty(name = "security.mode", havingValue = SecurityProperties.MODE_OIDC)
public class OidcSecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler,
                                                   ObjectProvider<ApiKeyAuthenticationFilter> apiKeyFilter,
                                                   SecurityProperties securityProperties) {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PublicPaths.patterns(securityProperties.disableSwaggerAuthorization())).permitAll()
                        .requestMatchers("/v1/**").hasAuthority(UserRole.FULL_ADMIN.name())
                        // Reached only when the rule above did not already permit them.
                        .requestMatchers(PublicPaths.API_DOCS_PATTERNS).hasAuthority(UserRole.FULL_ADMIN.name())
                        // Anything else is closed until someone decides otherwise.
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .authenticationManagerResolver(authenticationManagerResolver));
        // API-key auth (when enabled) runs before the Bearer filter so a JWT or opaque Authorization header
        // still takes precedence; the filter defers when that header is present.
        apiKeyFilter.ifAvailable(filter -> http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class));
        return http.build();
    }

    /**
     * Suppresses Spring Boot's auto-registration of {@link ApiKeyAuthenticationFilter} as a top-level
     * servlet filter: it is wired into the Spring Security chain via {@code addFilterBefore()} above,
     * so registering it again would run it twice, outside the chain.
     */
    @Bean
    @ConditionalOnBean(ApiKeyAuthenticationFilter.class)
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(ApiKeyAuthenticationFilter filter) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ErrorViewWriter errorViewWriter(JsonMapper jsonMapper) {
        return new ErrorViewWriter(jsonMapper);
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ErrorViewWriter errorViewWriter) {
        return new RestAuthenticationEntryPoint(errorViewWriter);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ErrorViewWriter errorViewWriter) {
        return new RestAccessDeniedHandler(errorViewWriter);
    }

    @Bean
    public IdentityProviderUtils identityProviderUtils(JsonMapper jsonMapper,
                                                       SecurityProperties securityProperties,
                                                       IdentityProvidersProperties providersProperties) {
        return new IdentityProviderUtils(jsonMapper, securityProperties, providersProperties);
    }

    @Bean
    public NimbusJwtDecoderResolver nimbusJwtDecoderResolver() {
        return config -> NimbusJwtDecoder.withJwkSetUri(config.jwkSetUri()).build();
    }

    @Bean
    public IssuerToDecoderMapFactory issuerToDecoderMapFactory(IdentityProviderUtils identityProviderUtils,
                                                               NimbusJwtDecoderResolver nimbusJwtDecoderResolver) {
        return new IssuerToDecoderMapFactory(identityProviderUtils, nimbusJwtDecoderResolver);
    }

    @Bean
    public JwtDecoder jwtDecoder(IssuerToDecoderMapFactory issuerToDecoderMapFactory,
                                 IdentityProvidersProperties properties) {
        Map<String, JwtDecoder> issuerToDecoder = new HashMap<>();
        for (ProviderConfig config : IdentityProviderUtils.jwtProviders(properties).values()) {
            issuerToDecoder.putAll(issuerToDecoderMapFactory.createIssuerToDecoderMap(config));
        }
        return new MultiIssuerJwtDecoder(issuerToDecoder);
    }

    @Bean
    public JwtAuthenticationConverterFactory jwtAuthenticationConverterFactory(IdentityProviderUtils identityProviderUtils,
                                                                              IdentityProvidersProperties properties) {
        return new JwtAuthenticationConverterFactory(
                IdentityProviderUtils.jwtProviders(properties), identityProviderUtils);
    }

    @Bean
    public JwtAuthenticationProvider jwtAuthenticationProvider(JwtDecoder jwtDecoder,
                                                               JwtAuthenticationConverterFactory converterFactory) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtDecoder);
        provider.setJwtAuthenticationConverter(jwt -> {
            String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
            JwtAuthenticationConverter converter = issuer != null ? converterFactory.getConverter(issuer) : null;
            if (converter == null) {
                throw new InvalidBearerTokenException("Unrecognized issuer: " + issuer);
            }
            return converter.convert(jwt);
        });
        return provider;
    }

    /**
     * One introspector per opaque provider, tried in turn, behind the runner's introspection cache. The
     * client carries explicit timeouts: ADAS uses a bare {@code RestClient.create()}, but a userinfo
     * endpoint that accepts the connection and never answers would otherwise hold a serving thread for as
     * long as the container allows — the same reason {@code CoreApiKeyIntrospector} sets them.
     */
    @Bean
    public OpaqueTokenIntrospector opaqueTokenIntrospector(IdentityProviderUtils identityProviderUtils,
                                                           IdentityProvidersProperties properties,
                                                           OpaqueTokenProperties opaqueTokenProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(opaqueTokenProperties.requestTimeoutMs());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder().requestFactory(factory).build();

        List<OpaqueTokenIntrospector> introspectors = new ArrayList<>();
        IdentityProviderUtils.opaqueProviders(properties).forEach((name, config) -> {
            List<String> roleClaims = identityProviderUtils.getRoleClaims(config).stream().map(String::trim).toList();
            MultiPathGrantedAuthoritiesConverter<ClaimAccessor> authoritiesConverter =
                    new MultiPathGrantedAuthoritiesConverter<>();
            authoritiesConverter.setAuthoritiesPaths(roleClaims);
            authoritiesConverter.setAuthorityPrefix("");
            introspectors.add(new DefaultOpaqueTokenIntrospector(
                    restClient, name, config.userInfoEndpoint(), authoritiesConverter,
                    identityProviderUtils.getPrincipalClaim(config), roleClaims,
                    identityProviderUtils.getEmailClaims(config)));
        });
        return new CachingOpaqueTokenIntrospector(
                new MultiIssuerOpaqueTokenIntrospector(introspectors), opaqueTokenProperties);
    }

    @Bean
    public OpaqueAuthenticationConverterFactory opaqueAuthenticationConverterFactory(IdentityProviderUtils identityProviderUtils,
                                                                                     IdentityProvidersProperties properties) {
        return new OpaqueAuthenticationConverterFactory(
                IdentityProviderUtils.opaqueProviders(properties), identityProviderUtils);
    }

    @Bean
    public OpaqueTokenAuthenticationProvider opaqueTokenAuthenticationProvider(OpaqueTokenIntrospector opaqueTokenIntrospector,
                                                                               OpaqueAuthenticationConverterFactory converterFactory) {
        OpaqueTokenAuthenticationProvider provider = new OpaqueTokenAuthenticationProvider(opaqueTokenIntrospector);
        provider.setAuthenticationConverter((token, principal) -> {
            String providerName = principal.getAttribute(DefaultOpaqueTokenIntrospector.IDP_CLAIM);
            OpaqueAuthenticationConverter converter =
                    providerName != null ? converterFactory.getConverter(providerName) : null;
            if (converter == null) {
                throw new InvalidBearerTokenException("No converter for opaque provider: " + providerName);
            }
            return converter.convert(token, principal);
        });
        return provider;
    }

    /**
     * Chooses the token shape per request from the credential itself, not from configuration: an opaque
     * token carries no issuer, so nothing about the caller can select the path in advance. A credential
     * that parses as a JWS goes to local verification; anything else is introspected.
     */
    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver(
            JwtAuthenticationProvider jwtAuthenticationProvider,
            OpaqueTokenAuthenticationProvider opaqueTokenAuthenticationProvider) {
        AuthenticationManager jwtManager = new ProviderManager(jwtAuthenticationProvider);
        AuthenticationManager opaqueManager = new ProviderManager(opaqueTokenAuthenticationProvider);
        BearerTokenResolver tokenResolver = new DefaultBearerTokenResolver();

        return request -> {
            String token = tokenResolver.resolve(request);
            if (token != null) {
                try {
                    SignedJWT.parse(token);
                } catch (ParseException e) {
                    return opaqueManager;
                }
            }
            return jwtManager;
        };
    }
}
