package com.szsemicon.hr.shared.web;

import org.springframework.http.HttpStatus;

public final class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    public ApiProblemException(HttpStatus status, String code, String message) {
        this(status, code, message, false);
    }

    public ApiProblemException(
            HttpStatus status,
            String code,
            String message,
            boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
