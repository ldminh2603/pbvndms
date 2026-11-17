package com.webcore.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireTokenAccess {
    boolean allowAnyUserJwt() default false;
    String[] userRoles() default {};
    String[] clientScopes() default {};
}
