package com.szsemicon.hr.evidenceingestion.port;

import java.time.Instant;
import java.util.List;

public interface EmployeeEmploymentResolverPort {

    List<Resolution> resolveByEmployeeNumber(String companyId, String employeeNumber, Instant at);

    List<Resolution> resolveByConfirmedBinding(
            String companyId,
            String locationId,
            String deviceId,
            ConfirmedBindingKind bindingKind,
            String externalPersonRef,
            Instant at);

    enum ConfirmedBindingKind {
        DELI_EXT_ID,
        DELI_USER_ID
    }

    record Resolution(
            String employeeId,
            String employmentPeriodId,
            String resolverSnapshotDigest) {
    }
}
