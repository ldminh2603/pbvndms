package com.webcore.api.controller;

import com.webcore.api.auth.AuthRepository;
import com.webcore.api.auth.AuthService;
import com.webcore.api.auth.AuthSecurityAuditService;
import com.webcore.api.contract.CommonResponses;
import com.webcore.api.upload.UploadException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(AuthRepository.InvalidCredentialsException.class)
    ResponseEntity<CommonResponses.ErrorResponse> invalidCredentials(){return error(HttpStatus.UNAUTHORIZED,"AUTH_INVALID_CREDENTIALS","Email or password is invalid.");}
    @ExceptionHandler(AuthSecurityAuditService.AccountLockedException.class)
    ResponseEntity<CommonResponses.ErrorResponse> accountLocked(AuthSecurityAuditService.AccountLockedException error){return error(HttpStatus.LOCKED,"AUTH_ACCOUNT_LOCKED","This account is temporarily locked after too many failed sign-in attempts.");}
    @ExceptionHandler(AuthService.AuthConfigurationException.class)
    ResponseEntity<CommonResponses.ErrorResponse> authConfiguration(AuthService.AuthConfigurationException error){return error(HttpStatus.FORBIDDEN,error.code(),error.getMessage());}
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<CommonResponses.ErrorResponse> forbidden(SecurityException error){return error(HttpStatus.FORBIDDEN,error.getMessage(),"Module access is denied.");}
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<CommonResponses.ErrorResponse> accessDenied(AccessDeniedException error){return error(HttpStatus.FORBIDDEN,"ACCESS_DENIED",error.getMessage());}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<CommonResponses.ErrorResponse> invalidRequest(IllegalArgumentException error){return error(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",error.getMessage());}
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<CommonResponses.ErrorResponse> unavailableDependency(IllegalStateException error){return error(HttpStatus.FAILED_DEPENDENCY,"DEPENDENCY_NOT_READY",error.getMessage());}
    @ExceptionHandler(UploadException.class)
    ResponseEntity<CommonResponses.ErrorResponse> upload(UploadException error){return error(error.status(),error.code(),error.getMessage());}
    @ExceptionHandler(NoSuchBucketException.class)
    ResponseEntity<CommonResponses.ErrorResponse> missingUploadBucket(NoSuchBucketException error){return error(HttpStatus.FAILED_DEPENDENCY,"UPLOAD_STORAGE_NOT_READY","Upload storage bucket is not initialized.");}
    @ExceptionHandler(Exception.class)
    ResponseEntity<CommonResponses.ErrorResponse> unexpected(Exception error){
        log.error("Unhandled API error: {}", rootMessage(error), error);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","The request could not be completed.");
    }
    /** Loki stores the formatted message, not necessarily the Java stack trace. */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
    private ResponseEntity<CommonResponses.ErrorResponse> error(HttpStatus status,String code,String message){return ResponseEntity.status(status).body(new CommonResponses.ErrorResponse(code,message));}
}
