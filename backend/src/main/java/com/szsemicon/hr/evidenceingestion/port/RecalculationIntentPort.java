package com.szsemicon.hr.evidenceingestion.port;

import java.time.LocalDate;
import java.util.Set;

public interface RecalculationIntentPort {

    void append(Intent intent);

    record Intent(
            String companyId,
            String employeeId,
            Set<LocalDate> candidateBusinessDates,
            Set<String> evidenceIds,
            String reasonCode,
            String resolverSnapshotDigest,
            String periodVersion,
            String requestId) {

        public Intent {
            candidateBusinessDates = Set.copyOf(candidateBusinessDates);
            evidenceIds = Set.copyOf(evidenceIds);
        }
    }
}
