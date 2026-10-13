package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EmployeeDeliBindingMapper {

    String findEmployeeIdByNumber(@Param("employeeNumber") String employeeNumber);

    List<EmployeeRosterRow> listCurrentRoster();

    String findCurrentBindingId(@Param("employeeId") String employeeId);

    String findEmployeeIdByDeliPersonId(
            @Param("sourceId") String sourceId,
            @Param("deliPersonId") String deliPersonId);

    String findActiveDeliSourceId();

    String findActiveDeliCompanyId(@Param("sourceId") String sourceId);

    String findActiveDeliTimeZone(@Param("sourceId") String sourceId);

    Integer findActiveDeliPageSize(@Param("sourceId") String sourceId);

    String findActiveDeliDisplayName(@Param("sourceId") String sourceId);

    String findActiveDeliSecretName(@Param("sourceId") String sourceId);

    void insertBinding(
            @Param("bindingId") String bindingId,
            @Param("employeeId") String employeeId,
            @Param("sourceId") String sourceId,
            @Param("deliUserId") String deliUserId,
            @Param("targetEmployeeNumber") String targetEmployeeNumber,
            @Param("confirmationRef") String confirmationRef,
            @Param("actorId") String actorId,
            @Param("at") Instant at,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("reason") String reason);

    void updateCurrentBinding(
            @Param("bindingId") String bindingId,
            @Param("sourceId") String sourceId,
            @Param("deliUserId") String deliUserId,
            @Param("confirmationRef") String confirmationRef,
            @Param("actorId") String actorId,
            @Param("at") Instant at,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("reason") String reason);

    void insertRevision(
            @Param("revisionId") String revisionId,
            @Param("bindingId") String bindingId,
            @Param("sourceId") String sourceId,
            @Param("employeeId") String employeeId,
            @Param("deliUserId") String deliUserId,
            @Param("confirmationRef") String confirmationRef,
            @Param("actorId") String actorId,
            @Param("at") Instant at,
            @Param("reason") String reason);
}
