package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public interface AttendanceReportSourceRepository {

    List<CompanyOption> listAuthorizedCompanies(
            String principalId,
            String capabilityCode,
            YearMonth period,
            Instant authorizationTime);

    Optional<RealtimeAuthorization> resolveRealtimeAuthorization(
            String principalId,
            String capabilityCode,
            String companyId,
            Instant authorizationTime);

    Optional<ReportSourceSnapshot> loadAuthorizedSnapshot(
            String principalId,
            String capabilityCode,
            ReportFilter filter,
            Instant authorizationTime);

    Optional<ReportSourceSnapshot> loadAuthorizedSnapshotIntersection(
            String principalId,
            String additionalCapabilityCode,
            ReportSourceSnapshot readSnapshot,
            boolean requireFullReadScopeCoverage,
            Instant authorizationTime);

    List<DepartmentAttendanceRate> listAuthorizedDepartmentAttendanceRates(
            String principalId,
            String capabilityCode,
            ReportFilter filter,
            Instant authorizationTime);

    List<EmployeeSickLeaveDays> listAuthorizedEmployeeSickLeaveDays(
            String principalId,
            String capabilityCode,
            ReportFilter filter,
            Instant authorizationTime);

    List<EmployeeDepartmentAttendancePeriod>
            listAuthorizedEmployeeDepartmentAttendancePeriods(
                    String principalId,
                    String capabilityCode,
                    ReportFilter filter,
                    Instant authorizationTime);

    record RealtimeAuthorization(
            AuthorizedScope scope,
            String companyId,
            boolean companyWide,
            String principalEmployeeId,
            Set<String> employeeIds,
            Set<String> organizationIds) {

        public RealtimeAuthorization {
            Objects.requireNonNull(scope, "scope");
            companyId = requireReference(companyId, "companyId");
            principalEmployeeId = optionalReference(
                    principalEmployeeId, "principalEmployeeId");
            employeeIds = immutableReferences(employeeIds, "employeeIds");
            organizationIds = immutableReferences(
                    organizationIds, "organizationIds");
            if (companyWide != (scope.type() == ScopeType.COMPANY)) {
                throw new IllegalArgumentException(
                        "companyWide must match the resolved scope type");
            }
            if (scope.type() == ScopeType.SELF
                    && principalEmployeeId == null) {
                throw new IllegalArgumentException(
                        "SELF scope requires a principal employee");
            }
        }

        private static Set<String> immutableReferences(
                Set<String> values, String field) {
            Objects.requireNonNull(values, field);
            TreeSet<String> normalized = new TreeSet<>();
            for (String value : values) {
                normalized.add(requireReference(value, field));
            }
            return Collections.unmodifiableSet(
                    new LinkedHashSet<>(normalized));
        }

        private static String optionalReference(
                String value, String field) {
            return value == null ? null : requireReference(value, field);
        }

        private static String requireReference(
                String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty() || normalized.length() > 36) {
                throw new IllegalArgumentException(
                        field + " must contain valid references");
            }
            return normalized;
        }
    }

    record CompanyOption(String companyId, String companyName) {

        public CompanyOption {
            companyId = requireText(companyId, "companyId", 36);
            companyName = requireText(
                    companyName, "companyName", 200);
        }

        private static String requireText(
                String value, String label, int maximumLength) {
            String normalized = Objects.requireNonNull(value, label).trim();
            if (normalized.isEmpty()
                    || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(
                        label + " must be non-blank and at most "
                                + maximumLength + " characters");
            }
            return normalized;
        }
    }

    record DepartmentAttendanceRate(
            String companyId,
            String organizationId,
            long actualAttendanceDays,
            long scheduledAttendanceDays,
            long sickLeaveDays,
            BigDecimal attendanceRate) {

        public DepartmentAttendanceRate {
            companyId = requireReference(companyId, "companyId");
            organizationId = requireReference(
                    organizationId, "organizationId");
            if (actualAttendanceDays < 0 || scheduledAttendanceDays <= 0
                    || sickLeaveDays < 0
                    || sickLeaveDays > scheduledAttendanceDays) {
                throw new IllegalArgumentException(
                        "department attendance days are inconsistent");
            }
            attendanceRate = Objects.requireNonNull(
                            attendanceRate, "attendanceRate")
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal expectedRate = BigDecimal
                    .valueOf(actualAttendanceDays)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(
                            BigDecimal.valueOf(scheduledAttendanceDays),
                            2,
                            RoundingMode.HALF_UP);
            if (attendanceRate.compareTo(expectedRate) != 0) {
                throw new IllegalArgumentException(
                        "department attendance rate does not match day totals");
            }
        }

        public DepartmentAttendanceRate(
                String companyId,
                String organizationId,
                long actualAttendanceDays,
                long scheduledAttendanceDays,
                BigDecimal attendanceRate) {
            this(
                    companyId,
                    organizationId,
                    actualAttendanceDays,
                    scheduledAttendanceDays,
                    0,
                    attendanceRate);
        }

        private static String requireReference(
                String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty() || normalized.length() > 36) {
                throw new IllegalArgumentException(
                        field + " must be non-blank and at most 36 characters");
            }
            return normalized;
        }
    }

    record EmployeeSickLeaveDays(
            String companyId,
            String employeeId,
            String organizationId,
            long sickLeaveDays) {

        public EmployeeSickLeaveDays {
            companyId = requireReference(companyId, "companyId");
            employeeId = requireReference(employeeId, "employeeId");
            organizationId = requireReference(
                    organizationId, "organizationId");
            if (sickLeaveDays < 0) {
                throw new IllegalArgumentException(
                        "sickLeaveDays must be non-negative");
            }
        }

        private static String requireReference(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty() || normalized.length() > 36) {
                throw new IllegalArgumentException(
                        field + " must be non-blank and at most 36 characters");
            }
            return normalized;
        }
    }

    record EmployeeDepartmentAttendancePeriod(
            String companyId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            LocalDate periodStart,
            LocalDate periodEnd,
            long actualAttendanceDays,
            long scheduledAttendanceDays,
            BigDecimal attendanceRate) {

        public EmployeeDepartmentAttendancePeriod {
            companyId = requireReference(companyId, "companyId");
            employeeId = requireReference(employeeId, "employeeId");
            employeeNumber = requireText(
                    employeeNumber, "employeeNumber", 64);
            employeeName = requireText(employeeName, "employeeName", 200);
            organizationId = requireReference(
                    organizationId, "organizationId");
            organizationName = requireText(
                    organizationName, "organizationName", 200);
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            if (periodEnd.isBefore(periodStart)) {
                throw new IllegalArgumentException(
                        "department period end must not precede start");
            }
            if (actualAttendanceDays < 0 || scheduledAttendanceDays < 0) {
                throw new IllegalArgumentException(
                        "employee department attendance days must be non-negative");
            }
            if (scheduledAttendanceDays == 0) {
                if (attendanceRate != null) {
                    throw new IllegalArgumentException(
                            "zero scheduled days must have no attendance rate");
                }
            } else {
                attendanceRate = Objects.requireNonNull(
                                attendanceRate, "attendanceRate")
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal expectedRate = BigDecimal
                        .valueOf(actualAttendanceDays)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(
                                BigDecimal.valueOf(scheduledAttendanceDays),
                                2,
                                RoundingMode.HALF_UP);
                if (attendanceRate.compareTo(expectedRate) != 0) {
                    throw new IllegalArgumentException(
                            "employee department rate does not match day totals");
                }
            }
        }

        private static String requireReference(
                String value, String field) {
            return requireText(value, field, 36);
        }

        private static String requireText(
                String value, String field, int maximumLength) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty() || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(
                        field + " must be non-blank and at most "
                                + maximumLength + " characters");
            }
            return normalized;
        }
    }
}
