package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.application.AttendanceIdempotencyReplayIndicator;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice(assignableTypes = {
        AttendanceGroupController.class,
        ShiftCalendarController.class,
        AttendancePolicyController.class,
        AttendancePolicyLifecycleController.class
})
public final class AttendanceIdempotencyResponseAdvice
        implements ResponseBodyAdvice<Object> {

    static final String HEADER = "Idempotency-Replayed";

    private final AttendanceIdempotencyReplayIndicator replayIndicator;

    public AttendanceIdempotencyResponseAdvice(
            AttendanceIdempotencyReplayIndicator replayIndicator) {
        this.replayIndicator = replayIndicator;
    }

    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return replayIndicator.metadata().isPresent();
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        replayIndicator.metadata().ifPresent(metadata -> {
            metadata.responseHeaders().forEach(
                    (name, value) -> response.getHeaders().set(name, value));
            response.getHeaders().set(
                    HEADER, Boolean.toString(metadata.replayed()));
        });
        return body;
    }
}
