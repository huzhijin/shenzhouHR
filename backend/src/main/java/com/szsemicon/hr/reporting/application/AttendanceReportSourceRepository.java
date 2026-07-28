package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface AttendanceReportSourceRepository {

    List<LegalEntityOption> listAuthorizedLegalEntities(
            String principalId,
            String capabilityCode,
            YearMonth period,
            Instant authorizationTime);

    Optional<ReportSourceSnapshot> loadAuthorizedSnapshot(
            String principalId,
            String capabilityCode,
            ReportFilter filter,
            Instant authorizationTime);

    record LegalEntityOption(String legalEntityId, String name) {

        public LegalEntityOption {
            legalEntityId = requireText(legalEntityId, "legalEntityId", 36);
            name = requireText(name, "name", 200);
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
