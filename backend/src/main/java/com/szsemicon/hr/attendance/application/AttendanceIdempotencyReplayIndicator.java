package com.szsemicon.hr.attendance.application;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Carries trusted idempotency execution metadata from the transactional
 * application service to the HTTP response without changing strict business
 * DTOs.
 */
@Component
public final class AttendanceIdempotencyReplayIndicator {

    private static final String ATTRIBUTE =
            AttendanceIdempotencyReplayIndicator.class.getName() + ".metadata";

    public void mark(boolean replayed) {
        mark(replayed, Map.of());
    }

    public void mark(boolean replayed, Map<String, String> responseHeaders) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(
                    ATTRIBUTE,
                    new Metadata(replayed, responseHeaders),
                    RequestAttributes.SCOPE_REQUEST);
        }
    }

    public Optional<Boolean> current() {
        return metadata().map(Metadata::replayed);
    }

    public Optional<Metadata> metadata() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((Metadata) attributes.getAttribute(
                ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST));
    }

    public record Metadata(boolean replayed, Map<String, String> responseHeaders) {

        public Metadata {
            responseHeaders = Map.copyOf(responseHeaders);
        }
    }
}
