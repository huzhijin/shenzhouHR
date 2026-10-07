package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.application.AttendancePolicyLifecycleRepository.Scope;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.Page;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ParameterValue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ScopedPolicyVersion;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ScopedVersionStatus;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ValidationIssue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ValidationResult;
import com.szsemicon.hr.attendance.domain.AttendancePolicyParameterValidator;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import com.szsemicon.hr.shared.web.StrongEtag;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AttendancePolicyLifecycleFacade {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository peopleRepository;
    private final AttendancePolicyLifecycleRepository repository;
    private final AttendanceSetupIdempotencyService idempotencyService;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttendancePolicyLifecycleFacade(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository peopleRepository,
            AttendancePolicyLifecycleRepository repository,
            AttendanceSetupIdempotencyService idempotencyService,
            AuditService auditService,
            SecurityTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.peopleRepository = peopleRepository;
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ScopedPolicyVersion> listVersions(
            String templateId, String companyId, int page, int size) {
        requirePage(page, size);
        requireScope(
                templateId, companyId, CapabilityCodes.ATTENDANCE_SETUP_READ, false);
        return repository.list(templateId, companyId, page, size);
    }

    @Transactional(readOnly = true)
    public ScopedPolicyVersion getVersion(
            String templateId, String versionId, String companyId) {
        requireScope(
                templateId, companyId, CapabilityCodes.ATTENDANCE_SETUP_READ, false);
        return requireVersion(templateId, versionId, companyId);
    }

    @Transactional(readOnly = true)
    public ScopedPolicyVersion getVersionContext(String versionId) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        ScopedPolicyVersion version = repository.findByScopedVersionId(versionId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        String actorId = principalProvider.currentPrincipalId();
        if (!peopleRepository.canAccessCompany(
                actorId,
                CapabilityCodes.ATTENDANCE_SETUP_READ,
                version.companyId(),
                clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return version;
    }

    @Transactional
    public ScopedPolicyVersion createDraft(
            String templateId,
            String companyId,
            String basedOnVersionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason,
            String idempotencyKey,
            String requestId) {
        requirePeriod(effectiveFrom, effectiveTo);
        requireReason(reason);
        Scope scope = requireScope(
                templateId,
                companyId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                false);
        ScopedPolicyVersion base = basedOnVersionId == null
                ? null
                : requireVersion(templateId, basedOnVersionId, companyId);
        String actorId = principalProvider.currentPrincipalId();
        return idempotencyService.execute(
                actorId,
                "ATTENDANCE_POLICY_DRAFT_CREATE",
                "ATTENDANCE_POLICY_SCOPE",
                scope.scopeId(),
                idempotencyKey,
                new DraftIdempotencyRequest(
                        companyId,
                        basedOnVersionId,
                        effectiveFrom,
                        effectiveTo,
                        reason),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        false),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        true),
                201,
                ScopedPolicyVersion::scopedVersionId,
                version -> lifecycleHeaders(version.rowVersion()),
                ScopedPolicyVersion.class,
                () -> {
                    Scope locked = requireScope(
                            templateId,
                            companyId,
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                            false);
                    Instant now = clock.instant();
                    String versionId = UUID.randomUUID().toString();
                    ScopedPolicyVersion draft = new ScopedPolicyVersion(
                            versionId,
                            locked.scopeId(),
                            locked.templateId(),
                            locked.companyId(),
                            com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind
                                    .valueOf(locked.policyKind()),
                            repository.nextVersionNumber(locked.scopeId()),
                            ScopedVersionStatus.DRAFT,
                            base == null ? List.of() : base.parameters(),
                            effectiveFrom,
                            effectiveTo,
                            reason.trim(),
                            new ValidationResult(false, List.of(), null),
                            "",
                            "",
                            null,
                            0,
                            actorId,
                            now,
                            null,
                            actorId,
                            now,
                            null);
                    String snapshot = snapshot(draft);
                    draft = withSnapshot(draft, snapshot, tokenService.digest(snapshot));
                    repository.insert(draft);
                    repository.appendLifecycle(
                            UUID.randomUUID().toString(),
                            locked.scopeId(),
                            versionId,
                            "DRAFT_CREATED",
                            effectiveFrom,
                            reason.trim(),
                            actorId,
                            requestId(requestId),
                            now);
                    audit(actorId, "ATTENDANCE_POLICY_DRAFT_CREATED", versionId, reason);
                    return requireVersion(templateId, versionId, companyId);
                });
    }

    @Transactional
    public ScopedPolicyVersion updateDraft(
            String templateId,
            String versionId,
            String companyId,
            List<ParameterValue> parameters,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason,
            long expectedVersion,
            String idempotencyKey,
            String requestId) {
        requirePeriod(effectiveFrom, effectiveTo);
        requireReason(reason);
        Scope scope = requireScope(
                templateId,
                companyId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                false);
        String actorId = principalProvider.currentPrincipalId();
        return idempotencyService.execute(
                actorId,
                "ATTENDANCE_POLICY_DRAFT_UPDATE",
                "ATTENDANCE_POLICY_SCOPED_VERSION",
                versionId,
                idempotencyKey,
                new UpdateIdempotencyRequest(
                        companyId,
                        parameters,
                        effectiveFrom,
                        effectiveTo,
                        reason,
                        expectedVersion),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        false),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        true),
                200,
                ScopedPolicyVersion::scopedVersionId,
                version -> lifecycleHeaders(version.rowVersion()),
                ScopedPolicyVersion.class,
                () -> {
                    requireScope(
                            scope.templateId(),
                            scope.companyId(),
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                            false);
                    ScopedPolicyVersion current =
                            requireVersion(templateId, versionId, companyId);
                    requireVersion(current.rowVersion(), expectedVersion);
                    if (current.status() == ScopedVersionStatus.PUBLISHED) {
                        throw conflict(
                                "PUBLISHED_POLICY_IMMUTABLE",
                                "已发布考勤策略内容不可修改");
                    }
                    int successorVersionNumber =
                            repository.nextVersionNumber(current.scopeId());
                    if (current.versionNumber() != successorVersionNumber - 1) {
                        throw stale();
                    }
                    Instant now = clock.instant();
                    String successorId = UUID.randomUUID().toString();
                    ScopedPolicyVersion candidate = new ScopedPolicyVersion(
                            successorId,
                            current.scopeId(),
                            current.templateId(),
                            current.companyId(),
                            current.policyKind(),
                            successorVersionNumber,
                            ScopedVersionStatus.DRAFT,
                            parameters,
                            effectiveFrom,
                            effectiveTo,
                            reason.trim(),
                            new ValidationResult(false, List.of(), null),
                            "",
                            "",
                            null,
                            0,
                            actorId,
                            now,
                            null,
                            actorId,
                            now,
                            null);
                    String snapshot = snapshot(candidate);
                    candidate = withSnapshot(
                            candidate, snapshot, tokenService.digest(snapshot));
                    repository.insert(candidate);
                    repository.appendLifecycle(
                            UUID.randomUUID().toString(),
                            current.scopeId(),
                            successorId,
                            "DRAFT_CREATED",
                            effectiveFrom,
                            reason.trim(),
                            actorId,
                            requestId(requestId),
                            now);
                    audit(actorId, "ATTENDANCE_POLICY_DRAFT_UPDATED", successorId, reason);
                    return requireVersion(templateId, successorId, companyId);
                });
    }

    @Transactional
    public ValidationResult validate(
            String templateId,
            String versionId,
            String companyId,
            long expectedVersion,
            String reason,
            String idempotencyKey,
            String requestId) {
        requireReason(reason);
        Scope scope = requireScope(
                templateId,
                companyId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                false);
        String actorId = principalProvider.currentPrincipalId();
        return idempotencyService.execute(
                actorId,
                "ATTENDANCE_POLICY_VALIDATE",
                "ATTENDANCE_POLICY_SCOPED_VERSION",
                versionId,
                idempotencyKey,
                new TransitionIdempotencyRequest(
                        companyId, reason, expectedVersion, null, null),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        false),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        true),
                200,
                ignored -> versionId,
                result -> lifecycleHeaders(result.valid()
                        ? Math.addExact(expectedVersion, 1)
                        : expectedVersion),
                ValidationResult.class,
                () -> {
                    requireScope(
                            scope.templateId(),
                            scope.companyId(),
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                            false);
                    ScopedPolicyVersion current =
                            requireVersion(templateId, versionId, companyId);
                    requireVersion(current.rowVersion(), expectedVersion);
                    if (current.status() == ScopedVersionStatus.PUBLISHED) {
                        throw conflict(
                                "PUBLISHED_POLICY_IMMUTABLE",
                                "已发布考勤策略不可重新校验");
                    }
                    ValidationResult result = validate(current);
                    if (result.valid()) {
                        repository.appendLifecycle(
                                UUID.randomUUID().toString(),
                                current.scopeId(),
                                versionId,
                                "VALIDATED",
                                current.effectiveFrom(),
                                reason.trim(),
                                actorId,
                                requestId(requestId),
                                clock.instant());
                    }
                    audit(actorId, "ATTENDANCE_POLICY_VALIDATED", versionId, reason);
                    return result;
                });
    }

    @Transactional
    public ScopedPolicyVersion publish(
            String templateId,
            String versionId,
            String companyId,
            String reason,
            long expectedVersion,
            String idempotencyKey,
            String requestId) {
        requireReason(reason);
        Scope scope = requireScope(
                templateId,
                companyId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                false);
        String actorId = principalProvider.currentPrincipalId();
        return idempotencyService.execute(
                actorId,
                "ATTENDANCE_POLICY_PUBLISH",
                "ATTENDANCE_POLICY_SCOPED_VERSION",
                versionId,
                idempotencyKey,
                new TransitionIdempotencyRequest(
                        companyId, reason, expectedVersion, null, null),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        false),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        true),
                200,
                ScopedPolicyVersion::scopedVersionId,
                version -> lifecycleHeaders(version.rowVersion()),
                ScopedPolicyVersion.class,
                () -> {
                    Scope locked = requireScope(
                            scope.templateId(),
                            scope.companyId(),
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                            false);
                    ScopedPolicyVersion current =
                            requireVersion(templateId, versionId, companyId);
                    requireVersion(current.rowVersion(), expectedVersion);
                    requireFutureChange(current.effectiveFrom());
                    if (current.status() != ScopedVersionStatus.VALIDATED) {
                        throw conflict(
                                "POLICY_VALIDATION_REQUIRED",
                                "发布前必须完成有效校验");
                    }
                    requireAppendOnlyPublicationBoundary(
                            locked.scopeId(), current.effectiveFrom());
                    Instant now = clock.instant();
                    List<String> publishedAtBoundary =
                            repository.findPublishedAt(
                                    locked.scopeId(), current.effectiveFrom());
                    if (publishedAtBoundary.size() > 1) {
                        throw conflict(
                                "POLICY_AMBIGUOUS",
                                "发布边界解析出多个现行考勤策略版本");
                    }
                    String currentRequestId = requestId(requestId);
                    if (!publishedAtBoundary.isEmpty()) {
                        String predecessorId = publishedAtBoundary.getFirst();
                        if (predecessorId.equals(versionId)) {
                            throw conflict(
                                    "POLICY_ALREADY_PUBLISHED",
                                    "考勤策略版本已经发布");
                        }
                        repository.appendLifecycle(
                                UUID.randomUUID().toString(),
                                locked.scopeId(),
                                predecessorId,
                                "DEACTIVATE_SCHEDULED",
                                current.effectiveFrom(),
                                reason.trim(),
                                actorId,
                                currentRequestId,
                                now);
                    }
                    repository.appendLifecycle(
                            UUID.randomUUID().toString(),
                            locked.scopeId(),
                            versionId,
                            "PUBLISHED",
                            current.effectiveFrom(),
                            reason.trim(),
                            actorId,
                            currentRequestId,
                            now);
                    audit(actorId, "ATTENDANCE_POLICY_PUBLISHED", versionId, reason);
                    return requireVersion(templateId, versionId, companyId);
                });
    }

    @Transactional
    public ScopedPolicyVersion deactivate(
            String templateId,
            String versionId,
            String companyId,
            LocalDate effectiveFrom,
            String reason,
            long expectedVersion,
            String idempotencyKey,
            String requestId) {
        requireReason(reason);
        Scope scope = requireScope(
                templateId,
                companyId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                false);
        String actorId = principalProvider.currentPrincipalId();
        return idempotencyService.execute(
                actorId,
                "ATTENDANCE_POLICY_DEACTIVATE",
                "ATTENDANCE_POLICY_SCOPED_VERSION",
                versionId,
                idempotencyKey,
                new TransitionIdempotencyRequest(
                        companyId, reason, expectedVersion, null, effectiveFrom),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        false),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        true),
                200,
                ScopedPolicyVersion::scopedVersionId,
                version -> lifecycleHeaders(version.rowVersion()),
                ScopedPolicyVersion.class,
                () -> {
                    Scope locked = requireScope(
                            scope.templateId(),
                            scope.companyId(),
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                            false);
                    ScopedPolicyVersion current =
                            requireVersion(templateId, versionId, companyId);
                    requireVersion(current.rowVersion(), expectedVersion);
                    requireFutureChange(effectiveFrom);
                    if (current.status() != ScopedVersionStatus.PUBLISHED) {
                        throw conflict(
                                "POLICY_NOT_PUBLISHED",
                                "只有已发布考勤策略可安排停用");
                    }
                    if (current.deactivationEffectiveFrom() != null) {
                        throw conflict(
                                "POLICY_DEACTIVATION_ALREADY_SCHEDULED",
                                "考勤策略已安排停用");
                    }
                    requireDeactivationBoundary(current, effectiveFrom);
                    repository.appendLifecycle(
                            UUID.randomUUID().toString(),
                            locked.scopeId(),
                            versionId,
                            "DEACTIVATE_SCHEDULED",
                            effectiveFrom,
                            reason.trim(),
                            actorId,
                            requestId(requestId),
                            clock.instant());
                    audit(actorId, "ATTENDANCE_POLICY_DEACTIVATION_SCHEDULED",
                            versionId, reason);
                    return requireVersion(templateId, versionId, companyId);
                });
    }

    @Transactional
    public ScopedPolicyVersion rollback(
            String templateId,
            String sourceVersionId,
            String companyId,
            String targetVersionId,
            LocalDate effectiveFrom,
            String reason,
            long expectedVersion,
            String idempotencyKey,
            String requestId) {
        requireReason(reason);
        Scope scope = requireScope(
                templateId,
                companyId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                false);
        String actorId = principalProvider.currentPrincipalId();
        return idempotencyService.execute(
                actorId,
                "ATTENDANCE_POLICY_ROLLBACK",
                "ATTENDANCE_POLICY_SCOPED_VERSION",
                sourceVersionId,
                idempotencyKey,
                new TransitionIdempotencyRequest(
                        companyId,
                        reason,
                        expectedVersion,
                        targetVersionId,
                        effectiveFrom),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        false),
                () -> requireScope(
                        templateId,
                        companyId,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                        true),
                201,
                ScopedPolicyVersion::scopedVersionId,
                version -> lifecycleHeaders(version.rowVersion()),
                ScopedPolicyVersion.class,
                () -> {
                    Scope locked = requireScope(
                            scope.templateId(),
                            scope.companyId(),
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY,
                            false);
                    ScopedPolicyVersion source =
                            requireVersion(templateId, sourceVersionId, companyId);
                    requireVersion(source.rowVersion(), expectedVersion);
                    requireFutureChange(effectiveFrom);
                    requireRollbackBoundary(source, effectiveFrom);
                    if (sourceVersionId.equals(targetVersionId)) {
                        throw conflict(
                                "POLICY_ROLLBACK_TARGET_INVALID",
                                "回滚目标不能与当前版本相同");
                    }
                    ScopedPolicyVersion target =
                            requireVersion(templateId, targetVersionId, companyId);
                    if (source.status() != ScopedVersionStatus.PUBLISHED
                            || target.status() != ScopedVersionStatus.PUBLISHED) {
                        throw conflict(
                                "POLICY_ROLLBACK_VERSION_INVALID",
                                "回滚源与目标必须都是已发布版本");
                    }
                    requireLatestPublishedSource(
                            locked.scopeId(), sourceVersionId);
                    if (!validate(target).valid()) {
                        throw conflict(
                                "POLICY_ROLLBACK_TARGET_INVALID",
                                "回滚目标参数不符合受控策略目录");
                    }
                    requireAppendOnlyPublicationBoundary(
                            locked.scopeId(), effectiveFrom);
                    Instant now = clock.instant();
                    String createdVersionId = UUID.randomUUID().toString();
                    ScopedPolicyVersion created = new ScopedPolicyVersion(
                            createdVersionId,
                            locked.scopeId(),
                            locked.templateId(),
                            locked.companyId(),
                            target.policyKind(),
                            repository.nextVersionNumber(locked.scopeId()),
                            ScopedVersionStatus.PUBLISHED,
                            target.parameters(),
                            effectiveFrom,
                            null,
                            reason.trim(),
                            new ValidationResult(true, List.of(), now),
                            null,
                            null,
                            target.scopedVersionId(),
                            0,
                            actorId,
                            now,
                            now,
                            actorId,
                            now,
                            null);
                    String snapshot = snapshot(created);
                    created = new ScopedPolicyVersion(
                            created.scopedVersionId(),
                            created.scopeId(),
                            created.templateId(),
                            created.companyId(),
                            created.policyKind(),
                            created.versionNumber(),
                            created.status(),
                            created.parameters(),
                            created.effectiveFrom(),
                            created.effectiveTo(),
                            created.changeReason(),
                            created.validation(),
                            snapshot,
                            tokenService.digest(snapshot),
                            created.rollbackOfScopedVersionId(),
                            created.rowVersion(),
                            created.createdBy(),
                            created.createdAt(),
                            created.publishedAt(),
                            created.updatedBy(),
                            created.updatedAt(),
                            null);
                    repository.insert(created);
                    String currentRequestId = requestId(requestId);
                    repository.appendLifecycle(
                            UUID.randomUUID().toString(),
                            locked.scopeId(),
                            sourceVersionId,
                            "ROLLED_BACK",
                            effectiveFrom,
                            reason.trim(),
                            actorId,
                            currentRequestId,
                            now);
                    repository.appendLifecycle(
                            UUID.randomUUID().toString(),
                            locked.scopeId(),
                            createdVersionId,
                            "PUBLISHED",
                            effectiveFrom,
                            reason.trim(),
                            actorId,
                            currentRequestId,
                            now);
                    audit(actorId, "ATTENDANCE_POLICY_ROLLED_BACK",
                            createdVersionId, reason);
                    return requireVersion(
                            templateId, createdVersionId, companyId);
                });
    }

    private Scope requireScope(
            String templateId,
            String companyId,
            String capability,
            boolean lock) {
        capabilityService.require(capability);
        String actorId = principalProvider.currentPrincipalId();
        if (companyId == null
                || !peopleRepository.canAccessCompany(
                        actorId, capability, companyId, clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        Scope scope = lock
                ? repository.lockScope(templateId, companyId)
                : repository.findScope(templateId, companyId);
        if (scope == null) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return scope;
    }

    private ScopedPolicyVersion requireVersion(
            String templateId, String versionId, String companyId) {
        return repository.find(templateId, versionId, companyId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
    }

    private ValidationResult validate(ScopedPolicyVersion version) {
        List<ValidationIssue> issues = AttendancePolicyParameterValidator.validate(
                version.policyKind(), version.parameters());
        return new ValidationResult(
                issues.isEmpty(), issues, clock.instant());
    }

    private String snapshot(ScopedPolicyVersion version) {
        try {
            Map<String, Object> parameters = new TreeMap<>();
            for (ParameterValue parameter : version.parameters()) {
                if (parameter == null
                        || parameter.key() == null
                        || parameter.key().isBlank()
                        || parameters.containsKey(parameter.key())) {
                    throw AttendanceSetupRules.invalid(
                            "策略参数键不能为空或重复");
                }
                parameters.put(parameter.key(), parameter.value());
            }
            Map<String, Object> snapshot = new TreeMap<>();
            snapshot.put("effectiveFrom", version.effectiveFrom());
            snapshot.put("effectiveTo", version.effectiveTo());
            snapshot.put("companyId", version.companyId());
            snapshot.put("parameters", parameters);
            snapshot.put("policyKind", version.policyKind().name());
            snapshot.put("scopeId", version.scopeId());
            snapshot.put("templateId", version.templateId());
            snapshot.put("versionNumber", version.versionNumber());
            return objectMapper.writeValueAsString(snapshot);
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "attendance policy snapshot serialization failed", exception);
        }
    }

    private ScopedPolicyVersion withSnapshot(
            ScopedPolicyVersion version,
            String snapshot,
            String digest) {
        return new ScopedPolicyVersion(
                version.scopedVersionId(),
                version.scopeId(),
                version.templateId(),
                version.companyId(),
                version.policyKind(),
                version.versionNumber(),
                version.status(),
                version.parameters(),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.changeReason(),
                version.validation(),
                snapshot,
                digest,
                version.rollbackOfScopedVersionId(),
                version.rowVersion(),
                version.createdBy(),
                version.createdAt(),
                version.publishedAt(),
                version.updatedBy(),
                version.updatedAt(),
                version.deactivationEffectiveFrom());
    }

    private void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE",
                    "分页参数超出允许范围");
        }
    }

    private void requirePeriod(LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (effectiveFrom == null
                || (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom))) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EFFECTIVE_PERIOD",
                    "生效区间必须是非空半开区间");
        }
    }

    private void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 2 || reason.length() > 500) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REASON",
                    "原因长度必须在 2 到 500 字符");
        }
    }

    private void requireFutureChange(LocalDate effectiveFrom) {
        if (effectiveFrom == null || !effectiveFrom.isAfter(LocalDate.now(clock))) {
            throw conflict(
                    "FROZEN_PERIOD_PROTECTION_UNAVAILABLE",
                    "月结保护提供方尚未交付，只允许安排未来生效的策略变更");
        }
    }

    private void requireDeactivationBoundary(
            ScopedPolicyVersion current, LocalDate effectiveFrom) {
        if (!effectiveFrom.isAfter(current.effectiveFrom())
                || current.effectiveTo() != null
                && !effectiveFrom.isBefore(current.effectiveTo())) {
            throw conflict(
                    "POLICY_DEACTIVATION_PERIOD_INVALID",
                    "停用边界必须位于策略版本的非空有效期内");
        }
    }

    private void requireRollbackBoundary(
            ScopedPolicyVersion source, LocalDate effectiveFrom) {
        if (!effectiveFrom.isAfter(source.effectiveFrom())) {
            throw conflict(
                    "POLICY_ROLLBACK_PERIOD_INVALID",
                    "回滚边界必须晚于回滚源版本的生效日");
        }
    }

    private void requireLatestPublishedSource(
            String scopeId, String sourceVersionId) {
        if (repository.findLatestPublishedVersionId(scopeId)
                .filter(sourceVersionId::equals)
                .isEmpty()) {
            throw conflict(
                    "POLICY_ROLLBACK_SOURCE_NOT_CURRENT",
                    "回滚源必须是发布链最新版本");
        }
    }

    private void requireAppendOnlyPublicationBoundary(
            String scopeId, LocalDate effectiveFrom) {
        if (repository.hasPublicationAtOrAfter(scopeId, effectiveFrom)) {
            throw conflict(
                    "POLICY_PUBLICATION_BACKFILL_CONFLICT",
                    "考勤策略发布只能追加到已有发布时序之后，不能重叠或回插");
        }
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) {
            throw stale();
        }
    }

    private Map<String, String> lifecycleHeaders(long rowVersion) {
        return Map.of("ETag", StrongEtag.ofVersion(rowVersion));
    }

    private void audit(String actorId, String action, String resourceId, String reason) {
        auditService.record(
                actorId,
                action,
                "ATTENDANCE_POLICY_SCOPED_VERSION",
                resourceId,
                "SUCCESS",
                reason);
    }

    private ApiProblemException stale() {
        return conflict("VERSION_CONFLICT", "规则版本已被其他操作更新");
    }

    private ApiProblemException conflict(String code, String message) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, message, true);
    }

    private String requestId(String requestId) {
        return requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId;
    }

    private record DraftIdempotencyRequest(
            String companyId,
            String basedOnVersionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }

    private record UpdateIdempotencyRequest(
            String companyId,
            List<ParameterValue> parameters,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason,
            long expectedVersion) {
    }

    private record TransitionIdempotencyRequest(
            String companyId,
            String reason,
            long expectedVersion,
            String targetVersionId,
            LocalDate effectiveFrom) {
    }

}
