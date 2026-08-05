package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface AttendanceReportSourceRepository {

    List<CompanyOption> listAuthorizedCompanies(
            String principalId,
            String capabilityCode,
            YearMonth period,
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
}
