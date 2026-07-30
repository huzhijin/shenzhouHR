package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

final class AttendancePolicyRows {

    private AttendancePolicyRows() {
    }

    record BindingRow(
            String attendancePolicyBindingId,
            String attendancePolicyBindingRevisionId,
            int revisionNumber,
            String companyId,
            String policyKind,
            String policyVersionId,
            String attendanceGroupId,
            String attendanceGroupRevisionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }
}
