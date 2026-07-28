package com.szsemicon.hr.people.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleCommands.AdjustPriorService;
import com.szsemicon.hr.people.application.PeopleCommands.CreateEmployee;
import com.szsemicon.hr.people.application.PeopleCommands.CreateEmployment;
import com.szsemicon.hr.people.application.PeopleCommands.CreateOrganization;
import com.szsemicon.hr.people.application.PeopleCommands.UpdateEmployee;
import com.szsemicon.hr.people.application.PeopleCommands.UpdateEmployment;
import com.szsemicon.hr.people.application.PeopleCommands.UpdateOrganization;
import com.szsemicon.hr.people.domain.PeopleModels.EmployeeVersion;
import com.szsemicon.hr.people.domain.PeopleModels.EmploymentPeriod;
import com.szsemicon.hr.people.domain.PeopleModels.IdempotencyRecord;
import com.szsemicon.hr.people.domain.PeopleModels.OrganizationVersion;
import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceRecord;
import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceReplay;
import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.validation.IdempotencyKeyPolicy;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PeopleManagementService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository repository;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PeopleManagementService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository repository,
            AuditService auditService,
            SecurityTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public OrganizationVersion createOrganization(
            CreateOrganization command, long expectedVersion, String idempotencyKey) {
        requireExpected(expectedVersion, 0);
        validateOrganization(
                command.code(), command.name(), command.organizationType(),
                "ACTIVE", command.effectiveFrom(), null, command.reason());
        requireIdempotencyKey(idempotencyKey);
        requireLegalEntity(CapabilityCodes.ORGANIZATION_CREATE, command.legalEntityId());
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(command);
        IdempotencyRecord existing = existing(
                actor, "ORGANIZATION_CREATE", idempotencyKey, requestDigest);
        if (existing != null) {
            return repository.findCurrentOrganization(existing.resourceId())
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        }
        repository.lockLegalEntity(command.legalEntityId());
        if (command.parentOrganizationId() != null) {
            OrganizationVersion parent = requireOrganization(
                    command.parentOrganizationId(), CapabilityCodes.ORGANIZATION_CREATE, null);
            if (!parent.legalEntityId().equals(command.legalEntityId())) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
        }
        if (repository.organizationCodeExists(
                command.legalEntityId(), command.code().trim(), null)) {
            throw conflict("ORGANIZATION_CODE_CONFLICT", "组织编码已经存在");
        }
        Instant now = clock.instant();
        String organizationId = UUID.randomUUID().toString();
        repository.createOrganizationIdentity(
                organizationId, command.legalEntityId(), "ACTIVE", now);
        OrganizationVersion version = new OrganizationVersion(
                UUID.randomUUID().toString(),
                organizationId,
                command.legalEntityId(),
                command.parentOrganizationId(),
                command.code().trim(),
                command.name().trim(),
                command.organizationType(),
                "ACTIVE",
                command.effectiveFrom(),
                null,
                "LOCAL",
                null,
                0,
                command.reason().trim(),
                actor,
                now,
                0);
        repository.saveOrganizationVersion(version, UUID.randomUUID().toString());
        repository.rebuildOrganizationClosure(UUID.randomUUID().toString());
        saveIdempotency(
                actor, "ORGANIZATION_CREATE", idempotencyKey,
                requestDigest, organizationId, now);
        auditService.record(
                actor, "ORGANIZATION_VERSION_CREATED", "ORGANIZATION",
                organizationId, "SUCCESS", command.reason(), null, digest(version));
        return repository.findCurrentOrganization(organizationId).orElseThrow();
    }

    @Transactional
    public OrganizationVersion updateOrganization(
            String organizationId,
            UpdateOrganization command,
            long expectedVersion,
            String idempotencyKey) {
        validateOrganization(
                command.code(), command.name(), command.organizationType(),
                command.status(), command.effectiveFrom(), command.effectiveTo(),
                command.reason());
        requireIdempotencyKey(idempotencyKey);
        OrganizationVersion current = requireOrganization(
                organizationId, CapabilityCodes.ORGANIZATION_EDIT, null);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(organizationId, command));
        IdempotencyRecord existing = existing(
                actor, "ORGANIZATION_EDIT", idempotencyKey, requestDigest);
        if (existing != null) {
            return repository.findCurrentOrganization(organizationId)
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        }
        requireExpected(current.rowVersion(), expectedVersion);
        repository.lockLegalEntity(current.legalEntityId());
        current = repository.findCurrentOrganization(organizationId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireExpected(current.rowVersion(), expectedVersion);
        requireNextVersionDate(
                current.effectiveFrom(), current.effectiveTo(), command.effectiveFrom());
        if (command.parentOrganizationId() != null) {
            OrganizationVersion parent = requireOrganization(
                    command.parentOrganizationId(), CapabilityCodes.ORGANIZATION_EDIT, null);
            if (!parent.legalEntityId().equals(current.legalEntityId())) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
        }
        if (repository.organizationWouldCycle(
                organizationId, command.parentOrganizationId())) {
            throw conflict("ORGANIZATION_PARENT_CYCLE", "上级组织会形成环");
        }
        if (repository.organizationCodeExists(
                current.legalEntityId(), command.code().trim(), organizationId)) {
            throw conflict("ORGANIZATION_CODE_CONFLICT", "组织编码已经存在");
        }
        Instant now = clock.instant();
        repository.closeCurrentOrganizationVersion(
                organizationId,
                command.effectiveFrom().atStartOfDay(ZoneOffset.UTC).toInstant(),
                expectedVersion);
        OrganizationVersion version = new OrganizationVersion(
                UUID.randomUUID().toString(),
                organizationId,
                current.legalEntityId(),
                command.parentOrganizationId(),
                command.code().trim(),
                command.name().trim(),
                command.organizationType(),
                command.status(),
                command.effectiveFrom(),
                command.effectiveTo(),
                "LOCAL",
                null,
                expectedVersion + 1,
                command.reason().trim(),
                actor,
                now,
                current.childCount());
        repository.saveOrganizationVersion(version, UUID.randomUUID().toString());
        repository.rebuildOrganizationClosure(UUID.randomUUID().toString());
        saveIdempotency(
                actor, "ORGANIZATION_EDIT", idempotencyKey,
                requestDigest, organizationId, now);
        auditService.record(
                actor, "ORGANIZATION_VERSION_CREATED", "ORGANIZATION",
                organizationId, "SUCCESS", command.reason(), digest(current), digest(version));
        return repository.findCurrentOrganization(organizationId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public OrganizationVersion getOrganization(String organizationId, LocalDate asOf) {
        return requireOrganization(
                organizationId, CapabilityCodes.ORGANIZATION_READ, asOf);
    }

    @Transactional(readOnly = true)
    public PeoplePage<OrganizationVersion> listOrganizationVersions(
            String organizationId, int page, int size) {
        validatePage(page, size);
        requireOrganization(organizationId, CapabilityCodes.ORGANIZATION_READ, null);
        return new PeoplePage<>(
                repository.listOrganizationVersions(
                        organizationId, size, page * size),
                repository.countOrganizationVersions(organizationId),
                page,
                size);
    }

    @Transactional
    public EmployeeDetail createEmployee(
            CreateEmployee command, long expectedVersion, String idempotencyKey) {
        requireExpected(expectedVersion, 0);
        validateEmployee(
                command.employeeNumber(), command.displayName(), "ACTIVE",
                command.effectiveFrom(), null, command.reason());
        requireIdempotencyKey(idempotencyKey);
        requireLegalEntity(CapabilityCodes.EMPLOYEE_CREATE, command.legalEntityId());
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(command);
        IdempotencyRecord existing = existing(
                actor, "EMPLOYEE_CREATE", idempotencyKey, requestDigest);
        if (existing != null) {
            return employeeDetail(
                    repository.findCurrentEmployee(existing.resourceId())
                            .orElseThrow(ResourceNotAvailableAccessDeniedException::new),
                    null,
                    CapabilityCodes.EMPLOYEE_CREATE);
        }
        repository.lockLegalEntity(command.legalEntityId());
        if (repository.employeeNumberExists(
                command.legalEntityId(), command.employeeNumber().trim(), null)) {
            throw conflict("EMPLOYEE_NUMBER_CONFLICT", "员工编号已经存在");
        }
        if (command.externalEmployeeId() != null) {
            new ExternalPreciseId(command.externalEmployeeId());
        }
        Instant now = clock.instant();
        String employeeId = UUID.randomUUID().toString();
        repository.createEmployeeIdentity(
                employeeId,
                command.legalEntityId(),
                command.employeeNumber().trim(),
                command.displayName().trim(),
                "ACTIVE",
                command.effectiveFrom(),
                now);
        EmployeeVersion version = new EmployeeVersion(
                UUID.randomUUID().toString(),
                employeeId,
                command.legalEntityId(),
                command.employeeNumber().trim(),
                command.displayName().trim(),
                "ACTIVE",
                command.externalEmployeeId(),
                command.effectiveFrom(),
                null,
                "LOCAL",
                null,
                0,
                command.reason().trim(),
                actor,
                now);
        repository.saveEmployeeVersion(version);
        saveIdempotency(
                actor, "EMPLOYEE_CREATE", idempotencyKey,
                requestDigest, employeeId, now);
        auditService.record(
                actor, "EMPLOYEE_VERSION_CREATED", "EMPLOYEE", employeeId,
                "SUCCESS", command.reason(), null, digest(version));
        return employeeDetail(version, null, CapabilityCodes.EMPLOYEE_CREATE);
    }

    @Transactional
    public EmployeeDetail updateEmployee(
            String employeeId,
            UpdateEmployee command,
            long expectedVersion,
            String idempotencyKey) {
        validateEmployee(
                command.employeeNumber(), command.displayName(), command.status(),
                command.effectiveFrom(), command.effectiveTo(), command.reason());
        requireIdempotencyKey(idempotencyKey);
        EmployeeVersion current = requireEmployee(
                employeeId, CapabilityCodes.EMPLOYEE_EDIT, null);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(employeeId, command));
        IdempotencyRecord existing = existing(
                actor, "EMPLOYEE_EDIT", idempotencyKey, requestDigest);
        if (existing != null) {
            return employeeDetail(
                    repository.findCurrentEmployee(employeeId)
                            .orElseThrow(ResourceNotAvailableAccessDeniedException::new),
                    null,
                    CapabilityCodes.EMPLOYEE_EDIT);
        }
        requireExpected(current.rowVersion(), expectedVersion);
        repository.lockLegalEntity(current.legalEntityId());
        repository.lockEmployee(employeeId);
        current = repository.findCurrentEmployee(employeeId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireExpected(current.rowVersion(), expectedVersion);
        requireNextVersionDate(
                current.effectiveFrom(), current.effectiveTo(), command.effectiveFrom());
        if (repository.employeeNumberExists(
                current.legalEntityId(), command.employeeNumber().trim(), employeeId)) {
            throw conflict("EMPLOYEE_NUMBER_CONFLICT", "员工编号已经存在");
        }
        Instant now = clock.instant();
        repository.closeCurrentEmployeeVersion(
                employeeId, command.effectiveFrom(), expectedVersion);
        repository.updateEmployeeIdentity(
                employeeId,
                command.employeeNumber().trim(),
                command.displayName().trim(),
                command.status(),
                expectedVersion,
                now);
        EmployeeVersion version = new EmployeeVersion(
                UUID.randomUUID().toString(),
                employeeId,
                current.legalEntityId(),
                command.employeeNumber().trim(),
                command.displayName().trim(),
                command.status(),
                current.externalEmployeeId(),
                command.effectiveFrom(),
                command.effectiveTo(),
                "LOCAL",
                null,
                expectedVersion + 1,
                command.reason().trim(),
                actor,
                now);
        repository.saveEmployeeVersion(version);
        saveIdempotency(
                actor, "EMPLOYEE_EDIT", idempotencyKey,
                requestDigest, employeeId, now);
        auditService.record(
                actor, "EMPLOYEE_VERSION_CREATED", "EMPLOYEE", employeeId,
                "SUCCESS", command.reason(), digest(current), digest(version));
        return employeeDetail(version, null, CapabilityCodes.EMPLOYEE_EDIT);
    }

    @Transactional(readOnly = true)
    public EmployeeDetail getEmployee(String employeeId, LocalDate asOf) {
        EmployeeVersion employee = requireEmployee(
                employeeId, CapabilityCodes.EMPLOYEE_READ, asOf);
        return employeeDetail(employee, asOf, CapabilityCodes.EMPLOYEE_READ);
    }

    @Transactional(readOnly = true)
    public PeoplePage<EmployeeVersion> listEmployeeVersions(
            String employeeId, int page, int size) {
        validatePage(page, size);
        requireEmployee(
                employeeId, CapabilityCodes.EMPLOYEE_READ, null);
        return new PeoplePage<>(
                repository.listEmployeeVersions(employeeId, size, page * size),
                repository.countEmployeeVersions(employeeId),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public PeoplePage<EmploymentPeriod> listEmploymentPeriods(
            String employeeId, LocalDate asOf, int page, int size) {
        validatePage(page, size);
        requireEmployeeForHistory(employeeId, CapabilityCodes.EMPLOYMENT_READ);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        return new PeoplePage<>(
                repository.listAccessibleEmploymentPeriods(
                        employeeId,
                        asOf,
                        principalId,
                        CapabilityCodes.EMPLOYMENT_READ,
                        at,
                        size,
                        page * size),
                repository.countAccessibleEmploymentPeriods(
                        employeeId,
                        asOf,
                        principalId,
                        CapabilityCodes.EMPLOYMENT_READ,
                        at),
                page,
                size);
    }

    @Transactional
    public EmploymentMutation createEmployment(
            String employeeId,
            CreateEmployment command,
            long expectedVersion,
            String idempotencyKey) {
        validateEmployment(
                command.startDate(), command.terminationDate(), command.reason());
        requireIdempotencyKey(idempotencyKey);
        EmployeeVersion employee = requireEmployeeForHistory(
                employeeId, CapabilityCodes.EMPLOYMENT_CREATE);
        OrganizationVersion organization = requireOrganization(
                command.organizationId(), CapabilityCodes.EMPLOYMENT_CREATE, null);
        if (!organization.legalEntityId().equals(employee.legalEntityId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(employeeId, command));
        IdempotencyRecord existing = existing(
                actor, "EMPLOYMENT_CREATE", idempotencyKey, requestDigest);
        if (existing != null) {
            EmploymentPeriod period = repository
                    .findCurrentEmploymentPeriodVersion(existing.resourceId())
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            return new EmploymentMutation(
                    period, repository.findEmployeeAggregateVersion(employeeId));
        }
        requireExpected(employee.rowVersion(), expectedVersion);
        Instant now = clock.instant();
        repository.lockLegalEntity(employee.legalEntityId());
        repository.lockEmployee(employeeId);
        assertActiveOrganizationInLegalEntity(
                command.organizationId(), employee.legalEntityId());
        LocalDate endExclusive = command.terminationDate() == null
                ? null
                : command.terminationDate().plusDays(1);
        if (repository.hasEmploymentOverlap(
                employeeId, command.startDate(), endExclusive, null)) {
            throw conflict("EMPLOYMENT_PERIOD_OVERLAP", "任职周期与现有周期重叠");
        }
        String employmentPeriodId = UUID.randomUUID().toString();
        EmploymentPeriod period = new EmploymentPeriod(
                employmentPeriodId,
                UUID.randomUUID().toString(),
                employeeId,
                command.organizationId(),
                command.positionId(),
                command.startDate(),
                command.terminationDate(),
                endExclusive,
                "ACTIVE",
                null,
                0,
                command.reason().trim(),
                actor,
                now);
        repository.saveEmploymentPeriodVersion(period, true);
        repository.touchEmployee(employeeId, expectedVersion, now);
        saveIdempotency(
                actor, "EMPLOYMENT_CREATE", idempotencyKey,
                requestDigest, employmentPeriodId, now);
        auditService.record(
                actor, "EMPLOYMENT_PERIOD_CREATED", "EMPLOYEE", employeeId,
                "SUCCESS", command.reason(), null, digest(period));
        return new EmploymentMutation(period, expectedVersion + 1);
    }

    @Transactional
    public EmploymentMutation updateEmployment(
            String employeeId,
            String employmentPeriodId,
            UpdateEmployment command,
            long expectedVersion,
            String idempotencyKey) {
        validateEmployment(
                command.startDate(), command.terminationDate(), command.reason());
        requireIdempotencyKey(idempotencyKey);
        EmployeeVersion employee =
                requireEmployeeForHistory(employeeId, CapabilityCodes.EMPLOYMENT_EDIT);
        OrganizationVersion organization =
                requireOrganization(command.organizationId(), CapabilityCodes.EMPLOYMENT_EDIT, null);
        if (!organization.legalEntityId().equals(employee.legalEntityId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        EmploymentPeriod current = repository.findCurrentEmploymentPeriodVersion(
                employmentPeriodId).orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!current.employeeId().equals(employeeId)) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        requireOrganization(
                current.organizationId(), CapabilityCodes.EMPLOYMENT_EDIT, null);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(employeeId, employmentPeriodId, command));
        IdempotencyRecord existing = existing(
                actor, "EMPLOYMENT_EDIT", idempotencyKey, requestDigest);
        if (existing != null) {
            EmploymentPeriod period = repository
                    .findCurrentEmploymentPeriodVersion(employmentPeriodId)
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            return new EmploymentMutation(
                    period, repository.findEmployeeAggregateVersion(employeeId));
        }
        requireExpected(current.rowVersion(), expectedVersion);
        LocalDate endExclusive = command.terminationDate() == null
                ? null
                : command.terminationDate().plusDays(1);
        repository.lockLegalEntity(employee.legalEntityId());
        repository.lockEmployee(employeeId);
        assertActiveOrganizationInLegalEntity(
                command.organizationId(), employee.legalEntityId());
        if (repository.hasEmploymentOverlap(
                employeeId, command.startDate(), endExclusive, employmentPeriodId)) {
            throw conflict("EMPLOYMENT_PERIOD_OVERLAP", "任职周期与现有周期重叠");
        }
        Instant now = clock.instant();
        repository.closeEmploymentPeriodVersion(employmentPeriodId, current.rowVersion());
        EmploymentPeriod version = new EmploymentPeriod(
                employmentPeriodId,
                UUID.randomUUID().toString(),
                employeeId,
                command.organizationId(),
                command.positionId(),
                command.startDate(),
                command.terminationDate(),
                endExclusive,
                "ACTIVE",
                null,
                current.rowVersion() + 1,
                command.reason().trim(),
                actor,
                now);
        repository.saveEmploymentPeriodVersion(version, false);
        repository.touchEmployee(employeeId, employee.rowVersion(), now);
        saveIdempotency(
                actor, "EMPLOYMENT_EDIT", idempotencyKey,
                requestDigest, employmentPeriodId, now);
        auditService.record(
                actor, "EMPLOYMENT_PERIOD_VERSION_CREATED", "EMPLOYEE", employeeId,
                "SUCCESS", command.reason(), digest(current), digest(version));
        return new EmploymentMutation(version, employee.rowVersion() + 1);
    }

    @Transactional(readOnly = true)
    public PriorServicePage listPriorService(
            String employeeId, int page, int size) {
        validatePage(page, size);
        requireEmployeeForHistory(employeeId, CapabilityCodes.PRIOR_SERVICE_READ);
        List<PriorServiceRecord> all = repository.listAllPriorServiceRecords(employeeId);
        PriorServiceReplay replay = replay(employeeId, all);
        return new PriorServicePage(
                repository.listPriorServiceRecords(employeeId, size, page * size),
                replay.totalDays(),
                replay.replayDigest(),
                repository.countPriorServiceRecords(employeeId),
                page,
                size);
    }

    @Transactional
    public PriorServiceMutation adjustPriorService(
            String employeeId,
            AdjustPriorService command,
            long expectedVersion,
            String idempotencyKey) {
        requireReason(command.reason());
        requireIdempotencyKey(idempotencyKey);
        if (command.businessDate() == null
                || command.amountDays() < -36500
                || command.amountDays() > 36500) {
            throw invalid("累计工龄发生额或业务日期无效");
        }
        EmployeeVersion employee = requireEmployeeForHistory(
                employeeId, CapabilityCodes.PRIOR_SERVICE_ADJUST);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(employeeId, command));
        IdempotencyRecord existing = existing(
                actor, "PRIOR_SERVICE_ADJUST", idempotencyKey, requestDigest);
        if (existing != null) {
            PriorServiceRecord record = repository.listAllPriorServiceRecords(employeeId).stream()
                    .filter(item -> item.priorServiceRecordId().equals(existing.resourceId()))
                    .findFirst()
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            return new PriorServiceMutation(
                    record, repository.findEmployeeAggregateVersion(employeeId));
        }
        requireExpected(employee.rowVersion(), expectedVersion);
        repository.lockEmployee(employeeId);
        List<PriorServiceRecord> records = repository.listAllPriorServiceRecords(employeeId);
        if (!records.isEmpty()
                && command.businessDate().isBefore(
                        records.get(records.size() - 1).businessDate())) {
            throw conflict(
                    "PRIOR_SERVICE_BUSINESS_DATE_OUT_OF_ORDER",
                    "累计工龄业务日期不得早于最新发生额");
        }
        int previousTotal = records.isEmpty()
                ? 0
                : records.get(records.size() - 1).resultingTotalDays();
        int resultingTotal = Math.addExact(previousTotal, command.amountDays());
        if (resultingTotal < 0) {
            throw invalid("累计工龄不能小于零");
        }
        Instant now = clock.instant();
        String recordId = UUID.randomUUID().toString();
        PriorServiceRecord record = new PriorServiceRecord(
                recordId,
                employeeId,
                "ADJUSTMENT",
                command.amountDays(),
                command.reason().trim(),
                command.businessDate(),
                null,
                null,
                resultingTotal,
                actor,
                now,
                idempotencyKey,
                records.size());
        repository.savePriorServiceRecord(record);
        repository.touchEmployee(employeeId, expectedVersion, now);
        saveIdempotency(
                actor, "PRIOR_SERVICE_ADJUST", idempotencyKey,
                requestDigest, recordId, now);
        auditService.record(
                actor, "PRIOR_SERVICE_ADJUSTED", "EMPLOYEE", employeeId,
                "SUCCESS", command.reason(), digest(previousTotal), digest(resultingTotal));
        return new PriorServiceMutation(record, expectedVersion + 1);
    }

    @Transactional
    public PriorServiceMutationReplay recalculatePriorService(
            String employeeId,
            String reason,
            long expectedVersion,
            String idempotencyKey) {
        requireReason(reason);
        requireIdempotencyKey(idempotencyKey);
        EmployeeVersion employee = requireEmployeeForHistory(
                employeeId, CapabilityCodes.PRIOR_SERVICE_ADJUST);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(employeeId, reason));
        IdempotencyRecord existing = existing(
                actor, "PRIOR_SERVICE_RECALCULATE", idempotencyKey, requestDigest);
        if (existing != null) {
            return new PriorServiceMutationReplay(
                    replay(employeeId),
                    repository.findEmployeeAggregateVersion(employeeId));
        }
        requireExpected(employee.rowVersion(), expectedVersion);
        repository.lockEmployee(employeeId);
        requireExpected(
                repository.findEmployeeAggregateVersion(employeeId), expectedVersion);
        PriorServiceReplay replay = replay(employeeId);
        Instant now = clock.instant();
        saveIdempotency(
                actor, "PRIOR_SERVICE_RECALCULATE", idempotencyKey,
                requestDigest, employeeId, now);
        auditService.record(
                actor, "PRIOR_SERVICE_RECALCULATED", "EMPLOYEE", employeeId,
                "SUCCESS", reason, null, replay.replayDigest());
        return new PriorServiceMutationReplay(replay, expectedVersion);
    }

    private OrganizationVersion requireOrganization(
            String organizationId, String capability, LocalDate asOf) {
        capabilityService.require(capability);
        OrganizationVersion organization = (asOf == null
                ? repository.findCurrentOrganization(organizationId)
                : repository.findOrganizationAsOf(organizationId, asOf))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!repository.canAccessOrganization(
                principalProvider.currentPrincipalId(), capability,
                organizationId, clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return organization;
    }

    private void assertActiveOrganizationInLegalEntity(
            String organizationId, String legalEntityId) {
        OrganizationVersion current = repository.findCurrentOrganization(organizationId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!current.legalEntityId().equals(legalEntityId)
                || !"ACTIVE".equals(current.status())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private EmployeeVersion requireEmployee(
            String employeeId, String capability, LocalDate asOf) {
        capabilityService.require(capability);
        EmployeeVersion employee = (asOf == null
                ? repository.findCurrentEmployee(employeeId)
                : repository.findEmployeeAsOf(employeeId, asOf))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        LocalDate scopeDate = asOf == null ? LocalDate.now(clock) : asOf;
        if (!repository.canAccessEmployee(
                principalProvider.currentPrincipalId(), capability, employeeId,
                scopeDate, clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return employee;
    }

    private EmployeeVersion requireEmployeeForHistory(
            String employeeId, String capability) {
        capabilityService.require(capability);
        EmployeeVersion employee = repository.findCurrentEmployee(employeeId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!repository.canAccessLegalEntity(
                principalProvider.currentPrincipalId(), capability,
                employee.legalEntityId(), clock.instant())
                && !repository.canAccessEmployee(
                        principalProvider.currentPrincipalId(), capability, employeeId,
                        LocalDate.now(clock), clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return employee;
    }

    private void requireLegalEntity(String capability, String legalEntityId) {
        capabilityService.require(capability);
        if (!repository.legalEntityExists(legalEntityId)
                || !repository.canAccessLegalEntity(
                        principalProvider.currentPrincipalId(), capability,
                        legalEntityId, clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private PriorServiceReplay replay(String employeeId) {
        return replay(employeeId, repository.listAllPriorServiceRecords(employeeId));
    }

    private EmployeeDetail employeeDetail(
            EmployeeVersion employee, LocalDate asOf, String capability) {
        List<EmploymentPeriod> periods = repository.listAccessibleEmploymentPeriods(
                employee.employeeId(),
                asOf,
                principalProvider.currentPrincipalId(),
                capability,
                clock.instant(),
                100,
                0);
        return new EmployeeDetail(employee, periods, replay(employee.employeeId()));
    }

    private PriorServiceReplay replay(
            String employeeId, List<PriorServiceRecord> records) {
        int total = 0;
        for (PriorServiceRecord record : records) {
            total = Math.addExact(total, record.amountDays());
            if (total < 0 || total != record.resultingTotalDays()) {
                throw new IllegalStateException("prior service replay invariant violated");
            }
        }
        return new PriorServiceReplay(
                employeeId,
                total,
                records.size(),
                digest(records.stream().map(record -> List.of(
                        record.priorServiceRecordId(),
                        record.recordType(),
                        record.amountDays(),
                        record.businessDate(),
                        record.resultingTotalDays(),
                        record.actorId(),
                        record.occurredAt())).toList()),
                clock.instant());
    }

    private IdempotencyRecord existing(
            String actor, String action, String key, String requestDigest) {
        IdempotencyRecord record = repository.findIdempotency(actor, action, key).orElse(null);
        if (record != null && !record.requestDigest().equals(requestDigest)) {
            throw conflict(
                    "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                    "幂等键已用于不同请求");
        }
        return record;
    }

    private void saveIdempotency(
            String actor, String action, String key, String requestDigest,
            String resourceId, Instant at) {
        repository.saveIdempotency(
                UUID.randomUUID().toString(), actor, action, key,
                requestDigest, resourceId, null, at);
    }

    private static void validateOrganization(
            String code, String name, String type, String status,
            LocalDate effectiveFrom, LocalDate effectiveTo, String reason) {
        requireText(code, 128, "组织编码");
        requireText(name, 200, "组织名称");
        if (!List.of("COMPANY", "DEPARTMENT", "TEAM").contains(type)) {
            throw invalid("组织类型无效");
        }
        if (!List.of("ACTIVE", "INACTIVE").contains(status)) {
            throw invalid("组织状态无效");
        }
        validatePeriod(effectiveFrom, effectiveTo);
        requireReason(reason);
    }

    private static void validateEmployee(
            String employeeNumber, String name, String status,
            LocalDate effectiveFrom, LocalDate effectiveTo, String reason) {
        requireText(employeeNumber, 128, "员工编号");
        requireText(name, 100, "姓名");
        if (!List.of("ACTIVE", "INACTIVE", "TERMINATED").contains(status)) {
            throw invalid("员工状态无效");
        }
        validatePeriod(effectiveFrom, effectiveTo);
        requireReason(reason);
    }

    private static void validateEmployment(
            LocalDate start, LocalDate termination, String reason) {
        if (start == null || termination != null && termination.isBefore(start)) {
            throw invalid("离职日不得早于任职开始日");
        }
        requireReason(reason);
    }

    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || to != null && !to.isAfter(from)) {
            throw invalid("版本结束日期必须晚于生效日期");
        }
    }

    private static void requireNextVersionDate(
            LocalDate currentFrom, LocalDate currentTo, LocalDate nextFrom) {
        if (!nextFrom.isAfter(currentFrom)
                || currentTo != null && nextFrom.isBefore(currentTo)) {
            throw conflict(
                    "STALE_VERSION",
                    "新版本生效日期必须晚于当前版本且不得消除既有版本空档");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 2 || reason.length() > 500) {
            throw invalid("reason 长度必须为 2 至 500");
        }
    }

    private static void requireText(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(label + "不能为空且长度不能超过 " + maxLength);
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("page 必须非负且 size 必须为 1 至 100");
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (!IdempotencyKeyPolicy.isValid(key)) {
            throw invalid("Idempotency-Key 必须为 16 至 128 位字母、数字或 ._:-");
        }
    }

    private static void requireExpected(long actual, long expected) {
        if (actual != expected) {
            throw conflict("STALE_VERSION", "If-Match 版本已过期");
        }
    }

    private String digest(Object value) {
        try {
            return tokenService.digest(objectMapper.writeValueAsString(value));
        } catch (Exception exception) {
            throw new IllegalStateException("people value must be JSON serializable", exception);
        }
    }

    private static ApiProblemException invalid(String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static ApiProblemException conflict(String code, String message) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, message);
    }

    public record EmployeeDetail(
            EmployeeVersion employee,
            List<EmploymentPeriod> employmentPeriods,
            PriorServiceReplay priorService) {
    }

    public record PriorServiceMutation(
            PriorServiceRecord record,
            long aggregateVersion) {
    }

    public record EmploymentMutation(
            EmploymentPeriod period,
            long aggregateVersion) {
    }

    public record PriorServiceMutationReplay(
            PriorServiceReplay replay,
            long aggregateVersion) {
    }

    public record PriorServicePage(
            List<PriorServiceRecord> items,
            int totalDays,
            String replayDigest,
            long total,
            int page,
            int size) {
    }
}
