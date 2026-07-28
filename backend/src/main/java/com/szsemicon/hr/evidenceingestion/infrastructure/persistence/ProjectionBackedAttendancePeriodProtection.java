package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.port.AttendancePeriodProtectionPort;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uses the latest immutable, published V10 projection as period authority.
 *
 * <p>A draft, absent projection, invalid identity anchor, or ambiguous active
 * employment is UNKNOWN and therefore cannot authorize effective mutation.
 * No state is inferred from the calendar month or current date.</p>
 */
@Repository
public class ProjectionBackedAttendancePeriodProtection
        implements AttendancePeriodProtectionPort {

    private static final int IDENTIFIER_MAX = 36;
    private static final int VERSION_MAX = 128;

    private final AttendancePeriodProtectionMapper mapper;

    public ProjectionBackedAttendancePeriodProtection(
            AttendancePeriodProtectionMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Protection protectionFor(
            String legalEntityId,
            String employeeId,
            LocalDate businessDate) {
        requireIdentifier(legalEntityId, "legalEntityId");
        requireIdentifier(employeeId, "employeeId");
        Objects.requireNonNull(businessDate, "businessDate");

        List<AttendancePeriodProjectionRow> rows =
                mapper.resolveLatestPublished(
                        legalEntityId, employeeId, businessDate);
        if (rows == null) {
            throw new IllegalStateException(
                    "attendance period protection mapper returned null");
        }
        if (rows.size() != 1) {
            return unknown(legalEntityId, employeeId, businessDate);
        }
        AttendancePeriodProjectionRow row = rows.getFirst();
        if (!valid(row, legalEntityId, employeeId, businessDate)) {
            return unknown(legalEntityId, employeeId, businessDate);
        }

        PeriodStatus status;
        try {
            status = PeriodStatus.valueOf(row.periodState());
        } catch (RuntimeException exception) {
            return unknown(legalEntityId, employeeId, businessDate);
        }
        if (status == PeriodStatus.UNKNOWN) {
            return unknown(legalEntityId, employeeId, businessDate);
        }
        String snapshotDigest = StableAuthorityDigest.sha256(
                "ATTENDANCE_PERIOD_PROJECTION_AUTHORITY_V1",
                legalEntityId,
                employeeId,
                businessDate.toString(),
                row.projectionId(),
                row.periodStart().toString(),
                row.periodEndExclusive().toString(),
                row.periodState(),
                row.projectionVersion(),
                row.sourceSnapshotDigest(),
                row.projectionDigest(),
                row.publishedAt().toString(),
                row.employeeVersionId(),
                Long.toString(row.employeeVersion()),
                row.employmentPeriodId(),
                row.employmentAssignmentId(),
                Long.toString(row.employmentVersion()),
                row.organizationId());
        return new Protection(
                status, row.projectionVersion(), snapshotDigest);
    }

    private static boolean valid(
            AttendancePeriodProjectionRow row,
            String legalEntityId,
            String employeeId,
            LocalDate businessDate) {
        return row != null
                && legalEntityId.equals(row.legalEntityId())
                && employeeId.equals(row.employeeId())
                && row.periodStart() != null
                && row.periodEndExclusive() != null
                && !businessDate.isBefore(row.periodStart())
                && businessDate.isBefore(row.periodEndExclusive())
                && row.publishedAt() != null
                && validId(row.projectionId())
                && validReference(row.projectionVersion(), VERSION_MAX)
                && validDigest(row.sourceSnapshotDigest())
                && validDigest(row.projectionDigest())
                && validId(row.employeeVersionId())
                && row.employeeVersion() >= 0
                && validId(row.employmentPeriodId())
                && validId(row.employmentAssignmentId())
                && row.employmentVersion() >= 0
                && validId(row.organizationId());
    }

    private static Protection unknown(
            String legalEntityId,
            String employeeId,
            LocalDate businessDate) {
        return new Protection(
                PeriodStatus.UNKNOWN,
                "UNKNOWN",
                StableAuthorityDigest.sha256(
                        "ATTENDANCE_PERIOD_UNKNOWN_V1",
                        legalEntityId,
                        employeeId,
                        businessDate.toString()));
    }

    private static boolean validId(String value) {
        return validReference(value, IDENTIFIER_MAX);
    }

    private static boolean validReference(String value, int maximumLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maximumLength
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void requireIdentifier(String value, String field) {
        if (!validId(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
