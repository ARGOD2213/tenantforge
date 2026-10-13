package dev.mahindra.tenantforge.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/** Small JSON error bodies written directly from filters (before Spring MVC is involved). */
public final class Problems {

    private Problems() {
    }

    public static void write(HttpServletResponse response, int status, String error, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + escape(error) + "\",\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
