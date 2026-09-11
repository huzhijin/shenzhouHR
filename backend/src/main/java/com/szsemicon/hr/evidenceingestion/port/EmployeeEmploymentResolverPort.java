package com.szsemicon.hr.evidenceingestion.port;

import java.time.Instant;
import java.util.List;

public interface EmployeeEmploymentResolverPort {

    List<Resolution> resolveByEmployeeNumber(String companyId, String employeeNumber, Instant at);

    List<Resolution> resolveByConfirmedBinding(
            String sourceId,
            String companyId,
            String locationId,
            String deviceId,
            ConfirmedBindingKind bindingKind,
            String externalPersonRef,
            Instant at);

    /**
     * Unique current display name only. Duplicate names must return every
     * row so the caller can skip to empno instead of guessing.
     */
    default List<Resolution> resolveByDisplayName(
            String companyId, String displayName, Instant at) {
        return List.of();
    }

    enum ConfirmedBindingKind {
        DELI_EXT_ID,
        DELI_USER_ID
    }

    record Resolution(
            String employeeId,
            String employmentPeriodId,
            String resolverSnapshotDigest,
            String companyId) {

        public Resolution(
                String employeeId,
                String employmentPeriodId,
                String resolverSnapshotDigest) {
            this(employeeId, employmentPeriodId, resolverSnapshotDigest, null);
        }
    }
}
