package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.BadgeCode;
import java.util.Locale;

/**
 * Daytime customer-report legend fills. Must stay aligned with
 * {@code attendanceLegend} / {@code REPORT_BADGE_COLORS} in the frontend.
 */
public final class CustomerReportLegendColors {

    public static final String CANVAS = "FFFFFF";
    public static final String HEADER = "DCEBF2";
    public static final String INK = "24344D";

    private CustomerReportLegendColors() {
    }

    public static String hexForTone(String tone) {
        if (tone == null || tone.isBlank()) {
            return null;
        }
        try {
            return hexForBadge(BadgeCode.valueOf(tone.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String hexForBadge(BadgeCode badge) {
        if (badge == null) {
            return null;
        }
        return switch (badge) {
            case LATE -> "FF8578";
            case EARLY_DEPARTURE -> "5AA3EA";
            case MISSING_PUNCH -> "BE6CBB";
            case RECOGNIZED_OVERTIME -> "3F8850";
            case TIME_OFF -> "F1B83D";
            case OUTING -> "43D4D0";
            case TRIP -> "02AA92";
            case PERSONAL_LEAVE -> "D8EF00";
            case SICK_LEAVE -> "9C2424";
            case ANNUAL_LEAVE -> "7A3434";
            case MARRIAGE_LEAVE -> "E07CC0";
            case MATERNITY_LEAVE -> "C45C9E";
            case PATERNITY_LEAVE -> "8E6CC9";
            case BEREAVEMENT_LEAVE -> "5C5C5C";
            case WORK_INJURY_LEAVE -> "E07A3D";
            case NURSING_LEAVE -> "3D6BB3";
            case BREASTFEEDING_LEAVE -> "F4A6C8";
            case PRENATAL_EXAM_LEAVE -> "7EC8E3";
            case FAMILY_PLANNING_LEAVE -> "6B8F3E";
            case REST_DAY -> "F2EFE7";
            case PUNCH_CORRECTION -> "FFFFFF";
            case ABSENCE, OTHER_LEAVE, LEAVE_REVOCATION, OVERTIME_APPLICATION,
                    EXEMPT_PUNCH, OTHER_ATTENDANCE_DOCUMENT, OTHER_EXCEPTION -> null;
        };
    }
}
