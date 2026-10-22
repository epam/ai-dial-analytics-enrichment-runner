package com.epam.aidial.enrichment.observability.trace;

import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.observability.Elapsed;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;

/**
 * Traces {@link LogExecution} calls at TRACE through the target class's logger.
 *
 * <p>When the logger is above TRACE the call proceeds untouched. Otherwise an {@code enter} line precedes it and an
 * {@code exit} or {@code exception} line with the duration follows it. The exception line carries only
 * {@link ErrorMessages#classify(Throwable)} — no throwable argument, so neither a stack trace nor a response body
 * reaches the log — and the original exception is rethrown unchanged. Arguments and return values are never
 * rendered.
 */
public class LogExecutionInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object target = invocation.getThis();
        Class<?> targetClass = target != null ? AopUtils.getTargetClass(target) : invocation.getMethod().getDeclaringClass();
        Logger log = LoggerFactory.getLogger(targetClass);
        if (!log.isTraceEnabled()) {
            return invocation.proceed();
        }
        String name = targetClass.getSimpleName() + "." + invocation.getMethod().getName() + "()";
        log.trace("enter {}", name);
        long start = System.nanoTime();
        try {
            Object result = invocation.proceed();
            log.trace("exit {} in {} ms", name, Elapsed.millisSince(start));
            return result;
        } catch (Throwable failure) {
            // Classified text only, never the throwable: its stack and message can carry an upstream response body.
            log.trace("exception {} in {} ms: {}", name, Elapsed.millisSince(start), ErrorMessages.classify(failure));
            throw failure;
        }
    }
}
