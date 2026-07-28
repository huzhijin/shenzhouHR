package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

final class ShiftRows {

    private ShiftRows() {
    }

    record TemplateRow(
            String shiftTemplateId,
            String legalEntityId,
            String locationId,
            String templateCode,
            String templateName,
            String status,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    record VersionRow(
            String shiftVersionId,
            String shiftTemplateId,
            int versionNumber,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String timeZoneSnapshot,
            String segmentsJson,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            Instant publishedAt,
            String updatedBy,
            Instant updatedAt) {
    }
}
