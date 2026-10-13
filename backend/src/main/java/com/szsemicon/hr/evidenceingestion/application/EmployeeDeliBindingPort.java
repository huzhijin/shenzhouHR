package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;
import java.util.List;

/**
 * Port for employee Deli binding configuration and operations.
 */
public interface EmployeeDeliBindingPort {

    String findActiveDeliSourceId();

    String findActiveDeliCompanyId(String sourceId);

    String findActiveDeliDisplayName(String sourceId);

    String findActiveDeliSecretName(String sourceId);

    String findActiveDeliTimeZone(String sourceId);

    Integer findActiveDeliPageSize(String sourceId);

    List<EmployeeRosterRow> listCurrentRoster();

    String findEmployeeIdByNumber(String employeeNumber);

    String findEmployeeIdByDeliPersonId(String sourceId, String deliPersonId);

    String findCurrentBindingId(String employeeId);

    void insertBinding(
            String bindingId,
            String employeeId,
            String sourceId,
            String deliUserId,
            String targetEmployeeNumber,
            String confirmationRef,
            String actorId,
            Instant at,
            Instant effectiveFrom,
            String reason);

    void updateCurrentBinding(
            String bindingId,
            String sourceId,
            String deliUserId,
            String confirmationRef,
            String actorId,
            Instant at,
            Instant effectiveFrom,
            String reason);

    void insertRevision(
            String revisionId,
            String bindingId,
            String sourceId,
            String employeeId,
            String deliUserId,
            String confirmationRef,
            String actorId,
            Instant at,
            String reason);

    record EmployeeRosterRow(
            String employeeId,
            String employeeNumber,
            String displayName) {
    }
}
