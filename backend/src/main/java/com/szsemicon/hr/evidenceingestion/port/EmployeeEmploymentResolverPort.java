package com.szsemicon.hr.evidenceingestion.port;

import java.time.Instant;
import java.util.List;

public interface EmployeeEmploymentResolverPort {

    List<Resolution> resolveByEmployeeNumber(String legalEntityId, String employeeNumber, Instant at);

    List<Resolution> resolveByConfirmedBinding(
            String legalEntityId,
            String locationId,
            String deviceId,
            String externalPersonRef,
            Instant at);

    record Resolution(
            String employeeId,
            String employmentPeriodId,
            String resolverSnapshotDigest) {
    }
}
