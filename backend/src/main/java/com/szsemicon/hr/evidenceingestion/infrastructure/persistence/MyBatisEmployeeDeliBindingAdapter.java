package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.EmployeeDeliBindingPort;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisEmployeeDeliBindingAdapter implements EmployeeDeliBindingPort {

    private final EmployeeDeliBindingMapper mapper;

    public MyBatisEmployeeDeliBindingAdapter(EmployeeDeliBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String findActiveDeliSourceId() {
        return mapper.findActiveDeliSourceId();
    }

    @Override
    public String findActiveDeliCompanyId(String sourceId) {
        return mapper.findActiveDeliCompanyId(sourceId);
    }

    @Override
    public String findActiveDeliDisplayName(String sourceId) {
        return mapper.findActiveDeliDisplayName(sourceId);
    }

    @Override
    public String findActiveDeliSecretName(String sourceId) {
        return mapper.findActiveDeliSecretName(sourceId);
    }

    @Override
    public String findActiveDeliTimeZone(String sourceId) {
        return mapper.findActiveDeliTimeZone(sourceId);
    }

    @Override
    public Integer findActiveDeliPageSize(String sourceId) {
        return mapper.findActiveDeliPageSize(sourceId);
    }

    @Override
    public List<EmployeeRosterRow> listCurrentRoster() {
        return mapper.listCurrentRoster().stream()
                .map(row -> new EmployeeRosterRow(
                        row.employeeId(),
                        row.employeeNumber(),
                        row.displayName()))
                .collect(Collectors.toList());
    }

    @Override
    public String findEmployeeIdByNumber(String employeeNumber) {
        return mapper.findEmployeeIdByNumber(employeeNumber);
    }

    @Override
    public String findEmployeeIdByDeliPersonId(String sourceId, String deliPersonId) {
        return mapper.findEmployeeIdByDeliPersonId(sourceId, deliPersonId);
    }

    @Override
    public String findCurrentBindingId(String employeeId) {
        return mapper.findCurrentBindingId(employeeId);
    }

    @Override
    public void insertBinding(
            String bindingId,
            String employeeId,
            String sourceId,
            String deliUserId,
            String targetEmployeeNumber,
            String confirmationRef,
            String actorId,
            Instant at,
            Instant effectiveFrom,
            String reason) {
        mapper.insertBinding(
                bindingId,
                employeeId,
                sourceId,
                deliUserId,
                targetEmployeeNumber,
                confirmationRef,
                actorId,
                at,
                effectiveFrom,
                reason);
    }

    @Override
    public void updateCurrentBinding(
            String bindingId,
            String sourceId,
            String deliUserId,
            String confirmationRef,
            String actorId,
            Instant at,
            Instant effectiveFrom,
            String reason) {
        mapper.updateCurrentBinding(
                bindingId,
                sourceId,
                deliUserId,
                confirmationRef,
                actorId,
                at,
                effectiveFrom,
                reason);
    }

    @Override
    public void insertRevision(
            String revisionId,
            String bindingId,
            String sourceId,
            String employeeId,
            String deliUserId,
            String confirmationRef,
            String actorId,
            Instant at,
            String reason) {
        mapper.insertRevision(
                revisionId,
                bindingId,
                sourceId,
                employeeId,
                deliUserId,
                confirmationRef,
                actorId,
                at,
                reason);
    }
}
