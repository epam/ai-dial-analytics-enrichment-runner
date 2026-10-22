package com.epam.aidial.enrichment.web.handler;

import com.epam.aidial.enrichment.exception.ErrorMessages;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * Stable JSON error contract shared by every endpoint:
 * {@code {"status": <int>, "error": "<machine_code>", "message": "<human readable>", ...}}.
 *
 * <p>{@code error} is a stable machine-readable code (e.g. {@code internal_error},
 * {@code postgres_unavailable}); {@code message} is human-readable. Stack traces and raw upstream
 * internals must never be exposed through this view. Matches the ADAS error contract.
 */
@Getter
public class ErrorView {

    private final Integer status;
    private final String error;
    private final String message;
    private final String path;
    private final String method;

    public ErrorView(HttpServletRequest request, HttpStatus status, String error, String message) {
        this.status = status.value();
        this.error = error;
        this.message = ErrorMessages.sanitize(message);
        this.path = resolvePath(request);
        this.method = request.getMethod();
    }

    /**
     * The request path, preferring the servlet path but falling back to the request URI.
     *
     * <p>The fallback matters for errors raised inside the security filter chain: those run before the
     * {@code DispatcherServlet}, which is what populates the servlet path, so a 401 or 403 would
     * otherwise report an empty path while a controller-raised error on the same URL reports the real
     * one. The contract should not depend on where in the chain the failure occurred.
     *
     * <p>The context path is stripped from the fallback, because the servlet path it stands in for does
     * not include it — otherwise the same URL would report two different paths depending on which one was
     * available, exactly the inconsistency this method exists to remove.
     */
    private static String resolvePath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isBlank()) {
            return servletPath;
        }
        String requestUri = Objects.requireNonNullElse(request.getRequestURI(), "");
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }
}
