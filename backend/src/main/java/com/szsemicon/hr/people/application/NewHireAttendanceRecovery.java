package com.szsemicon.hr.people.application;

import java.time.LocalDate;

/**
 * Schedules a background pass that releases punches quarantined before this
 * person existed, then recalculates the affected attendance.
 */
public interface NewHireAttendanceRecovery {

    void noteHire(LocalDate effectiveFrom);
}
