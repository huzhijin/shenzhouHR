package com.szsemicon.hr.evidenceingestion.domain.oa;

import java.util.Map;

/**
 * Compile-time mapping from OA overtime category display values
 * ({@code ctp_enum_item.showvalue}) to internal overtime-type codes.
 *
 * <p>Source: OA data dictionary confirmed 2026-08-08. The three values below
 * cover the complete set for the overtime form's category field
 * ({@code formson_0172.field0096}).</p>
 *
 * <p>Semantics per company policy:</p>
 * <ul>
 *   <li><b>加班费</b> — cash-compensated overtime; generates a payroll item.
 *       Hours count toward work time but not toward time-off balance.</li>
 *   <li><b>调休</b> — time-off-in-lieu; hours credited to the employee's
 *       TIME_OFF leave balance (debit on the accrual side).</li>
 *   <li><b>义务加班</b> — obligatory / voluntary overtime; hours are recorded
 *       for statistical purposes only. No payroll item and no leave credit.</li>
 * </ul>
 */
public final class OaOvertimeTypeCatalog {

    /** Compensated overtime — triggers a payroll item. */
    public static final String COMPENSATED_OVERTIME   = "COMPENSATED_OVERTIME";
    /** Time-off-in-lieu — credits the TIME_OFF leave balance. */
    public static final String TIME_OFF_IN_LIEU       = "TIME_OFF_IN_LIEU";
    /** Obligatory / unpaid overtime — statistical only. */
    public static final String OBLIGATORY_OVERTIME    = "OBLIGATORY_OVERTIME";

    private static final Map<String, String> BY_SHOW_VALUE = Map.of(
            "加班费",   COMPENSATED_OVERTIME,
            "调休",     TIME_OFF_IN_LIEU,
            "义务加班", OBLIGATORY_OVERTIME
    );

    private OaOvertimeTypeCatalog() {
    }

    /**
     * Resolves an OA {@code ctp_enum_item.showvalue} string to the
     * corresponding internal overtime-type code.
     *
     * @param showValue the Chinese display label from the OA overtime form;
     *                  stripped before lookup
     * @return the internal overtime code, or {@code null} when unrecognised
     */
    public static String resolveOvertimeCode(String showValue) {
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
     * Returns {@code true} if this overtime type should credit the employee's
     * TIME_OFF leave balance.
     */
    public static boolean creditsTimeOffBalance(String overtimeCode) {
        return TIME_OFF_IN_LIEU.equals(overtimeCode);
    }

    /**
     * Returns {@code true} if this overtime type generates a payroll
     * cash-compensation item.
     */
    public static boolean generatesPayrollItem(String overtimeCode) {
        return COMPENSATED_OVERTIME.equals(overtimeCode);
    }

    public static Map<String, String> allMappings() {
        return BY_SHOW_VALUE;
    }
}
