package com.szsemicon.hr.reporting.application;

/**
 * Callback fired after an {@code attendance_report_auto_recalc_slot} finishes
 * rebuilding all company reports. Implementors can piggyback on the same
 * cadence to keep downstream aggregates (e.g. leave-account balances) in sync
 * without a separate fixed-time scheduler.
 *
 * <p>The interface mirrors {@link ScheduledSourceCompletionListener}: it lives
 * in the reporting module so that downstream modules can implement it without
 * creating a circular package dependency.</p>
 */
public interface ReportSlotCompletionListener {

    /**
     * Called once per active company after the slot's report rebuild succeeds.
     * Implementations must be idempotent and must not throw; any error should
     * be caught and logged internally so it does not affect other companies or
     * roll back the slot's COMPLETED status.
     *
     * @param companyId the company whose report window was just rebuilt
     * @param year      the calendar year of the slot (Asia/Shanghai)
     */
    void onReportSlotCompleted(String companyId, int year);
}
