package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface EvidenceEmployeeResolverMapper {

    List<EvidenceEmployeeResolverRow> resolveByEmployeeNumber(
            @Param("legalEntityId") String legalEntityId,
            @Param("employeeNumber") String employeeNumber,
            @Param("businessDate") LocalDate businessDate);

    List<EvidenceEmployeeResolverRow> resolveByConfirmedDeliBinding(
            @Param("legalEntityId") String legalEntityId,
            @Param("bindingKind") String bindingKind,
            @Param("externalPersonRef") String externalPersonRef,
            @Param("sourceLocalTime") LocalDateTime sourceLocalTime,
            @Param("businessDate") LocalDate businessDate);
}
