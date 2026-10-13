package com.szsemicon.hr.evidenceingestion.port;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

public interface AttendanceConfigurationResolverPort {

    Resolution resolve(String companyId, String employeeId, Instant instant);

    record Resolution(
            String locationId,
            String attendanceGroupRevisionId,
            String shiftVersionId,
            ZoneId businessTimeZone,
            Set<LocalDate> candidateBusinessDates,
            String resolverSnapshotDigest,
            boolean authoritative) {

        public Resolution {
            candidateBusinessDates = Set.copyOf(candidateBusinessDates);
        }
    }
}
