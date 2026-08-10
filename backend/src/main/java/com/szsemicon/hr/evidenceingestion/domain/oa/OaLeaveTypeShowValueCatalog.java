package com.szsemicon.hr.evidenceingestion.domain.oa;

import java.util.Map;
import java.util.Objects;

/**
 * Compile-time mapping from OA {@code ctp_enum_item.showvalue} Chinese
 * display values to the internal {@code leave_type.leave_code} identifiers.
 *
 * <p>The OA system stores enum selections as the integer {@code itemvalue} but
 * exposes the human-readable {@code showvalue} in form payloads. This catalog
 * converts those display strings to the leave codes recognised by the HR
 * module so that the evidence-ingestion layer can attach the correct leave
 * type to an OA leave document without performing a runtime database lookup
 * against the OA schema.</p>
 *
 * <p>Source: OA data dictionary confirmed 2026-08-08. Values match
 * {@link com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog
 * FormKind.LEAVE} field {@code field0089} ("类别").</p>
 *
 * <p>TODO (needs user confirmation): 调休 can appear as both a leave type
 * ("TIME_OFF") and as an overtime category payout type ("TIME_OFF_IN_LIEU").
 * The mapping here covers the leave-form context only; the overtime form uses
 * {@link OaOvertimeTypeCatalog} for its category field.</p>
 */
public final class OaLeaveTypeShowValueCatalog {

    /**
     * Internal leave code constant — matches {@code leave_type.leave_code}.
     */
    public static final String ANNUAL_LEAVE      = "ANNUAL_LEAVE";
    public static final String SICK_LEAVE        = "SICK_LEAVE";
    public static final String PERSONAL_LEAVE    = "PERSONAL_LEAVE";
    public static final String TIME_OFF          = "TIME_OFF";
    public static final String MARRIAGE_LEAVE    = "MARRIAGE_LEAVE";
    public static final String MATERNITY_LEAVE   = "MATERNITY_LEAVE";
    public static final String PATERNITY_LEAVE   = "PATERNITY_LEAVE";
    public static final String BEREAVEMENT_LEAVE = "BEREAVEMENT_LEAVE";
    public static final String WORK_INJURY_LEAVE = "WORK_INJURY_LEAVE";
    public static final String NURSING_LEAVE     = "NURSING_LEAVE";
    /** Paid leave — counts as attendance per company policy. */
    public static final String BREASTFEEDING_TIME = "BREASTFEEDING_TIME";
    public static final String PRENATAL_EXAM_TIME = "PRENATAL_EXAM_TIME";

    /** Family planning leave (计划生育). */
    public static final String FAMILY_PLANNING_LEAVE = "FAMILY_PLANNING_LEAVE";
    /** Catch-all for OA leave types that do not map to a known code. */
    public static final String OTHER_LEAVE            = "OTHER_LEAVE";

    /**
     * Set of leave codes that count as paid attendance under the current
     * company policy (年假/调休/婚/产/陪产/丧/工伤). Used by the attendance
     * calculation engine when deciding whether a leave day contributes to the
     * confirmed-attendance numerator.
     *
     * <p>病假 and 事假 are explicitly absent -- they are unpaid and must not
     * count as attended.</p>
     */
    public static final java.util.Set<String> PAID_ATTENDANCE_LEAVE_CODES =
            java.util.Set.of(
                    ANNUAL_LEAVE,
                    TIME_OFF,
                    MARRIAGE_LEAVE,
                    MATERNITY_LEAVE,
                    PATERNITY_LEAVE,
                    BEREAVEMENT_LEAVE,
                    WORK_INJURY_LEAVE,
                    NURSING_LEAVE,
                    BREASTFEEDING_TIME,
                    PRENATAL_EXAM_TIME);

    /** Exact Chinese showvalue strings transcribed from OA ctp_enum_item. */
    private static final Map<String, String> BY_SHOW_VALUE = Map.ofEntries(
            Map.entry("年假",   ANNUAL_LEAVE),
            Map.entry("年休假", ANNUAL_LEAVE),          // alternate label
            Map.entry("病假",   SICK_LEAVE),
            Map.entry("事假",   PERSONAL_LEAVE),
            Map.entry("调休",   TIME_OFF),
            Map.entry("调休假", TIME_OFF),              // alternate label
            Map.entry("婚假",   MARRIAGE_LEAVE),
            Map.entry("结婚假", MARRIAGE_LEAVE),        // alternate label
            Map.entry("产假",   MATERNITY_LEAVE),
            Map.entry("陪产假", PATERNITY_LEAVE),
            Map.entry("丧假",   BEREAVEMENT_LEAVE),
            Map.entry("工伤",   WORK_INJURY_LEAVE),
            Map.entry("工伤假", WORK_INJURY_LEAVE),     // alternate label
            Map.entry("护理假", NURSING_LEAVE),
            Map.entry("哺乳时间", BREASTFEEDING_TIME),
            Map.entry("哺乳假", BREASTFEEDING_TIME),    // alternate label
            Map.entry("产检时间", PRENATAL_EXAM_TIME),
            Map.entry("孕检假", PRENATAL_EXAM_TIME),    // alternate label
            Map.entry("计生假", FAMILY_PLANNING_LEAVE),
            Map.entry("其他",   OTHER_LEAVE)
    );

    private OaLeaveTypeShowValueCatalog() {
    }

    /**
     * Resolves an OA {@code ctp_enum_item.showvalue} string to the
     * corresponding internal leave code.
     *
     * @param showValue the Chinese display label read from the OA form row;
     *                  leading and trailing whitespace is stripped before lookup
     * @return the internal {@code leave_type.leave_code}, or {@code null} if
     *         the value is blank or not in the catalog
     */
    public static String resolveLeaveCode(String showValue) {
        if (showValue == null) {
            return null;
        }
        String trimmed = showValue.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return BY_SHOW_VALUE.get(trimmed);
    }

    /**
     * Returns {@code true} when the given leave code counts as paid attendance
     * under the current company policy.
     *
     * @param leaveCode internal {@code leave_type.leave_code}
     */
    public static boolean isPaidAttendance(String leaveCode) {
        return leaveCode != null
                && PAID_ATTENDANCE_LEAVE_CODES.contains(leaveCode);
    }

    /**
     * Returns the full map of confirmed show-value → leave-code entries for
     * diagnostic and test use. The returned map is unmodifiable.
     */
    public static Map<String, String> allMappings() {
        return BY_SHOW_VALUE;
    }
}
