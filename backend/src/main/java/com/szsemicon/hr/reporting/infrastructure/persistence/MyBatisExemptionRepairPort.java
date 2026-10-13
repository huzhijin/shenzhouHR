package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.ExemptionRepairPort;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.ExemptionRow;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairReceipt;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisExemptionRepairPort implements ExemptionRepairPort {

    private final ExemptionRepairMapper mapper;

    public MyBatisExemptionRepairPort(ExemptionRepairMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ExemptionRow> findExemption(String exemptionId) {
        ExemptionRow row = mapper.findByExemptionId(exemptionId);
        return Optional.ofNullable(row);
    }

    @Override
    public int updateExemptionValidFrom(String exemptionId, Instant expectedValidFrom, Instant newValidFrom) {
        return mapper.updateValidFrom(exemptionId, expectedValidFrom, newValidFrom);
    }

    @Override
    public void insertRepairReceipt(RepairReceipt receipt) {
        mapper.insertReceipt(
                receipt.receiptId(),
                receipt.requestId(),
                receipt.exemptionId(),
                receipt.companyId(),
                receipt.employeeId(),
                receipt.operation(),
                receipt.originalValidFrom(),
                receipt.repairedValidFrom(),
                receipt.approvalEvidenceDigest(),
                receipt.payloadDigest(),
                receipt.repairedAt(),
                receipt.repairedBy(),
                receipt.beforeDigest(),
                receipt.afterDigest());
    }

    @Override
    public Optional<RepairReceipt> findReceipt(String exemptionId, String operation) {
        RepairReceipt row = mapper.findReceipt(exemptionId, operation);
        return Optional.ofNullable(row);
    }
}
