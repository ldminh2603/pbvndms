package com.webcore.api.config;

import java.util.Map;
import java.util.function.Supplier;

import com.webcore.api.auth.AllowAnonymous;
import com.webcore.api.core.CoreMetadataService;

import jakarta.annotation.Resource;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public class AllowAnonymousAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private final CoreMetadataService core;

    private final AntPathMatcher pathMatcher =
            new AntPathMatcher();

    @Resource(name = "requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    public AllowAnonymousAuthorizationManager(
            CoreMetadataService core
    ) {
        this.core = core;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authenticationSupplier,
            RequestAuthorizationContext context
    ) {

        String requestPath =
                context.getRequest().getRequestURI();

        String requestMethod =
                context.getRequest().getMethod();

        /*
         * 1. Nếu DB có rule matching:
         *    DB là nguồn quyết định cuối cùng.
         */
        Boolean configuredAllowAnonymous =
                findConfiguredAllowAnonymous(
                        requestMethod,
                        requestPath
                );

        if (configuredAllowAnonymous != null) {
            if (configuredAllowAnonymous) {
                return new AuthorizationDecision(true);
            }

            return authenticatedDecision(
                    authenticationSupplier
            );
        }

        /*
         * 2. DB không có config:
         *    fallback sang @AllowAnonymous.
         */
        if (hasAllowAnonymousAnnotation(context)) {
            return new AuthorizationDecision(true);
        }

        /*
         * 3. Không DB rule, không annotation:
         *    mặc định PRIVATE.
         */
        return authenticatedDecision(
                authenticationSupplier
        );
    }

    private AuthorizationDecision authenticatedDecision(
            Supplier<? extends Authentication> authenticationSupplier
    ) {

        Authentication authentication =
                authenticationSupplier.get();

        boolean authenticated =
                authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(
                        authentication.getPrincipal()
                );

        return new AuthorizationDecision(
                authenticated
        );
    }

    /**
     * Return:
     *
     * true  = DB có rule và cho anonymous
     * false = DB có rule và bắt authentication
     * null  = DB không có rule matching
     */
    private Boolean findConfiguredAllowAnonymous(
            String requestMethod,
            String requestPath
    ) {

        java.util.List<com.webcore.api.core.metadata.RuntimeMetadataRows.ApiSecurityRule> rules =
                core.apiSecurityRules();

        if (rules == null || rules.isEmpty()) {
            return null;
        }

        for (com.webcore.api.core.metadata.RuntimeMetadataRows.ApiSecurityRule rule : rules) {

            boolean active = Boolean.TRUE.equals(rule.active());

            if (!active) {
                continue;
            }

            String httpMethod = rule.httpMethod();

            String pathPattern = rule.pathPattern();

            if (
                    pathPattern == null
                    || pathPattern.isBlank()
            ) {
                continue;
            }

            if (
                    httpMethod != null
                    && !httpMethod.isBlank()
                    && !httpMethod.equalsIgnoreCase(
                            requestMethod
                    )
            ) {
                continue;
            }

            if (
                    !pathMatcher.match(
                            pathPattern,
                            requestPath
                    )
            ) {
                continue;
            }

            /*
             * Tìm thấy rule matching.
             * true/false đều có ý nghĩa.
             */
            return Boolean.TRUE.equals(rule.allowAnonymous());
        }

        // Không có config cho endpoint này.
        return null;
    }

    private boolean hasAllowAnonymousAnnotation(
            RequestAuthorizationContext context
    ) {

        try {
            HandlerExecutionChain handler =
                    handlerMapping.getHandler(
                            context.getRequest()
                    );

            if (handler == null) {
                return false;
            }

            if (!(handler.getHandler()
                    instanceof HandlerMethod handlerMethod)) {
                return false;
            }

            // Annotation trên method
            if (handlerMethod.hasMethodAnnotation(
                    AllowAnonymous.class
            )) {
                return true;
            }

            // Annotation trên controller
            return handlerMethod
                    .getBeanType()
                    .isAnnotationPresent(
                            AllowAnonymous.class
                    );

        } catch (Exception ex) {
            return false;
        }
    }

    private Object getValueIgnoreCase(
            Map<String, Object> row,
            String key
    ) {

        if (row == null || key == null) {
            return null;
        }

        if (row.containsKey(key)) {
            return row.get(key);
        }

        for (Map.Entry<String, Object> entry
                : row.entrySet()) {

            if (
                    entry.getKey() != null
                    && entry.getKey()
                    .equalsIgnoreCase(key)
            ) {
                return entry.getValue();
            }
        }

        return null;
    }

    private boolean toBoolean(Object value) {

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }

        if (value instanceof String stringValue) {

            String normalized =
                    stringValue.trim();

            return (
                    "true".equalsIgnoreCase(normalized)
                    || "1".equals(normalized)
                    || "y".equalsIgnoreCase(normalized)
                    || "yes".equalsIgnoreCase(normalized)
            );
        }

        return false;
    }

    private String toStringValue(Object value) {

        if (value == null) {
            return null;
        }

        String text =
                value.toString().trim();

        return text.isEmpty()
                ? null
                : text;
    }
}
