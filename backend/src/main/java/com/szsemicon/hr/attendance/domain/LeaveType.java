package com.szsemicon.hr.attendance.domain;

/**
 * Enumeration of leave types recognized by the HR attendance system.
 *
 * <p>This enum represents the canonical leave categories used throughout the
 * attendance calculation and reporting modules. It maps to the string-based
 * {@code leave_type.leave_code} values in the database and serves as a
 * type-safe alternative to raw string constants.</p>
 *
 * <p>The enum values correspond to the leave codes defined in
 * {@link com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveTypeShowValueCatalog}
 * and are used by:</p>
 * <ul>
 *   <li>Daily attendance calculation to determine attendance status</li>
 *   <li>Report generation to break down leave usage by type</li>
 *   <li>Time account management for annual leave and compensatory time-off</li>
 * </ul>
 *
 * <p>Specific business rules:</p>
 * <ul>
 *   <li><b>Paid attendance leaves</b>: ANNUAL, COMPENSATORY, MARRIAGE, MATERNITY,
 *       PATERNITY, BEREAVEMENT, WORK_INJURY — these count toward actual
 *       attendance days and do not reduce the attendance rate.</li>
 *   <li><b>SICK</b>: As of 2026-08-16 business decision, sick leave counts as
 *       actual attendance (does not reduce rate) but is tracked separately for
 *       reporting purposes.</li>
 *   <li><b>PERSONAL</b>: Unpaid leave; does not count as attendance.</li>
 *   <li><b>PRENATAL_NURSING</b>: Paid time for prenatal exams and nursing;
 *       counts as attendance.</li>
 * </ul>
 */
public enum LeaveType {
    /**
     * Annual leave (年假) — paid leave accrued based on tenure and used at
     * employee discretion. Counts as attendance.
     */
    ANNUAL,

    /**
     * Sick leave (病假) — taken for illness or medical appointments. As of
     * 2026-08-16, counts as actual attendance per company policy but is
     * reported separately.
     */
    SICK,

    /**
     * Marriage leave (婚假) — paid leave for marriage. Counts as attendance.
     */
    MARRIAGE,

    /**
     * Maternity leave (产假) — statutory paid leave for childbirth. Counts
     * as attendance.
     */
    MATERNITY,

    /**
     * Paternity leave (陪产假) — paid leave for partners during childbirth.
     * Counts as attendance.
     */
    PATERNITY,

    /**
     * Bereavement leave (丧假) — paid leave for immediate family death.
     * Counts as attendance.
     */
    BEREAVEMENT,

    /**
     * Work injury leave (工伤假) — leave due to work-related injury. Counts
     * as attendance.
     */
    WORK_INJURY,

    /**
     * Prenatal exam and nursing time (产检/哺乳时间) — paid time for prenatal
     * checkups and breastfeeding. Counts as attendance.
     */
    PRENATAL_NURSING,

    /**
     * Breastfeeding time (哺乳假) — one paid hour each workday. Counts as
     * attendance. Weekend hours are excluded.
     */
    BREASTFEEDING,

    /**
     * Personal leave (事假) — unpaid leave for personal reasons. Does NOT
     * count as attendance.
     */
    PERSONAL,

    /**
     * Compensatory time-off (调休) — time off in lieu of overtime worked.
     * Counts as attendance.
     */
    COMPENSATORY,

    /**
     * Family-planning leave (计生假). Counts as attendance and includes weekends.
     */
    FAMILY_PLANNING,

    /**
     * Catch-all OA leave (其他). Counts as attendance and includes weekends.
     */
    OTHER;

    /**
     * Returns the database leave code string corresponding to this enum value.
     * This matches the values in {@code leave_type.leave_code} and the constants
     * in {@link com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveTypeShowValueCatalog}.
     *
     * @return the canonical leave code string
     */
    public String toLeaveCode() {
        return switch (this) {
            case ANNUAL -> "ANNUAL_LEAVE";
            case SICK -> "SICK_LEAVE";
            case MARRIAGE -> "MARRIAGE_LEAVE";
            case MATERNITY -> "MATERNITY_LEAVE";
            case PATERNITY -> "PATERNITY_LEAVE";
            case BEREAVEMENT -> "BEREAVEMENT_LEAVE";
            case WORK_INJURY -> "WORK_INJURY_LEAVE";
            case PRENATAL_NURSING -> "PRENATAL_EXAM_TIME";
            case BREASTFEEDING -> "BREASTFEEDING_TIME";
            case PERSONAL -> "PERSONAL_LEAVE";
            case COMPENSATORY -> "TIME_OFF";
            case FAMILY_PLANNING -> "FAMILY_PLANNING_LEAVE";
            case OTHER -> "OTHER_LEAVE";
        };
    }

    /**
     * Parses a database leave code string into the corresponding enum value.
     *
     * @param leaveCode the {@code leave_type.leave_code} string from the database
     * @return the matching {@code LeaveType}, or {@code null} if the code is
     *         null, blank, or does not match any known type
     */
    public static LeaveType fromLeaveCode(String leaveCode) {
        if (leaveCode == null || leaveCode.isBlank()) {
            return null;
        }
        return switch (leaveCode.strip()) {
            case "ANNUAL_LEAVE" -> ANNUAL;
            case "SICK_LEAVE" -> SICK;
            case "MARRIAGE_LEAVE" -> MARRIAGE;
            case "MATERNITY_LEAVE" -> MATERNITY;
            case "PATERNITY_LEAVE" -> PATERNITY;
            case "BEREAVEMENT_LEAVE" -> BEREAVEMENT;
            case "WORK_INJURY_LEAVE" -> WORK_INJURY;
            case "PRENATAL_EXAM_TIME", "NURSING_LEAVE" -> PRENATAL_NURSING;
            case "BREASTFEEDING_TIME" -> BREASTFEEDING;
            case "PERSONAL_LEAVE" -> PERSONAL;
            case "TIME_OFF" -> COMPENSATORY;
            case "FAMILY_PLANNING_LEAVE" -> FAMILY_PLANNING;
            case "OTHER_LEAVE" -> OTHER;
            default -> null;
        };
    }

    /**
     * Returns whether this leave type counts as actual attendance under the
     * current company policy.
     *
     * <p>As of 2026-08-16 business decision:</p>
     * <ul>
     *   <li>All leave types EXCEPT {@code PERSONAL} count as attendance</li>
     *   <li>{@code SICK} now counts as attendance (policy change)</li>
     * </ul>
     *
     * @return {@code true} if this leave type contributes to actual attendance days
     */
    public boolean countsAsAttendance() {
        return this != PERSONAL;
    }

    /**
     * Weekend and public-holiday hours are included for calendar leave types
     * (产假, 陪产假, 丧假, 病假, 计生假, and others) and excluded for 年休假,
     * 调休假, 事假, 婚假, and 哺乳假.
     */
    public boolean includesWeekendHours() {
        return this != ANNUAL
                && this != COMPENSATORY
                && this != PERSONAL
                && this != BREASTFEEDING
                && this != MARRIAGE;
    }
}
