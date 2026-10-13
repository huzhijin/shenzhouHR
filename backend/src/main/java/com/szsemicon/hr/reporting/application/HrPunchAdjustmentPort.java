package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;

/** HR punch-adjustment persistence; SQL stays in HrPunchAdjustmentMapper.xml. */
public interface HrPunchAdjustmentPort {

    void insert(
            String adjustmentId,
            String companyId,
            String employeeId,
            LocalDate businessDate,
            Instant onDutyAt,
            Instant offDutyAt,
            String reason,
            String createdBy,
            Instant createdAt,
            Integer overtimeMinutesOverride,
            String clearedExceptionTypes,
            String dayTypes);

    /** Company of the employee by primary key, or null when the employee does not exist. */
    String findEmployeeCompanyId(String employeeId);
}
