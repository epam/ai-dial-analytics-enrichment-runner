package com.epam.aidial.enrichment.observability.trace;

import com.epam.aidial.enrichment.support.EventCapture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogExecutionTest {

    private static final String BODY_MARKER = "BODY-MARKER-7f3a";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AnnotationAwareAspectJAutoProxyCreator.class, LogExecutionTest::aspectjAutoProxyCreator)
            .withUserConfiguration(LogExecutionConfiguration.class)
            .withBean(TracedExecutor.class)
            .withBean(MethodTraced.class);

    private EventCapture appender;

    @BeforeEach
    void attach() {
        appender = EventCapture.attach();
    }

    @AfterEach
    void detach() {
        appender.detach();
        setLevel(Level.INFO);
        MDC.clear();
    }

    @Test
    void atInfoNothingIsLoggedAndTheMethodRunsOnce() {
        setLevel(Level.INFO);
        runner.run(context -> {
            TracedExecutor executor = context.getBean(TracedExecutor.class);
            executor.run();
            assertThat(target(executor).invocations.get()).isEqualTo(1);
            assertThat(traceLines()).isEmpty();
        });
    }

    @Test
    void atTraceEnterAndExitCarryTheDurationAndTheMdc() {
        setLevel(Level.TRACE);
        runner.run(context -> {
            TracedExecutor executor = context.getBean(TracedExecutor.class);
            MDC.put("pipeline_name", "p1");
            executor.run();
            assertThat(target(executor).invocations.get()).isEqualTo(1);
            List<LogEvent> lines = traceLines();
            assertThat(lines).extracting(e -> e.getMessage().getFormattedMessage())
                    .hasSize(2)
                    .satisfies(m -> assertThat(m.getFirst()).isEqualTo("enter TracedExecutor.run()"))
                    .satisfies(m -> assertThat(m.get(1)).matches("exit TracedExecutor\\.run\\(\\) in \\d+ ms"));
            assertThat(lines).allSatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.TRACE);
                assertThat(e.getLoggerName()).isEqualTo(TracedExecutor.class.getName());
                assertThat(e.getContextData().<String>getValue("pipeline_name")).isEqualTo("p1");
            });
        });
    }

    @Test
    void failingCallIsTracedWithoutItsBodyAndTheOriginalExceptionPropagates() {
        setLevel(Level.TRACE);
        runner.run(context -> {
            TracedExecutor executor = context.getBean(TracedExecutor.class);
            assertThatThrownBy(executor::fail).isSameAs(target(executor).failure);
            List<LogEvent> lines = traceLines();
            assertThat(lines).hasSize(2);
            assertThat(lines.get(1).getMessage().getFormattedMessage())
                    .matches("exception TracedExecutor\\.fail\\(\\) in \\d+ ms: HTTP 422");
            assertThat(lines).allSatisfy(e -> {
                assertThat(e.getThrown()).isNull();
                assertThat(e.getMessage().getFormattedMessage()).doesNotContain(BODY_MARKER);
            });
        });
    }

    @Test
    @SuppressWarnings({"ResultOfMethodCallIgnored", "EqualsWithItself"}) // called only to prove they emit no trace line
    void objectMethodsAndNonPublicMethodsAreNotTraced() {
        setLevel(Level.TRACE);
        runner.run(context -> {
            TracedExecutor executor = context.getBean(TracedExecutor.class);
            assertThat(executor.toString()).isEqualTo("traced");
            executor.hashCode();
            executor.equals(executor);
            executor.getClass();
            executor.packagePrivate();
            assertThat(traceLines()).isEmpty();
        });
    }

    @Test
    void methodLevelAnnotationTracesOnlyThatMethod() {
        setLevel(Level.TRACE);
        runner.run(context -> {
            MethodTraced bean = context.getBean(MethodTraced.class);
            bean.plain();
            assertThat(traceLines()).isEmpty();
            bean.traced();
            assertThat(traceLines()).extracting(e -> e.getMessage().getFormattedMessage())
                    .first().isEqualTo("enter MethodTraced.traced()");
        });
    }

    @Test
    void annotatedBeanIsAnAopProxy() {
        runner.run(context -> {
            assertThat(AopUtils.isAopProxy(context.getBean(TracedExecutor.class))).isTrue();
            assertThat(AopUtils.isAopProxy(context.getBean(MethodTraced.class))).isTrue();
        });
    }

    @Test
    void theInfrastructureAdvisorAppliesUnderTheInfrastructureAutoProxyCreator() {
        new ApplicationContextRunner()
                .withBean(InfrastructureAdvisorAutoProxyCreator.class, () -> {
                    InfrastructureAdvisorAutoProxyCreator creator = new InfrastructureAdvisorAutoProxyCreator();
                    creator.setProxyTargetClass(true);
                    return creator;
                })
                .withUserConfiguration(LogExecutionConfiguration.class)
                .withBean(TracedExecutor.class)
                .run(context -> assertThat(AopUtils.isAopProxy(context.getBean(TracedExecutor.class))).isTrue());
    }

    @Test
    void theSwitchOffRemovesTheAdvisor() {
        runner.withPropertyValues("observability.execution-trace.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(Advisor.class);
            assertThat(AopUtils.isAopProxy(context.getBean(TracedExecutor.class))).isFalse();
        });
        runner.run(context -> assertThat(context).hasBean(LogExecutionConfiguration.ADVISOR_BEAN_NAME));
    }

    private static AnnotationAwareAspectJAutoProxyCreator aspectjAutoProxyCreator() {
        AnnotationAwareAspectJAutoProxyCreator creator = new AnnotationAwareAspectJAutoProxyCreator();
        creator.setProxyTargetClass(true);
        return creator;
    }

    /** The proxy's own fields are never initialised, so state is read from the target. */
    private static TracedExecutor target(TracedExecutor proxy) {
        return AopTestUtils.getTargetObject(proxy);
    }

    private List<LogEvent> traceLines() {
        return appender.events().stream()
                .filter(e -> e.getLoggerName().equals(TracedExecutor.class.getName()) || e.getLoggerName().equals(MethodTraced.class.getName()))
                .toList();
    }

    private static void setLevel(Level level) {
        Configurator.setLevel(TracedExecutor.class.getName(), level);
        Configurator.setLevel(MethodTraced.class.getName(), level);
    }

    @LogExecution
    public static class TracedExecutor {

        final AtomicInteger invocations = new AtomicInteger();

        final HttpClientErrorException failure = new HttpClientErrorException(HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable",
                ("{\"error\":\"PIPELINE_INVALID\",\"message\":\"" + BODY_MARKER + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        public void run() {
            invocations.incrementAndGet();
        }

        public void fail() {
            throw failure;
        }

        void packagePrivate() {
            // Not public: never traced.
        }

        @Override
        public String toString() {
            return "traced";
        }
    }

    public static class MethodTraced {

        public void plain() {
            // Not annotated: never traced.
        }

        @LogExecution
        public void traced() {
            // Annotated: traced at TRACE.
        }
    }
}
