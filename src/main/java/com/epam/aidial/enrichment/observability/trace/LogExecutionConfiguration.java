package com.epam.aidial.enrichment.observability.trace;

import org.jspecify.annotations.NonNull;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcher;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Registers the {@link LogExecution} advisor, switchable with {@code observability.execution-trace.enabled}.
 *
 * <p>The pointcut is the class-level match (inherited annotations included) unioned with the method-level one,
 * restricted to public methods that are neither declared by {@link Object} nor override {@code equals},
 * {@code hashCode} or {@code toString}. The two-argument {@link AnnotationMatchingPointcut}
 * constructor would AND the class and method annotations, hence the explicit union. The advisor is an
 * infrastructure bean, so it applies under either auto-proxy creator and is never itself proxied.
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@ConditionalOnProperty(prefix = "observability.execution-trace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogExecutionConfiguration {

    public static final String ADVISOR_BEAN_NAME = "logExecutionAdvisor";

    @Bean(ADVISOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor logExecutionAdvisor() {
        return new DefaultPointcutAdvisor(logExecutionPointcut(), new LogExecutionInterceptor());
    }

    static Pointcut logExecutionPointcut() {
        return new ComposablePointcut(new AnnotationMatchingPointcut(LogExecution.class, true))
                .union(AnnotationMatchingPointcut.forMethodAnnotation(LogExecution.class))
                .intersection(new StaticMethodMatcher() {
                    @Override
                    public boolean matches(@NonNull Method method, @NonNull Class<?> targetClass) {
                        return Modifier.isPublic(method.getModifiers()) && !isObjectMethod(method);
                    }
                });
    }

    /** Declared by {@link Object}, or an override of {@code equals}, {@code hashCode} or {@code toString}. */
    private static boolean isObjectMethod(Method method) {
        return ReflectionUtils.isObjectMethod(method)
                || ReflectionUtils.isEqualsMethod(method)
                || ReflectionUtils.isHashCodeMethod(method)
                || ReflectionUtils.isToStringMethod(method);
    }
}
