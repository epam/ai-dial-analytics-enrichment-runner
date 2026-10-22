package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.handler.ErrorView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Renders authorization failures (an authenticated caller lacking {@link UserRole#FULL_ADMIN}) as the
 * standard {@link ErrorView} JSON with a {@code 403} status and the {@code forbidden} code. Runs inside
 * the security filter chain, so it — not {@code DefaultExceptionHandler} — is what produces the body.
 */
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ErrorViewWriter errorViewWriter;

    @Override
    public void handle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull AccessDeniedException ex)
            throws IOException {
        ErrorView body = new ErrorView(request, HttpStatus.FORBIDDEN, "forbidden",
                "You do not have permission to perform this operation");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        errorViewWriter.write(response, body);
    }
}
