package com.webcore.api.config;

import com.webcore.api.auth.RequireTokenAccessInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcSecurityConfiguration implements WebMvcConfigurer {
    private final RequireTokenAccessInterceptor requireTokenAccessInterceptor;
    public WebMvcSecurityConfiguration(RequireTokenAccessInterceptor requireTokenAccessInterceptor) {
        this.requireTokenAccessInterceptor = requireTokenAccessInterceptor;
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requireTokenAccessInterceptor);
    }
}
