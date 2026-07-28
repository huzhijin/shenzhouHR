package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.AttendanceExceptionCase;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionFinding;
import java.time.Instant;
import java.util.List;

public final class AttendanceExceptionReconciler {

    public List<AttendanceExceptionCase> reconcile(
            List<AttendanceExceptionCase> previous,
            List<ExceptionFinding> findings,
            String requestId,
            Instant occurredAt) {
        throw new UnsupportedOperationException(
                "RED: exception reconciliation");
    }
}
