package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.web.mapper.PipelineCacheMapper;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** An operator pause and a breaker pause call for opposite responses, so the operator's line names who took it. */
class PipelineAdminControllerOperatorLogTest {

    private final PipelinePauseRegistry pauseRegistry = mock(PipelinePauseRegistry.class);
    private final PipelineAdminController controller = new PipelineAdminController(pauseRegistry,
            mock(PipelineCache.class), mock(PipelineCacheMapper.class));
    private CapturingAppender appender;

    @BeforeEach
    void attach() {
        appender = CapturingAppender.attachAtTrace();
    }

    @AfterEach
    void detach() {
        appender.detach(Level.INFO);
        SecurityContextHolder.clearContext();
    }

    @Test
    void pauseNamesTheAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice@example.com", "n/a", "ROLE_FULL_ADMIN"));

        controller.pause("r1");
        controller.resume("r1");

        verify(pauseRegistry).pause(eq("r1"), eq(PauseOrigin.OPERATOR), anyString());
        assertThat(actionLines()).containsExactly(
                "operator action: action=pipeline_pause pipeline=r1 principal=alice@example.com",
                "operator action: action=pipeline_resume pipeline=r1 principal=alice@example.com");
    }

    @Test
    void withoutPrincipalTheFieldIsDash() {
        controller.pause("r1");
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        controller.resume("r1");

        assertThat(actionLines()).containsExactly(
                "operator action: action=pipeline_pause pipeline=r1 principal=-",
                "operator action: action=pipeline_resume pipeline=r1 principal=-");
    }

    private List<String> actionLines() {
        return appender.messages().stream().filter(m -> m != null && m.startsWith("operator action:")).toList();
    }
}
