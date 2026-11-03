package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.exception.PipelineNotPausedException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lifting a breaker pause early is an operator decision over a machine one, so its line names who took it. */
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
    void earlyResumeNamesTheAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice@example.com", "n/a", "ROLE_FULL_ADMIN"));
        when(pauseRegistry.resume("r1")).thenReturn(true);

        controller.resume("r1");

        assertThat(actionLines()).containsExactly(
                "operator action: action=pipeline_resume pipeline=r1 principal=alice@example.com");
    }

    @Test
    void withoutPrincipalTheFieldIsDash() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        when(pauseRegistry.resume("r1")).thenReturn(true);

        controller.resume("r1");

        assertThat(actionLines()).containsExactly("operator action: action=pipeline_resume pipeline=r1 principal=-");
    }

    @Test
    void resumeOfPipelineTheBreakerHasNotPausedIsRefusedAndNotLogged() {
        when(pauseRegistry.resume("r1")).thenReturn(false);

        assertThatThrownBy(() -> controller.resume("r1")).isInstanceOf(PipelineNotPausedException.class);
        assertThat(actionLines()).isEmpty();
    }

    private List<String> actionLines() {
        return appender.messages().stream().filter(m -> m != null && m.startsWith("operator action:")).toList();
    }
}
