package com.epam.aidial.enrichment.configuration;

import com.epam.aidial.enrichment.adas.AdasContract;
import com.epam.aidial.enrichment.adas.AdasContractInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds the {@link RestClient} used for all ADAS calls: the ADAS base URL, connect/read timeouts,
 * the {@code Api-Key} header and the contract handshake. Response bodies are parsed by the caller with the
 * application's Jackson 3 {@code JsonMapper} (case-insensitive enum reads, matching ADAS's lowercase enum
 * wire format), so the client itself needs no custom message converters.
 *
 * <p><b>One client, so one place for the handshake.</b> All five {@code adas/*Client} classes inject this
 * bean, which is why {@link AdasContractInterceptor} is registered here rather than in each of them: the
 * {@link AdasContract#HEADER} is then sent, and its echo verified, on every ADAS call the runner will ever
 * make — including from a client added after this was written.
 */
@Configuration
public class AdasClientConfiguration {

    public static final String ADAS_REST_CLIENT = "adasRestClient";
    public static final String API_KEY_HEADER = "Api-Key";

    @Bean(ADAS_REST_CLIENT)
    public RestClient adasRestClient(AdasProperties properties, AdasContractInterceptor contractInterceptor) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(Strings.CS.removeEnd(properties.baseUrl(), "/"))
                .requestFactory(factory)
                .requestInterceptor(contractInterceptor);
        if (StringUtils.isNotBlank(properties.apiKey())) {
            builder = builder.defaultHeader(API_KEY_HEADER, properties.apiKey());
        }
        return builder.build();
    }
}
