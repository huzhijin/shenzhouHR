package com.szsemicon.hr.shared.web;

public record ApiErrorResponse(
        String code,
        String message,
        String correlationId,
        boolean retryable) {
}

