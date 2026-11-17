package com.webcore.api.controller;


import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import com.webcore.api.auth.AuthRepository;
import com.webcore.api.contract.CommonResponses;
import com.webcore.api.auth.AuthService;
import com.webcore.api.auth.AuthProperties;
import com.webcore.api.auth.AuthCookieProperties;
import com.webcore.api.auth.AuthSystemSettingsService;
import com.webcore.api.auth.PasswordResetService;
import com.webcore.api.userconfig.UserConfigRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthProperties authProperties;
    private final AuthCookieProperties cookieProperties;
    private final AuthSystemSettingsService authSystemSettings;
    private final PasswordResetService passwordReset;
    private final UserConfigRepository userConfigRepository;

    public AuthController(AuthService authService, AuthProperties authProperties, AuthCookieProperties cookieProperties,
                          AuthSystemSettingsService authSystemSettings, PasswordResetService passwordReset, UserConfigRepository userConfigRepository) {
        this.authService = authService;
        this.authProperties=authProperties;
        this.cookieProperties=cookieProperties;
        this.authSystemSettings=authSystemSettings;
        this.passwordReset=passwordReset;
        this.userConfigRepository=userConfigRepository;
    }

    @GetMapping("/me")
    public CommonResponses.AuthMeResponse me(@AuthenticationPrincipal Jwt jwt) {
        AuthService.CurrentUser user = authService.currentUser(jwt);
        String email = authService.tokenPayload(jwt).email();
        return CommonResponses.AuthMeResponse.from(user,
                userConfigRepository.loadOrDefaultLanguage(email, user.setting(), email).values(),
                authSystemSettings.load().lockScreenTimeoutSeconds());
    }

    @PostMapping("/login")
    public CommonResponses.LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response, HttpServletRequest httpRequest) {
        AuthService.LoginResult result;
        try {
            result = authService.login(request.email(), request.password(), httpRequest.getRemoteAddr());
        } catch (AuthRepository.InvalidCredentialsException exception) {
            if (authService.isDomainLoginEnabled()) {
                clearAuthenticationCookies(response);
                response.setHeader("X-WebCore-Session-Cleared", "true");
            }
            throw exception;
        }
        response.addHeader("Set-Cookie", createCookie("access_token", result.accessToken(), authProperties.accessTokenTtl()).toString());
        response.addHeader("Set-Cookie", createCookie("refresh_token", result.refreshToken(), authProperties.refreshTokenTtl()).toString());
        return new CommonResponses.LoginResponse(result.user());
    }

    @PostMapping("/refresh")
    public CommonResponses.MessageResponse refresh(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        String accessToken = authService.refresh(jwt);
        response.addHeader("Set-Cookie", createCookie("access_token", accessToken, authProperties.accessTokenTtl()).toString());
        return new CommonResponses.MessageResponse("Access token refreshed.");
    }

    @PostMapping("/logout")
    public CommonResponses.MessageResponse logout(HttpServletResponse response, @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        AuthService.TokenPayload actor = authService.tokenPayload(jwt);
        authService.logout(actor.email(), httpRequest.getRemoteAddr());
        clearAuthenticationCookies(response);
        return new CommonResponses.MessageResponse("Logout done");
    }

    @PostMapping("/change-password")
    public CommonResponses.MessageResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        AuthService.TokenPayload payload = authService.tokenPayload(jwt);
        if (!request.newPassword().equals(request.confirmPassword())) throw new IllegalArgumentException("Password confirmation does not match.");
        passwordReset.change(payload.email(), request.currentPassword(), request.newPassword());
        return new CommonResponses.MessageResponse("Password changed.");
    }

    @PostMapping("/forgot-password")
    public CommonResponses.MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        passwordReset.request(request.email(), httpRequest.getRemoteAddr());
        return new CommonResponses.MessageResponse("If the account exists, a password-reset message has been sent.");
    }

    @PostMapping("/reset-password")
    public CommonResponses.MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) throw new IllegalArgumentException("Password confirmation does not match.");
        passwordReset.reset(request.token(), request.newPassword());
        return new CommonResponses.MessageResponse("Password reset completed.");
    }

    private ResponseCookie createCookie(String name, String value, java.time.Duration maxAge) {
        return ResponseCookie.from(name, value)
                .path(cookieProperties.path())
                .httpOnly(true)
                .secure(cookieProperties.secure() || authProperties.reverseProxyEnabled())
                .sameSite(cookieProperties.sameSite())
                .maxAge(maxAge)
                .build();
    }

    private void clearAuthenticationCookies(HttpServletResponse response) {
        response.addHeader("Set-Cookie", createCookie("access_token", "", java.time.Duration.ZERO).toString());
        response.addHeader("Set-Cookie", createCookie("refresh_token", "", java.time.Duration.ZERO).toString());
    }

    // A domain login may be a UPN, short account name, or DOMAIN\\account.
    // AuthService accepts the latter two only when SYSTEM$DOMAINLOGIN is Y.
    public record LoginRequest(@NotBlank String email, @NotBlank String password) { }
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword, @NotBlank String confirmPassword) { }
    public record ForgotPasswordRequest(@NotBlank @Email String email) { }
    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String newPassword, @NotBlank String confirmPassword) { }
}
