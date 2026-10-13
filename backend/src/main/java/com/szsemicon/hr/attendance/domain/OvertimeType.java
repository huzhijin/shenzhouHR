package com.szsemicon.hr.attendance.domain;

/**
 * Enumeration of overtime compensation types recognized by the HR system.
 *
 * <p>This enum represents how overtime work will be compensated according to
 * the employee's choice declared in the OA overtime application form. It maps
 * to the {@code field0096} enum selection in the OA {@code formson_0172}
 * overtime form table.</p>
 *
 * <p>Business context (confirmed 2026-08-16):</p>
 * <ul>
 *   <li><b>PAID</b>: Overtime will be compensated as cash payment in the payroll,
 *       typically at 1.5x or 2x rate depending on day type (weekday/weekend/holiday).</li>
 *   <li><b>COMPENSATORY</b>: Overtime hours convert to compensatory time-off
 *       (调休) at 1:1 ratio, credited to the employee's time-off account for
 *       future leave.</li>
 *   <li><b>VOLUNTARY</b>: Employee chooses not to claim compensation for the
 *       overtime worked; hours are recorded for audit but do not generate payment
 *       or time-off credit.</li>
 * </ul>
 *
 * <p>OA field mapping (verified against production data 2026-08-16):</p>
 * <pre>
 * OA enum ID (field0096)        | Enum Value    | Display (showvalue)
 * -----------------------------|---------------|--------------------
 * -6539634143789166714         | PAID          | 现金
 * 5912806790045781226          | COMPENSATORY  | 调休
 * 4337518111002608138          | VOLUNTARY     | 义务加班
 * </pre>
 *
 * <p>Evidence volume (from OA production snapshot):</p>
 * <ul>
 *   <li>PAID: 112,022 records</li>
 *   <li>COMPENSATORY: 5,066 records</li>
 *   <li>VOLUNTARY: 89 records</li>
 * </ul>
 *
 * <p>NULL handling: If {@code field0096} is NULL in the OA form, the overtime
 * record is isolated and logged as a data quality issue; it does not contribute
 * to any overtime calculation until the source data is corrected.</p>
 */
public enum OvertimeType {
    /**
     * Paid overtime (现金) — compensated as cash in payroll at premium rate.
     * OA enum ID: -6539634143789166714
     */
    PAID,

    /**
     * Compensatory time-off (调休) — overtime hours credited to time-off account
     * at 1:1 ratio for future leave.
     * OA enum ID: 5912806790045781226
     */
    COMPENSATORY,

    /**
     * Voluntary overtime (义务加班) — no compensation claimed; recorded for
     * audit only.
     * OA enum ID: 4337518111002608138
     */
    VOLUNTARY;

    /**
     * Parses an OA {@code field0096} enum ID (stored as {@code Long}) into the
     * corresponding overtime type.
     *
     * <p>This mapping is based on the OA production {@code ctp_enum_item} table
     * verified on 2026-08-16. The enum IDs are stable identifiers tied to the
     * specific overtime form field definition.</p>
     *
     * @param oaEnumId the {@code itemvalue} from {@code field0096} in the OA
     *                 {@code formson_0172} table
     * @return the matching {@code OvertimeType}, or {@code null} if the ID is
     *         null or does not match any known type
     */
    public static OvertimeType fromOaEnumId(Long oaEnumId) {
        if (oaEnumId == null) {
            return null;
        }
        if (oaEnumId == -6539634143789166714L) {
            return PAID;
        }
        if (oaEnumId == 5912806790045781226L) {
            return COMPENSATORY;
        }
        if (oaEnumId == 4337518111002608138L) {
            return VOLUNTARY;
        }
        return null;
    }

    /**
     * Returns the OA enum ID corresponding to this overtime type.
     *
     * @return the {@code itemvalue} for {@code field0096} in the OA overtime form
     */
    public Long toOaEnumId() {
        return switch (this) {
            case PAID -> -6539634143789166714L;
            case COMPENSATORY -> 5912806790045781226L;
            case VOLUNTARY -> 4337518111002608138L;
        };
    }

    /**
     * Returns whether this overtime type generates payroll compensation.
     *
     * @return {@code true} for PAID, {@code false} for COMPENSATORY and VOLUNTARY
     */
    public boolean generatesCashPayment() {
        return this == PAID;
    }

    /**
     * Returns whether this overtime type credits hours to the compensatory
     * time-off account.
     *
     * @return {@code true} for COMPENSATORY, {@code false} for PAID and VOLUNTARY
     */
    public boolean creditsTimeOff() {
        return this == COMPENSATORY;
    }
}
