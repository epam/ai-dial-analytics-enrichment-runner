package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.web.security.ErrorViewWriter;
import com.epam.aidial.enrichment.web.security.IntrospectionBulkhead;
import com.epam.aidial.enrichment.web.security.SecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A miss refused by the introspection bulkhead is answered like an unreachable Core: {@code 503
 * service_unavailable}, never a {@code 401} that would tell the caller its key is bad.
 */
class ApiKeyAuthenticationFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void saturatedBulkheadIsServiceUnavailable() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties(new JsonMapperConfiguration().jsonMapper(), "");
        properties.setCacheTtlSeconds(60);
        properties.setCacheMaxSize(100);
        CoreApiKeyIntrospector introspector = mock(CoreApiKeyIntrospector.class);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                introspector,
                new ApiKeyCache(properties, new IntrospectionBulkhead(0)),
                mock(ApiKeyAuthorityResolver.class),
                new ErrorViewWriter(new JsonMapperConfiguration().jsonMapper()),
                new SecurityProperties(SecurityProperties.MODE_OIDC, false, false, null));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/dlq");
        request.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "an-unseen-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("\"error\":\"service_unavailable\"")
                .contains("\"status\":503");
        assertThat(chain.getRequest()).as("the request must not continue down the chain").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(introspector);
    }
}
