package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LeaveAccountOaRecalculateMapper {

    List<String> listActiveCompanyIds();

    List<EmployeeEmploymentRow> listCompanyEmployments(
            @Param("companyId") String companyId,
            @Param("at") Instant at);

    List<OaHourRow> listOaHours(
            @Param("companyId") String companyId,
            @Param("yearStart") Instant yearStart,
            @Param("yearEndExclusive") Instant yearEndExclusive);

    List<OaSyncEntryRow> listUnreversedOaSyncEntries(
            @Param("accountId") String accountId,
            @Param("yearStart") LocalDate yearStart,
            @Param("yearEndExclusive") LocalDate yearEndExclusive);

    void insertReversal(
            @Param("entryId") String entryId,
            @Param("accountId") String accountId,
            @Param("sequenceNo") int sequenceNo,
            @Param("amountHours") BigDecimal amountHours,
            @Param("sourceType") String sourceType,
            @Param("sourceId") String sourceId,
            @Param("businessDate") LocalDate businessDate,
            @Param("policyVersionId") String policyVersionId,
            @Param("requestId") String requestId,
            @Param("reversalOfEntryId") String reversalOfEntryId,
            @Param("actorId") String actorId,
            @Param("occurredAt") Instant occurredAt);

    record EmployeeEmploymentRow(
            String employeeId,
            String employmentPeriodId,
            String companyId) {
    }

    record OaHourRow(
            String employeeId,
            String kind,
            long recognizedMinutes) {
    }

    record OaSyncEntryRow(
            String entryId,
            String entryType,
            BigDecimal amountHours) {
    }
}
