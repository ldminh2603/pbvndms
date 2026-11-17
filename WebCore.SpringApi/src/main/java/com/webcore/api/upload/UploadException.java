package com.webcore.api.upload;

import org.springframework.http.HttpStatus;

/** Contract error returned by the generic Uxxx upload API. */
public final class UploadException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public UploadException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
