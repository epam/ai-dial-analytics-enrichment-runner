package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.handler.ErrorView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Renders authentication failures (missing, malformed, or invalid credential) as the standard
 * {@link ErrorView} JSON with a {@code 401} status and the {@code unauthorized} code. Runs inside the
 * security filter chain, so it — not {@code DefaultExceptionHandler} — is what produces the body.
 *
 * <p>The message is fixed and deliberately uninformative: why a credential was rejected is a detail for
 * the runner's own logs, not for the caller, and the credential itself is never echoed.
 */
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ErrorViewWriter errorViewWriter;

    @Override
    public void commence(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull AuthenticationException ex)
            throws IOException {
        ErrorView body = new ErrorView(request, HttpStatus.UNAUTHORIZED, "unauthorized",
                "Authentication is required to access this resource");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        errorViewWriter.write(response, body);
    }
}
