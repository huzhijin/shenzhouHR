package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.shared.web.ApiProblemException;
import com.szsemicon.hr.shared.validation.IdempotencyKeyPolicy;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

final class AttendanceSetupRules {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{1,64}$");

    private AttendanceSetupRules() {
    }

    static String code(String value) {
        String normalized = required(value, "编码", 64).toUpperCase();
        if (!CODE.matcher(normalized).matches()) {
            throw invalid("编码只能包含大写字母、数字、下划线和连字符");
        }
        return normalized;
    }

    static String name(String value) {
        return required(value, "名称", 100);
    }

    static String reason(String value) {
        String normalized = required(value, "变更原因", 500);
        if (normalized.length() < 2) {
            throw invalid("变更原因至少需要 2 个字符");
        }
        return normalized;
    }

    static String timeZone(String value) {
        String normalized = required(value, "IANA 时区", 64);
        try {
            return ZoneId.of(normalized).getId();
        } catch (ZoneRulesException exception) {
            throw invalid("IANA 时区无效");
        }
    }

    static void halfOpenPeriod(LocalDate from, LocalDate to) {
        if (from == null) {
            throw invalid("生效日必填");
        }
        if (to != null && !to.isAfter(from)) {
            throw invalid("失效日必须晚于生效日");
        }
    }

    static String idempotencyKey(String value) {
        if (!IdempotencyKeyPolicy.isValid(value)) {
            throw invalid("Idempotency-Key 格式无效");
        }
        return value;
    }

    static void page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("分页参数无效");
        }
    }

    static ApiProblemException conflict(String code, String message) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, message, true);
    }

    static ApiProblemException invalid(String message) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static String required(String value, String label, int maximum) {
        if (value == null || value.isBlank()) {
            throw invalid(label + "必填");
        }
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw invalid(label + "长度超限");
        }
        return normalized;
    }
}
