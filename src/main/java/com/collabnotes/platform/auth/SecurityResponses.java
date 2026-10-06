package com.collabnotes.platform.auth;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

public final class SecurityResponses {
    private SecurityResponses() { }

    public static void write(ObjectMapper mapper, HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), body);
    }

    public static void error(ObjectMapper mapper, HttpServletResponse response, int status,
                             String code, String message) throws IOException {
        write(mapper, response, status, Map.of("code", code, "message", message));
    }
}
