package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.HrPunchAdjustmentPort;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisHrPunchAdjustmentAdapter implements HrPunchAdjustmentPort {

    private final HrPunchAdjustmentMapper mapper;

    public MyBatisHrPunchAdjustmentAdapter(HrPunchAdjustmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(
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
            String dayTypes) {
        mapper.insert(
                adjustmentId,
                companyId,
                employeeId,
                businessDate,
                onDutyAt,
                offDutyAt,
                reason,
                createdBy,
                createdAt,
                overtimeMinutesOverride,
                clearedExceptionTypes,
                dayTypes);
    }

    @Override
    public String findEmployeeCompanyId(String employeeId) {
        return mapper.findEmployeeCompanyId(employeeId);
    }
}
