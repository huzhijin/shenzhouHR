package com.szsemicon.hr.employee.infrastructure.persistence;

import com.szsemicon.hr.employee.application.PunchExemptionPort;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPunchExemptionAdapter implements PunchExemptionPort {

    private final PunchExemptionMapper mapper;

    public MyBatisPunchExemptionAdapter(PunchExemptionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean hasStandingExemption(String employeeId) {
        return mapper.hasStandingExemption(employeeId);
    }

    @Override
    public boolean hasExecutiveRole(String employeeId) {
        return mapper.hasExecutiveRole(employeeId);
    }

    @Override
    public String currentEmployeeNumber(String employeeId) {
        return mapper.currentEmployeeNumber(employeeId);
    }

    @Override
    public int insertStanding(
            String exemptionId,
            String employeeId,
            String employeeNumber,
            Instant validFrom) {
        return mapper.insertStanding(exemptionId, employeeId, employeeNumber, validFrom);
    }

    @Override
    public int closeStanding(String employeeId, Instant validTo) {
        return mapper.closeStanding(employeeId, validTo);
    }
}
