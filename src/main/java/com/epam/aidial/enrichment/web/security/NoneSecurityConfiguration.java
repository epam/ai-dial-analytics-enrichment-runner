package com.epam.aidial.enrichment.web.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security wiring for {@code none} mode (the default): every request is permitted and no
 * authentication is performed. This explicit permit-all chain overrides Spring Security's
 * locked-down default chain, keeping local development and the existing test suite open.
 *
 * <p>{@link SecurityPostureLogger} reports this state at WARN on startup, because a deployment that
 * meant to enable authentication and forgot would otherwise look identical to one that did.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnProperty(name = "security.mode", havingValue = SecurityProperties.MODE_NONE, matchIfMissing = true)
public class NoneSecurityConfiguration {

    @Bean
    public SecurityFilterChain permitAllSecurityFilterChain(HttpSecurity http) {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
