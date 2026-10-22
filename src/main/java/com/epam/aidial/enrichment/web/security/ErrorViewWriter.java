package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.handler.ErrorView;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Serializes an {@link ErrorView} to a servlet response body using the application's primary
 * {@link JsonMapper}, so security-filter error responses match the JSON shape produced elsewhere.
 */
@RequiredArgsConstructor
public class ErrorViewWriter {

    private final JsonMapper jsonMapper;

    public void write(HttpServletResponse response, ErrorView body) throws IOException {
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }
}
