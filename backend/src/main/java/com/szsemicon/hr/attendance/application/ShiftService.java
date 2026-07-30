package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.application.ShiftCommands.TemplateCommand;
import com.szsemicon.hr.attendance.application.ShiftCommands.VersionCommand;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Page;
import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftTemplate;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import com.szsemicon.hr.attendance.domain.ShiftModels.VersionStatus;
import com.szsemicon.hr.attendance.domain.ShiftSegmentValidator;
import com.szsemicon.hr.attendance.domain.ShiftSnapshotDigest;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ShiftService {

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository peopleRepository;
    private final AttendanceGroupRepository groupRepository;
    private final ShiftRepository repository;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ShiftService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository peopleRepository,
            AttendanceGroupRepository groupRepository,
            ShiftRepository repository,
            AuditService auditService,
            SecurityTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.peopleRepository = peopleRepository;
        this.groupRepository = groupRepository;
        this.repository = repository;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ShiftTemplate> listTemplates() {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        return allTemplates(
                principalProvider.currentPrincipalId(), clock.instant());
    }

    @Transactional(readOnly = true)
    public Page<ShiftTemplate> listTemplates(int page, int size) {
        AttendanceSetupRules.page(page, size);
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        return new Page<>(
                repository.listTemplates(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SETUP_READ,
                        size,
                        page * size,
                        at),
                repository.countTemplates(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SETUP_READ,
                        at),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public ShiftTemplate getTemplate(String shiftId) {
        return requireTemplate(shiftId, CapabilityCodes.ATTENDANCE_SETUP_READ);
    }

    @Transactional
    public ShiftTemplate createTemplate(
            TemplateCommand command, String idempotencyKey) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        TemplateCommand normalized = normalize(command);
        requireCompany(
                normalized.companyId(), CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        var location = groupRepository.findLocation(normalized.locationId())
                .filter(value -> value.companyId().equals(normalized.companyId()))
                .filter(value -> value.status() == LifecycleStatus.ACTIVE)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        String actor = principalProvider.currentPrincipalId();
        String key = AttendanceSetupRules.idempotencyKey(idempotencyKey);
        ShiftTemplate replay =
                repository.findTemplateByIdempotency(actor, key).orElse(null);
        if (replay != null) {
            if (!same(replay, normalized)) {
                throw AttendanceSetupRules.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key 已用于不同的班次模板请求");
            }
            return replay;
        }
        Instant now = clock.instant();
        ShiftTemplate created = new ShiftTemplate(
                UUID.randomUUID().toString(), normalized.companyId(),
                location.locationId(), normalized.code(), normalized.name(),
                LifecycleStatus.ACTIVE, 0, normalized.reason(), actor, now, actor, now);
        repository.insertTemplate(created, key);
        audit(actor, "SHIFT_TEMPLATE_CREATED", "SHIFT_TEMPLATE",
                created.shiftId(), normalized.reason(), null, created);
        return created;
    }

    @Transactional
    public ShiftTemplate updateTemplate(
            String shiftId, TemplateCommand command, long expectedVersion) {
        requireTemplate(
                shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        TemplateCommand normalized = normalize(command);
        repository.lockTemplate(shiftId);
        ShiftTemplate current = requireTemplate(
                shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        if (!current.companyId().equals(normalized.companyId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (!current.locationId().equals(normalized.locationId())
                || !current.code().equals(normalized.code())) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_TEMPLATE_IDENTITY_IMMUTABLE",
                    "班次 family 的地点和编码不可原地修改");
        }
        var location = groupRepository.findLocation(normalized.locationId())
                .filter(value -> value.companyId().equals(current.companyId()))
                .filter(value -> value.status() == LifecycleStatus.ACTIVE)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireVersion(current.rowVersion(), expectedVersion);
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        ShiftTemplate updated = new ShiftTemplate(
                current.shiftId(), current.companyId(), location.locationId(),
                normalized.code(), normalized.name(), current.status(),
                current.rowVersion() + 1, normalized.reason(), current.createdBy(),
                current.createdAt(), actor, now);
        if (!repository.updateTemplate(updated, expectedVersion)) {
            throw new OptimisticLockingFailureException("shift template changed");
        }
        auditVersioned(
                actor, "SHIFT_TEMPLATE_UPDATED", "SHIFT_TEMPLATE",
                shiftId, normalized.reason(), updated.rowVersion(),
                current, updated);
        return updated;
    }

    @Transactional
    public ShiftTemplate changeTemplateStatus(
            String shiftId,
            LifecycleStatus target,
            long expectedVersion,
            String reason) {
        requireTemplate(
                shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        repository.lockTemplate(shiftId);
        ShiftTemplate current = requireTemplate(
                shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        requireVersion(current.rowVersion(), expectedVersion);
        String normalizedReason = AttendanceSetupRules.reason(reason);
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        ShiftTemplate updated = new ShiftTemplate(
                current.shiftId(), current.companyId(), current.locationId(),
                current.code(), current.name(), target, current.rowVersion() + 1,
                normalizedReason, current.createdBy(), current.createdAt(), actor, now);
        if (!repository.updateTemplate(updated, expectedVersion)) {
            throw new OptimisticLockingFailureException("shift template changed");
        }
        auditVersioned(
                actor, "SHIFT_TEMPLATE_" + target.name(), "SHIFT_TEMPLATE",
                shiftId, normalizedReason, updated.rowVersion(),
                current, updated);
        return updated;
    }

    @Transactional(readOnly = true)
    public List<ShiftVersion> listVersions(String shiftId) {
        requireTemplate(shiftId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        return allVersions(shiftId);
    }

    @Transactional(readOnly = true)
    public Page<ShiftVersion> listVersions(
            String shiftId, int page, int size) {
        AttendanceSetupRules.page(page, size);
        requireTemplate(shiftId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        return new Page<>(
                repository.listVersions(shiftId, size, page * size),
                repository.countVersions(shiftId),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public ShiftVersion getVersion(String versionId) {
        ShiftVersion version = repository.findVersion(versionId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireTemplate(version.shiftId(), CapabilityCodes.ATTENDANCE_SETUP_READ);
        return version;
    }

    @Transactional
    public ShiftVersion createVersion(
            String shiftId, VersionCommand command, String idempotencyKey) {
        ShiftTemplate template =
                requireTemplate(shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        AttendanceSetupRules.halfOpenPeriod(
                command.effectiveFrom(), command.effectiveTo());
        var segments = validateSegments(command.segments());
        String reason = AttendanceSetupRules.reason(command.reason());
        String actor = principalProvider.currentPrincipalId();
        String key = AttendanceSetupRules.idempotencyKey(idempotencyKey);
        ShiftVersion replay = repository.findVersionByIdempotency(actor, key).orElse(null);
        if (replay != null) {
            if (!replay.shiftId().equals(shiftId)
                    || !replay.effectiveFrom().equals(command.effectiveFrom())
                    || !Objects.equals(replay.effectiveTo(), command.effectiveTo())
                    || !replay.segments().equals(segments)) {
                throw AttendanceSetupRules.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key 已用于不同的班次版本请求");
            }
            return replay;
        }
        repository.lockTemplate(template.shiftId());
        Instant now = clock.instant();
        var locationRevisions = groupRepository.resolveLocationRevisions(
                template.locationId(), command.effectiveFrom(), clock.instant());
        if (locationRevisions.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    locationRevisions.isEmpty()
                            ? "LOCATION_REVISION_MISSING"
                            : "LOCATION_REVISION_AMBIGUOUS",
                    "班次生效日必须恰好解析一个地点时区版本");
        }
        ShiftVersion created = new ShiftVersion(
                UUID.randomUUID().toString(), template.shiftId(),
                repository.nextVersionNumber(template.shiftId()), VersionStatus.DRAFT,
                command.effectiveFrom(), command.effectiveTo(),
                locationRevisions.getFirst().timeZone(), segments, null, 0,
                reason, actor, now, null, actor, now);
        repository.insertVersion(created, json(segments), key);
        ShiftVersion persisted = repository.findVersion(
                created.shiftVersionId()).orElseThrow();
        audit(actor, "SHIFT_VERSION_DRAFTED", "SHIFT_VERSION",
                created.shiftVersionId(), reason, null, persisted);
        return persisted;
    }

    @Transactional
    public ShiftVersion updateVersion(
            String shiftId,
            String versionId,
            VersionCommand command,
            long expectedVersion) {
        requireTemplate(shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        ShiftVersion current = repository.findVersion(versionId)
                .filter(value -> value.shiftId().equals(shiftId))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (current.status() != VersionStatus.DRAFT) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_IMMUTABLE",
                    "已发布班次版本不可原地修改");
        }
        requireVersion(current.rowVersion(), expectedVersion);
        AttendanceSetupRules.halfOpenPeriod(
                command.effectiveFrom(), command.effectiveTo());
        List<Segment> segments = validateSegments(command.segments());
        String normalizedReason = AttendanceSetupRules.reason(command.reason());
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        repository.lockTemplate(shiftId);
        ShiftVersion updated = new ShiftVersion(
                current.shiftVersionId(), current.shiftId(), current.versionNumber(),
                current.status(), command.effectiveFrom(), command.effectiveTo(),
                current.timeZone(), segments, null,
                current.rowVersion() + 1, normalizedReason,
                current.createdBy(), current.createdAt(), null, actor, now);
        if (!repository.updateDraftVersion(
                updated, json(segments), expectedVersion)) {
            throw new OptimisticLockingFailureException("shift version changed");
        }
        ShiftVersion successor = repository.listVersions(shiftId, 1, 0).stream()
                .filter(value -> value.versionNumber() > current.versionNumber())
                .findFirst()
                .orElseThrow();
        audit(actor, "SHIFT_VERSION_DRAFT_UPDATED", "SHIFT_VERSION",
                versionId, normalizedReason, current, successor);
        return successor;
    }

    @Transactional
    public ShiftVersion publish(
            String shiftId,
            String versionId,
            long expectedVersion,
            String reason) {
        requireTemplate(shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        repository.lockTemplate(shiftId);
        ShiftVersion current = repository.findVersion(versionId)
                .filter(value -> value.shiftId().equals(shiftId))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (current.status() != VersionStatus.DRAFT) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_IMMUTABLE", "仅草稿班次版本可以发布");
        }
        if (current.rowVersion() != expectedVersion) {
            throw new OptimisticLockingFailureException("shift version changed");
        }
        String normalizedReason = AttendanceSetupRules.reason(reason);
        validateSegments(current.segments());
        if (repository.hasPublishedOverlap(
                shiftId, current.effectiveFrom(), current.effectiveTo(), versionId)) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_OVERLAP", "已发布班次版本期间发生重叠");
        }
        requireAdjacentTimeline(current, allVersions(shiftId));
        String snapshotDigest = ShiftSnapshotDigest.digest(current);
        if (!snapshotDigest.equals(current.snapshotDigest())) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_SNAPSHOT_DIGEST_MISMATCH",
                    "班次草稿摘要与 canonical 内容不一致");
        }
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        if (!repository.publishVersion(
                versionId, expectedVersion, snapshotDigest,
                actor, normalizedReason, now)) {
            throw new OptimisticLockingFailureException("shift version changed");
        }
        ShiftVersion published = repository.findVersion(versionId).orElseThrow();
        if (!snapshotDigest.equals(published.snapshotDigest())) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_SNAPSHOT_DIGEST_MISMATCH",
                    "发布响应与持久化班次摘要不一致");
        }
        audit(actor, "SHIFT_VERSION_PUBLISHED", "SHIFT_VERSION",
                versionId, normalizedReason, current, published);
        return published;
    }

    @Transactional
    public ShiftVersion deactivateVersion(
            String shiftId,
            String versionId,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String reason) {
        requireTemplate(shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        repository.lockTemplate(shiftId);
        ShiftVersion current = repository.findVersion(versionId)
                .filter(value -> value.shiftId().equals(shiftId))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (current.status() != VersionStatus.PUBLISHED) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_INVALID_TRANSITION",
                    "只有已发布班次版本可以停用");
        }
        requireVersion(current.rowVersion(), expectedVersion);
        LocalDate boundary = requireFutureDeactivationBoundary(
                current, businessEffectiveFrom);
        List<ShiftVersion> versions = allVersions(shiftId);
        requireContinuousPublishedTimeline(versions);
        List<ShiftVersion> successors = versions.stream()
                .filter(version -> !version.shiftVersionId().equals(versionId))
                .filter(version -> version.status() == VersionStatus.PUBLISHED)
                .filter(version -> version.effectiveFrom().equals(boundary))
                .toList();
        if (successors.size() > 1) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_AMBIGUOUS_SUCCESSOR",
                    "停用边界存在多个班次承接版本");
        }
        ShiftVersion successor = successors.isEmpty()
                ? null : successors.getFirst();
        if (successor != null
                && !Objects.equals(current.effectiveTo(), boundary)) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_NON_ADJACENT_SUCCESSOR",
                    "班次承接版本必须与当前版本半开期间相邻");
        }
        if (repository.hasOverrideReferencesAtOrAfter(versionId, boundary)) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_OVERRIDE_REFERENCED",
                    "停用边界之后仍有工作日历显式引用该班次版本");
        }
        if (successor == null
                && repository.hasActiveGroupReferencesAtOrAfter(
                        shiftId, boundary)) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_GAP",
                    "被考勤组引用的班次时间线必须有相邻发布版本承接");
        }
        String normalizedReason = AttendanceSetupRules.reason(reason);
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        if (!repository.scheduleVersionDeactivation(
                versionId,
                expectedVersion,
                boundary,
                successor == null ? null : successor.shiftVersionId(),
                actor,
                normalizedReason,
                now)) {
            throw new OptimisticLockingFailureException("shift version changed");
        }
        verifyDeactivationResolution(
                shiftId, current, successor, boundary, now);
        ShiftVersion updated = repository.findVersion(versionId).orElseThrow();
        audit(
                actor,
                "SHIFT_VERSION_DEACTIVATED",
                "SHIFT_VERSION",
                versionId,
                normalizedReason,
                current,
                updated);
        return updated;
    }

    @Transactional
    public ShiftVersion activateVersion(
            String shiftId,
            String versionId,
            long expectedVersion,
            String reason) {
        requireTemplate(shiftId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT);
        throw AttendanceSetupRules.conflict(
                "SHIFT_VERSION_REACTIVATION_REQUIRES_NEW_VERSION",
                "已停用班次不可重写时间线，请创建并发布新的不可变版本");
    }

    @Transactional
    public ShiftVersion changeVersionStatus(
            String shiftId,
            String versionId,
            VersionStatus target,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String reason) {
        if (target == null || target == VersionStatus.DRAFT) {
            throw AttendanceSetupRules.invalid(
                    "班次版本状态不能回退为草稿");
        }
        return target == VersionStatus.PUBLISHED
                ? activateVersion(shiftId, versionId, expectedVersion, reason)
                : deactivateVersion(
                        shiftId,
                        versionId,
                        expectedVersion,
                        businessEffectiveFrom,
                        reason);
    }

    private ShiftTemplate requireTemplate(String shiftId, String capability) {
        capabilityService.require(capability);
        ShiftTemplate template = repository.findTemplate(shiftId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireCompany(template.companyId(), capability);
        return template;
    }

    private List<ShiftTemplate> allTemplates(String principalId, Instant at) {
        java.util.ArrayList<ShiftTemplate> templates =
                new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<ShiftTemplate> page = repository.listTemplates(
                    principalId,
                    CapabilityCodes.ATTENDANCE_SETUP_READ,
                    100,
                    offset,
                    at);
            templates.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(templates);
            }
            offset += page.size();
        }
    }

    private List<ShiftVersion> allVersions(String shiftId) {
        java.util.ArrayList<ShiftVersion> versions =
                new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<ShiftVersion> page =
                    repository.listVersions(shiftId, 100, offset);
            versions.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(versions);
            }
            offset += page.size();
        }
    }

    private void requireCompany(String companyId, String capability) {
        if (!peopleRepository.canAccessCompany(
                principalProvider.currentPrincipalId(), capability,
                companyId, clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private TemplateCommand normalize(TemplateCommand command) {
        return new TemplateCommand(
                Objects.requireNonNull(command.companyId()),
                Objects.requireNonNull(command.locationId()),
                AttendanceSetupRules.code(command.code()),
                AttendanceSetupRules.name(command.name()),
                AttendanceSetupRules.reason(command.reason()));
    }

    private void requireAdjacentTimeline(
            ShiftVersion candidate, List<ShiftVersion> versions) {
        List<ShiftVersion> published = versions.stream()
                .filter(value -> value.status() == VersionStatus.PUBLISHED)
                .sorted(Comparator.comparing(ShiftVersion::effectiveFrom))
                .toList();
        ShiftVersion previous = published.stream()
                .filter(value -> value.effectiveFrom().isBefore(candidate.effectiveFrom()))
                .reduce((left, right) -> right)
                .orElse(null);
        ShiftVersion next = published.stream()
                .filter(value -> value.effectiveFrom().isAfter(candidate.effectiveFrom()))
                .findFirst()
                .orElse(null);
        if (previous != null
                && !Objects.equals(previous.effectiveTo(), candidate.effectiveFrom())) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_GAP", "班次版本时间线不能出现间隙");
        }
        if (next != null
                && !Objects.equals(candidate.effectiveTo(), next.effectiveFrom())) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_GAP", "班次版本时间线不能出现间隙");
        }
    }

    private LocalDate requireFutureDeactivationBoundary(
            ShiftVersion current, LocalDate boundary) {
        if (boundary == null
                || !boundary.isAfter(LocalDate.now(clock))
                || !boundary.isAfter(current.effectiveFrom())) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_HISTORY_PROTECTED",
                    "班次停用必须声明晚于今天和版本起点的未来业务边界");
        }
        if (current.effectiveTo() != null
                && boundary.isAfter(current.effectiveTo())) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_DEACTIVATION_OUT_OF_RANGE",
                    "班次停用边界不得晚于版本的半开期间终点");
        }
        return boundary;
    }

    private void requireContinuousPublishedTimeline(
            List<ShiftVersion> versions) {
        List<ShiftVersion> published = versions.stream()
                .filter(version -> version.status() == VersionStatus.PUBLISHED)
                .sorted(Comparator.comparing(ShiftVersion::effectiveFrom))
                .toList();
        for (int index = 1; index < published.size(); index++) {
            ShiftVersion previous = published.get(index - 1);
            ShiftVersion next = published.get(index);
            if (!Objects.equals(
                    previous.effectiveTo(), next.effectiveFrom())) {
                throw AttendanceSetupRules.conflict(
                        "SHIFT_VERSION_GAP",
                        "班次停用前的已发布时间线已存在间隙");
            }
        }
    }

    private void verifyDeactivationResolution(
            String shiftId,
            ShiftVersion current,
            ShiftVersion successor,
            LocalDate boundary,
            Instant knowledgeAsOf) {
        List<ShiftVersion> before = repository.resolvePublishedAt(
                shiftId, boundary.minusDays(1), knowledgeAsOf);
        if (boundary.minusDays(1).isBefore(current.effectiveFrom())
                || before.size() != 1
                || !before.getFirst().shiftVersionId()
                .equals(current.shiftVersionId())
                || before.getFirst().status() != VersionStatus.PUBLISHED) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_DEACTIVATION_INVARIANT",
                    "班次停用未能保持边界前的唯一发布解析");
        }
        List<ShiftVersion> after = repository.resolvePublishedAt(
                shiftId, boundary, knowledgeAsOf);
        if (successor == null) {
            if (!after.isEmpty()) {
                throw AttendanceSetupRules.conflict(
                        "SHIFT_VERSION_DEACTIVATION_INVARIANT",
                        "班次终止边界解析不唯一");
            }
            return;
        }
        if (after.size() != 1
                || !after.getFirst().shiftVersionId()
                .equals(successor.shiftVersionId())
                || after.getFirst().status() != VersionStatus.PUBLISHED) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_VERSION_DEACTIVATION_INVARIANT",
                    "班次停用未由相邻发布版本原子承接");
        }
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) {
            throw new OptimisticLockingFailureException("row version changed");
        }
    }

    private boolean same(ShiftTemplate template, TemplateCommand command) {
        return template.companyId().equals(command.companyId())
                && template.locationId().equals(command.locationId())
                && template.code().equals(command.code())
                && template.name().equals(command.name());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("shift snapshot serialization failed", exception);
        }
    }

    private List<Segment> validateSegments(List<Segment> segments) {
        try {
            return ShiftSegmentValidator.validateAndNormalize(segments);
        } catch (IllegalArgumentException exception) {
            throw AttendanceSetupRules.invalid(exception.getMessage());
        }
    }

    private void audit(
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String reason,
            Object before,
            Object after) {
        auditService.record(
                actor, action, resourceType, resourceId, "SUCCESS", reason,
                before == null ? null : tokenService.digest(before.toString()),
                after == null ? null : tokenService.digest(after.toString()));
    }

    private void auditVersioned(
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String reason,
            long resourceVersion,
            Object before,
            Object after) {
        auditService.recordVersioned(
                actor, action, resourceType, resourceId, "SUCCESS", reason,
                resourceVersion,
                before == null ? null : tokenService.digest(before.toString()),
                after == null ? null : tokenService.digest(after.toString()));
    }
}
