package com.szsemicon.hr.payroll.domain;

import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Metadata-only boundaries reserved for a later payroll implementation.
 *
 * <p>These types intentionally carry identity, version, lifecycle and
 * integrity references only. They do not model monetary values or calculation
 * rules.</p>
 */
public final class PayrollReservationModels {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private PayrollReservationModels() {
    }

    public enum PayrollPeriodStatus {
        RESERVED,
        INACTIVE
    }

    public enum PayrollItemStatus {
        ACTIVE,
        INACTIVE
    }

    public enum PayrollCalculationStatus {
        RESERVED,
        SUPERSEDED
    }

    public record PayrollPeriod(
            ExternalPreciseId payrollPeriodId,
            String periodCode,
            LocalDate startInclusive,
            LocalDate endExclusive,
            PayrollPeriodStatus status,
            long version) {

        public PayrollPeriod {
            Objects.requireNonNull(payrollPeriodId, "payroll period id is required");
            periodCode = code(periodCode, "payroll period code");
            range(startInclusive, endExclusive);
            Objects.requireNonNull(status, "payroll period status is required");
            positive(version, "payroll period version");
        }
    }

    public record PayrollItemDefinition(
            ExternalPreciseId payrollItemId,
            String itemCode,
            PayrollItemStatus status,
            long version) {

        public PayrollItemDefinition {
            Objects.requireNonNull(payrollItemId, "payroll item id is required");
            itemCode = code(itemCode, "payroll item code");
            Objects.requireNonNull(status, "payroll item status is required");
            positive(version, "payroll item version");
        }
    }

    public record EmployeePayrollProfileRef(
            ExternalPreciseId payrollProfileId,
            ExternalPreciseId employeeId,
            ExternalPreciseId legalEntityId,
            ExternalPreciseId payrollGroupId,
            long version) {

        public EmployeePayrollProfileRef {
            Objects.requireNonNull(payrollProfileId, "payroll profile id is required");
            Objects.requireNonNull(employeeId, "employee id is required");
            Objects.requireNonNull(legalEntityId, "legal entity id is required");
            Objects.requireNonNull(payrollGroupId, "payroll group id is required");
            positive(version, "payroll profile version");
        }
    }

    public record FrozenAttendanceSnapshotRef(
            ExternalPreciseId attendanceSnapshotId,
            LocalDate periodStartInclusive,
            LocalDate periodEndExclusive,
            long closeVersion,
            Instant closedAt,
            String integrityDigest) {

        public FrozenAttendanceSnapshotRef {
            Objects.requireNonNull(
                    attendanceSnapshotId, "attendance snapshot id is required");
            range(periodStartInclusive, periodEndExclusive);
            positive(closeVersion, "attendance close version");
            Objects.requireNonNull(closedAt, "attendance closed time is required");
            integrityDigest = digest(integrityDigest, "attendance snapshot digest");
        }
    }

    public record PayrollCalculationResultRef(
            ExternalPreciseId calculationResultId,
            ExternalPreciseId payrollPeriodId,
            ExternalPreciseId attendanceSnapshotId,
            PayrollCalculationStatus status,
            long version,
            String integrityDigest) {

        public PayrollCalculationResultRef {
            Objects.requireNonNull(
                    calculationResultId, "calculation result id is required");
            Objects.requireNonNull(payrollPeriodId, "payroll period id is required");
            Objects.requireNonNull(
                    attendanceSnapshotId, "attendance snapshot id is required");
            Objects.requireNonNull(status, "calculation result status is required");
            positive(version, "calculation result version");
            integrityDigest = digest(integrityDigest, "calculation result digest");
        }
    }

    public record PayrollRunReservation(
            PayrollPeriod period,
            EmployeePayrollProfileRef profile,
            List<PayrollItemDefinition> items,
            FrozenAttendanceSnapshotRef attendanceSnapshot,
            PayrollCalculationResultRef calculationResult) {

        public PayrollRunReservation {
            Objects.requireNonNull(period, "payroll period is required");
            Objects.requireNonNull(profile, "payroll profile is required");
            Objects.requireNonNull(attendanceSnapshot, "attendance snapshot is required");
            items = List.copyOf(Objects.requireNonNull(items, "payroll items are required"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("at least one payroll item is required");
            }
            Set<String> codes = new HashSet<>();
            for (PayrollItemDefinition item : items) {
                Objects.requireNonNull(item, "payroll item is required");
                if (!codes.add(item.itemCode())) {
                    throw new IllegalArgumentException(
                            "payroll item codes must be unique");
                }
            }
            if (!period.startInclusive().equals(attendanceSnapshot.periodStartInclusive())
                    || !period.endExclusive().equals(
                            attendanceSnapshot.periodEndExclusive())) {
                throw new IllegalArgumentException(
                        "attendance snapshot must cover the payroll period exactly");
            }
            if (calculationResult != null
                    && (!period.payrollPeriodId().equals(
                            calculationResult.payrollPeriodId())
                    || !attendanceSnapshot.attendanceSnapshotId().equals(
                            calculationResult.attendanceSnapshotId()))) {
                throw new IllegalArgumentException(
                        "calculation result references must match the reservation");
            }
        }
    }

    private static String code(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must be an uppercase stable code");
        }
        return value;
    }

    private static String digest(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        }
        return value;
    }

    private static void range(LocalDate startInclusive, LocalDate endExclusive) {
        Objects.requireNonNull(startInclusive, "period start is required");
        Objects.requireNonNull(endExclusive, "period end is required");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException(
                    "period must be a non-empty half-open range");
        }
    }

    private static void positive(long value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }
}
