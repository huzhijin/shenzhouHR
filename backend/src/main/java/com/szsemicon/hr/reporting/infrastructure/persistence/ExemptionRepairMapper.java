package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.ExemptionRow;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairReceipt;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExemptionRepairMapper {

    ExemptionRow findByExemptionId(@Param("exemptionId") String exemptionId);

    int updateValidFrom(
            @Param("exemptionId") String exemptionId,
            @Param("expectedValidFrom") Instant expectedValidFrom,
            @Param("newValidFrom") Instant newValidFrom);

    RepairReceipt findReceipt(
            @Param("exemptionId") String exemptionId,
            @Param("operation") String operation);

    void insertReceipt(
            @Param("receiptId") String receiptId,
            @Param("requestId") String requestId,
            @Param("exemptionId") String exemptionId,
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("operation") String operation,
            @Param("originalValidFrom") Instant originalValidFrom,
            @Param("repairedValidFrom") Instant repairedValidFrom,
            @Param("approvalEvidenceDigest") String approvalEvidenceDigest,
            @Param("payloadDigest") String payloadDigest,
            @Param("repairedAt") Instant repairedAt,
            @Param("repairedBy") String repairedBy,
            @Param("beforeDigest") String beforeDigest,
            @Param("afterDigest") String afterDigest);
}
