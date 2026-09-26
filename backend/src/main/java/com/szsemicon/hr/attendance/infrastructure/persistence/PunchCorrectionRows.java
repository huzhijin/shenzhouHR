package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

final class PunchCorrectionRows {

    private PunchCorrectionRows() {
    }

    record RequestRow(
            String requestId,
            String employeeId,
            LocalDate requestMonth,
            LocalDate businessDate,
            String punchSide,
            String correctionReason,
            String status,
            Instant requestedAt,
            String requestedBy,
            Instant reviewedAt,
            String reviewedBy,
            String reviewNotes) {
    }
}
