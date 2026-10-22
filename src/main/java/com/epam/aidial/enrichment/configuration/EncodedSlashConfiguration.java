package com.epam.aidial.enrichment.configuration;

import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * Lets a path segment carry a percent-encoded slash, so a group key containing {@code /} — an arbitrary
 * {@code group_by} value — can be addressed as {@code …/groups/team%2Fa/requeue}.
 *
 * <p>Two layers refuse {@code %2F} by default, each with a {@code 400}: Tomcat's connector and Spring Security's
 * {@link StrictHttpFirewall}. Tomcat is set to decode it, which only affects the servlet path. Route matching
 * ({@code PathPatternParser}) and the authorization rules ({@code PathPatternRequestMatcher}) both parse the raw
 * request URI, where {@code %2F} is still inside one segment, so an encoded slash binds as part of {@code {groupKey}}
 * and cannot change which route or rule a request matches.
 *
 * <p>The firewall is an {@link EncodedSlashFirewall}: the stock firewall with encoded slashes allowed and nothing else
 * loosened, plus a dot-segment check on the percent-decoded request URI. That check is what refuses an encoded slash
 * that would climb out of the segment ({@code x%2F..%2Fcache}): the stock check never sees it, because the raw URI has
 * no literal {@code /../} and Tomcat has already normalised the decoded servlet path. Double encoding
 * ({@code %252F}) is refused by the stock encoded-percent check. Both refusals are the firewall's ordinary {@code 400}.
 * Pass-through was rejected: it leaves a literal {@code %} in the servlet path, which the firewall refuses unless
 * encoded percents are allowed too, and allowing them would reopen double encoding.
 *
 * <p>Fixed in code rather than configurable: no supported deployment wants a listed group to be unaddressable.
 */
@Configuration(proxyBeanMethods = false)
public class EncodedSlashConfiguration {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> encodedSlashDecode() {
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setEncodedSolidusHandling(EncodedSolidusHandling.DECODE.getValue()));
    }

    /** Picked up by {@code WebSecurity} in every security mode, since each one builds a filter chain. */
    @Bean
    public HttpFirewall httpFirewall() {
        return new EncodedSlashFirewall();
    }
}
