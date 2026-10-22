package com.epam.aidial.enrichment.configuration;

import com.epam.aidial.enrichment.dial.DialCallTimingInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds the {@link RestClient} used for DIAL Core chat/completions calls: the DIAL base URL,
 * connect/read timeouts, and the {@code Api-Key} header. Response bodies are parsed by the caller
 * with the application Jackson 3 {@code JsonMapper}. Every call is timed and logged by
 * {@link DialCallTimingInterceptor}.
 */
@Configuration
public class DialClientConfiguration {

    public static final String DIAL_REST_CLIENT = "dialRestClient";
    public static final String API_KEY_HEADER = "Api-Key";

    @Bean(DIAL_REST_CLIENT)
    public RestClient dialRestClient(DialProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(Strings.CS.removeEnd(properties.baseUrl(), "/"))
                .requestFactory(factory)
                .requestInterceptor(new DialCallTimingInterceptor(properties));
        if (StringUtils.isNotBlank(properties.apiKey())) {
            builder = builder.defaultHeader(API_KEY_HEADER, properties.apiKey());
        }
        return builder.build();
    }
}
