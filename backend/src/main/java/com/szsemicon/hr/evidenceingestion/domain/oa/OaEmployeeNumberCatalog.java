package com.szsemicon.hr.evidenceingestion.domain.oa;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * OA 工号别名和填错人的改挂。致远流程结束后不能改单，核算仍按正确人。
 */
public final class OaEmployeeNumberCatalog {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Map<String, String> EMPNO_ALIAS = Map.of(
            "SZT0687", "SZST0687",
            "SZT0709", "SZST0709");

    private OaEmployeeNumberCatalog() {
    }

    /**
     * OA/设备写成 {@code SZT0687} 时，按花名册 {@code SZST0687} 认人。
     * 仅在原工号查不到人时由 {@link com.szsemicon.hr.evidenceingestion.domain.EvidenceResolutionPolicy}
     * 调用。
     */
    public static String alias(String employeeNumber) {
        if (employeeNumber == null || employeeNumber.isBlank()) {
            return employeeNumber;
        }
        String mapped = EMPNO_ALIAS.get(employeeNumber);
        if (mapped != null) {
            return mapped;
        }
        if (employeeNumber.startsWith("SZT")
                && !employeeNumber.startsWith("SZST")
                && employeeNumber.length() > 3
                && employeeNumber.substring(3).chars().allMatch(Character::isDigit)) {
            return "SZST" + employeeNumber.substring(3);
        }
        return employeeNumber;
    }

    /**
     * 李谭 SZST0263 在 2026-08-17 18:30–20:00 的 1.5 小时是填错人。
     * 马锦涵自己已有同区间加班单，这条只作废，不再改挂，避免双计。
     */
    public static boolean suppressOvertime(
            String employeeNumber, Instant start, Instant end) {
        if (!"SZST0263".equals(employeeNumber) || start == null || end == null) {
            return false;
        }
        LocalDateTime startLocal = LocalDateTime.ofInstant(start, SHANGHAI);
        LocalDateTime endLocal = LocalDateTime.ofInstant(end, SHANGHAI);
        return startLocal.getYear() == 2026
                && startLocal.getMonthValue() == 8
                && startLocal.getDayOfMonth() == 17
                && startLocal.getHour() == 18
                && startLocal.getMinute() == 30
                && endLocal.getHour() == 20
                && endLocal.getMinute() == 0;
    }

    public static String relocateOvertimeEmployeeNumber(
            String employeeNumber, Instant start, Instant end) {
        if (suppressOvertime(employeeNumber, start, end)) {
            return employeeNumber;
        }
        return employeeNumber;
    }
}
