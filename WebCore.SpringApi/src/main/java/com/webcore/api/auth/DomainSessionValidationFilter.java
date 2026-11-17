package com.webcore.api.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects a domain session immediately after AD reports that its password changed. */
@Component
public class DomainSessionValidationFilter extends OncePerRequestFilter {
    private final AuthService authService;
    private final AuthProperties authProperties;
    private final AuthCookieProperties cookieProperties;

    public DomainSessionValidationFilter(AuthService authService, AuthProperties authProperties, AuthCookieProperties cookieProperties) {
        this.authService = authService;
        this.authProperties = authProperties;
        this.cookieProperties = cookieProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            try {
                authService.assertDomainSessionCurrent(jwtAuthentication.getToken());
            } catch (AuthRepository.InvalidCredentialsException exception) {
                SecurityContextHolder.clearContext();
                clearAuthenticationCookies(response);
                response.setHeader("X-WebCore-Session-Cleared", "true");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Domain password was changed. Please sign in again.");
                return;
            } catch (AuthService.AuthConfigurationException exception) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, exception.getMessage());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void clearAuthenticationCookies(HttpServletResponse response) {
        response.addHeader("Set-Cookie", expiredCookie("access_token").toString());
        response.addHeader("Set-Cookie", expiredCookie("refresh_token").toString());
    }

    private ResponseCookie expiredCookie(String name) {
        return ResponseCookie.from(name, "")
                .path(cookieProperties.path())
                .httpOnly(true)
                .secure(cookieProperties.secure() || authProperties.reverseProxyEnabled())
                .sameSite(cookieProperties.sameSite())
                .maxAge(java.time.Duration.ZERO)
                .build();
    }
}
