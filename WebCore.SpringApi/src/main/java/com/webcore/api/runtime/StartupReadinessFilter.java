package com.webcore.api.runtime;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps business endpoints unavailable until the immutable Core snapshot has been warmed. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupReadinessFilter extends OncePerRequestFilter {
    private final StartupGate startupGate;

    public StartupReadinessFilter(StartupGate startupGate) {
        this.startupGate = startupGate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "/health/live".equals(path)
                || "/health/ready".equals(path)
                || "/actuator/health/liveness".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (startupGate.state().ready()) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", "1");
        response.getWriter().write("{\"code\":\"APPLICATION_STARTING\",\"message\":\"The application is warming its runtime metadata.\"}");
    }
}
