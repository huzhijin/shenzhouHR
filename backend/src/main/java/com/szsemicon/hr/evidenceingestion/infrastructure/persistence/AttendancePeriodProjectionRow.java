package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

record AttendancePeriodProjectionRow(
        String projectionId,
        String legalEntityId,
        LocalDate periodStart,
        LocalDate periodEndExclusive,
        String periodState,
        String projectionVersion,
        String sourceSnapshotDigest,
        String projectionDigest,
        Instant publishedAt,
        String employeeId,
        String employeeVersionId,
        long employeeVersion,
        String employmentPeriodId,
        String employmentAssignmentId,
        long employmentVersion,
        String organizationId) {
}
