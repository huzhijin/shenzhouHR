package com.szsemicon.hr.employee.application;

import java.time.Instant;

/**
 * Standing punch-exemption list operations used by the employee switch.
 * SQL stays in PunchExemptionMapper.xml; the MyBatis adapter forwards 1:1.
 */
public interface PunchExemptionPort {

    boolean hasStandingExemption(String employeeId);

    boolean hasExecutiveRole(String employeeId);

    /** Current employee number, or null when the employee has no current record. */
    String currentEmployeeNumber(String employeeId);

    int insertStanding(
            String exemptionId,
            String employeeId,
            String employeeNumber,
            Instant validFrom);

    int closeStanding(String employeeId, Instant validTo);
}
