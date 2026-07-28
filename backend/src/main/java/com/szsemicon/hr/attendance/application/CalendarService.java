package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.application.CalendarCommands.CalendarCommand;
import com.szsemicon.hr.attendance.application.CalendarCommands.CalendarDayCommand;
import com.szsemicon.hr.attendance.application.CalendarCommands.CalendarVersionCommand;
import com.szsemicon.hr.attendance.application.CalendarCommands.ReplaceDaysCommand;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Page;
import com.szsemicon.hr.attendance.domain.CalendarSnapshotDigest;
import com.szsemicon.hr.attendance.domain.CalendarModels.CalendarStatus;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import com.szsemicon.hr.attendance.domain.ShiftModels.VersionStatus;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarService {

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository peopleRepository;
    private final AttendanceGroupRepository groupRepository;
    private final CalendarRepository repository;
    private final ShiftRepository shiftRepository;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final Clock clock;

    public CalendarService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository peopleRepository,
            AttendanceGroupRepository groupRepository,
            CalendarRepository repository,
            ShiftRepository shiftRepository,
            AuditService auditService,
            SecurityTokenService tokenService,
            Clock clock) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.peopleRepository = peopleRepository;
        this.groupRepository = groupRepository;
        this.repository = repository;
        this.shiftRepository = shiftRepository;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WorkCalendar> listCalendars(Integer year) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        return allCalendars(
                principalProvider.currentPrincipalId(), year, clock.instant());
    }

    @Transactional(readOnly = true)
    public Page<WorkCalendar> listCalendars(
            Integer year, int page, int size) {
        AttendanceSetupRules.page(page, size);
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        return new Page<>(
                repository.listCalendars(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SETUP_READ,
                        year,
                        size,
                        page * size,
                        at),
                repository.countCalendars(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SETUP_READ,
                        year,
                        at),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public WorkCalendar getCalendar(String calendarId) {
        return requireCalendar(calendarId, CapabilityCodes.ATTENDANCE_SETUP_READ);
    }

    @Transactional(readOnly = true)
    public List<WorkCalendar> listVersions(String calendarId) {
        requireCalendar(calendarId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        return allVersions(calendarId);
    }

    @Transactional(readOnly = true)
    public Page<WorkCalendar> listVersions(
            String calendarId, int page, int size) {
        AttendanceSetupRules.page(page, size);
        requireCalendar(calendarId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        return new Page<>(
                repository.listVersions(calendarId, size, page * size),
                repository.countVersions(calendarId),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public WorkCalendar getVersion(String calendarId, String versionId) {
        return requireCalendarVersion(
                calendarId, versionId, CapabilityCodes.ATTENDANCE_SETUP_READ);
    }

    @Transactional
    public WorkCalendar createCalendar(
            CalendarCommand command, String idempotencyKey) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        CalendarCommand normalized = normalize(command);
        requireLegalEntity(
                normalized.legalEntityId(),
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        String actor = principalProvider.currentPrincipalId();
        String key = AttendanceSetupRules.idempotencyKey(idempotencyKey);
        var locationRevisions = groupRepository.resolveLocationRevisions(
                normalized.locationId(), normalized.effectiveFrom(), clock.instant());
        if (locationRevisions.size() != 1
                || !locationRevisions.getFirst().legalEntityId()
                        .equals(normalized.legalEntityId())
                || !locationRevisions.getFirst().timeZone()
                        .equals(normalized.timeZone())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        WorkCalendar replay =
                repository.findCalendarByIdempotency(actor, key).orElse(null);
        if (replay != null) {
            if (!same(replay, normalized)) {
                throw AttendanceSetupRules.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key 已用于不同的工作日历请求");
            }
            return replay;
        }
        Instant now = clock.instant();
        WorkCalendar created = new WorkCalendar(
                UUID.randomUUID().toString(), normalized.legalEntityId(),
                normalized.locationId(), normalized.code(),
                UUID.randomUUID().toString(), 1,
                normalized.name(), normalized.calendarYear(),
                normalized.timeZone(), CalendarStatus.DRAFT,
                normalized.effectiveFrom(), normalized.effectiveTo(), null, 0,
                normalized.reason(), actor, now, actor, now);
        repository.insertCalendar(created, key);
        WorkCalendar persisted = repository.findVersion(
                created.calendarVersionId()).orElseThrow();
        audit(actor, "WORK_CALENDAR_CREATED", "WORK_CALENDAR",
                persisted.calendarId(), normalized.reason(), null, persisted);
        return persisted;
    }

    @Transactional
    public WorkCalendar createVersion(
            String calendarId,
            CalendarVersionCommand command,
            String idempotencyKey) {
        WorkCalendar family = requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        CalendarVersionCommand normalized =
                normalizeVersion(family, command);
        String actor = principalProvider.currentPrincipalId();
        String key = AttendanceSetupRules.idempotencyKey(idempotencyKey);
        WorkCalendar replay =
                repository.findVersionByIdempotency(actor, key).orElse(null);
        if (replay != null) {
            if (!replay.calendarId().equals(calendarId)
                    || !sameVersion(replay, normalized)) {
                throw AttendanceSetupRules.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key 已用于不同的工作日历版本请求");
            }
            return replay;
        }
        repository.lockCalendar(calendarId);
        replay = repository.findVersionByIdempotency(actor, key).orElse(null);
        if (replay != null) {
            if (!replay.calendarId().equals(calendarId)
                    || !sameVersion(replay, normalized)) {
                throw AttendanceSetupRules.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key 已用于不同的工作日历版本请求");
            }
            return replay;
        }
        Instant now = clock.instant();
        WorkCalendar created = new WorkCalendar(
                family.calendarId(), family.legalEntityId(), family.locationId(),
                family.code(), UUID.randomUUID().toString(),
                repository.nextVersionNumber(calendarId),
                normalized.name(), normalized.calendarYear(),
                normalized.timeZone(), CalendarStatus.DRAFT,
                normalized.effectiveFrom(), normalized.effectiveTo(),
                null, 0, normalized.reason(), actor, now, actor, now);
        repository.insertVersion(created, key);
        WorkCalendar persisted = repository.findVersion(
                created.calendarVersionId()).orElseThrow();
        audit(actor, "WORK_CALENDAR_VERSION_CREATED", "WORK_CALENDAR_VERSION",
                persisted.calendarVersionId(), normalized.reason(), null, persisted);
        return persisted;
    }

    @Transactional
    public WorkCalendar updateCalendar(
            String calendarId, CalendarCommand command, long expectedVersion) {
        WorkCalendar current = requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        if (current.status() != CalendarStatus.DRAFT) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_IMMUTABLE",
                    "已发布工作日历不可原地修改");
        }
        CalendarCommand normalized = normalize(command);
        if (!current.legalEntityId().equals(normalized.legalEntityId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (!current.locationId().equals(normalized.locationId())
                || !current.code().equals(normalized.code())) {
            throw AttendanceSetupRules.conflict(
                    "CALENDAR_FAMILY_IMMUTABLE",
                    "工作日历 family 的地点与编码不可原地修改");
        }
        requireVersion(current.rowVersion(), expectedVersion);
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        WorkCalendar updated = new WorkCalendar(
                current.calendarId(), current.legalEntityId(), current.locationId(),
                current.code(), current.calendarVersionId(), current.versionNumber(),
                normalized.name(), normalized.calendarYear(),
                normalized.timeZone(), current.status(),
                normalized.effectiveFrom(), normalized.effectiveTo(), null,
                current.rowVersion() + 1,
                normalized.reason(), current.createdBy(), current.createdAt(), actor, now);
        repository.lockCalendar(calendarId);
        if (!repository.updateCalendar(updated, expectedVersion)) {
            throw new OptimisticLockingFailureException("work calendar version changed");
        }
        WorkCalendar successor = repository.findCalendar(calendarId).orElseThrow();
        audit(actor, "WORK_CALENDAR_UPDATED", "WORK_CALENDAR",
                calendarId, normalized.reason(), current, successor);
        return successor;
    }

    @Transactional
    public WorkCalendar updateVersion(
            String calendarId,
            String versionId,
            CalendarVersionCommand command,
            long expectedVersion) {
        WorkCalendar current = requireCalendarVersion(
                calendarId, versionId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        if (current.status() != CalendarStatus.DRAFT) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_IMMUTABLE",
                    "已发布工作日历版本不可原地修改");
        }
        CalendarVersionCommand normalized =
                normalizeVersion(current, command);
        requireVersion(current.rowVersion(), expectedVersion);
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        WorkCalendar updated = new WorkCalendar(
                current.calendarId(), current.legalEntityId(), current.locationId(),
                current.code(), current.calendarVersionId(), current.versionNumber(),
                normalized.name(), normalized.calendarYear(),
                normalized.timeZone(), current.status(),
                normalized.effectiveFrom(), normalized.effectiveTo(), null,
                current.rowVersion() + 1, normalized.reason(),
                current.createdBy(), current.createdAt(), actor, now);
        repository.lockCalendar(calendarId);
        if (!repository.updateCalendar(updated, expectedVersion)) {
            throw new OptimisticLockingFailureException(
                    "work calendar version changed");
        }
        WorkCalendar successor = repository.listVersions(calendarId, 1, 0).stream()
                .findFirst()
                .orElseThrow();
        audit(actor, "WORK_CALENDAR_VERSION_UPDATED", "WORK_CALENDAR_VERSION",
                versionId, normalized.reason(), current, successor);
        return successor;
    }

    @Transactional(readOnly = true)
    public List<WorkCalendarDay> listDays(
            String calendarId, LocalDate from, LocalDate to) {
        WorkCalendar calendar =
                requireCalendar(calendarId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        if (from == null || to == null || to.isBefore(from)) {
            throw AttendanceSetupRules.invalid("日历查询日期范围无效");
        }
        return allDays(calendar.calendarVersionId(), from, to);
    }

    @Transactional(readOnly = true)
    public Page<WorkCalendarDay> listDays(
            String calendarId,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        AttendanceSetupRules.page(page, size);
        WorkCalendar calendar =
                requireCalendar(calendarId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        requireDayRange(from, to);
        return new Page<>(
                repository.listDays(
                        calendar.calendarVersionId(),
                        from,
                        to,
                        size,
                        page * size),
                repository.countDays(calendar.calendarVersionId(), from, to),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public List<WorkCalendarDay> listVersionDays(
            String calendarId,
            String versionId,
            LocalDate from,
            LocalDate to) {
        WorkCalendar calendar = requireCalendarVersion(
                calendarId, versionId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        if (from == null || to == null || to.isBefore(from)) {
            throw AttendanceSetupRules.invalid("日历查询日期范围无效");
        }
        return allDays(calendar.calendarVersionId(), from, to);
    }

    @Transactional(readOnly = true)
    public Page<WorkCalendarDay> listVersionDays(
            String calendarId,
            String versionId,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        AttendanceSetupRules.page(page, size);
        WorkCalendar calendar = requireCalendarVersion(
                calendarId, versionId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        requireDayRange(from, to);
        return new Page<>(
                repository.listDays(
                        calendar.calendarVersionId(),
                        from,
                        to,
                        size,
                        page * size),
                repository.countDays(calendar.calendarVersionId(), from, to),
                page,
                size);
    }

    @Transactional
    public WorkCalendar replaceDays(
            String calendarId,
            ReplaceDaysCommand command,
            long expectedVersion) {
        WorkCalendar calendar = requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        if (calendar.status() != CalendarStatus.DRAFT) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_IMMUTABLE",
                    "只有草稿工作日历可以修改日期");
        }
        requireVersion(calendar.rowVersion(), expectedVersion);
        String reason = AttendanceSetupRules.reason(command.reason());
        if (command.days() == null
                || command.days().isEmpty()
                || command.days().size() > 366) {
            throw AttendanceSetupRules.invalid("日历日必须包含 1 至 366 条记录");
        }
        Set<LocalDate> dates = new HashSet<>();
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        List<WorkCalendarDay> days = command.days().stream()
                .map(day -> validateDay(calendar, day, dates, reason, actor, now))
                .toList();
        repository.replaceDays(
                calendar.calendarVersionId(), days, expectedVersion, actor, reason, now);
        WorkCalendar updated = repository.findCalendar(calendarId).orElseThrow();
        audit(actor, "WORK_CALENDAR_DAYS_REPLACED", "WORK_CALENDAR",
                calendarId, reason, calendar, updated);
        return updated;
    }

    @Transactional
    public WorkCalendar upsertVersionDays(
            String calendarId,
            String versionId,
            ReplaceDaysCommand command,
            long expectedVersion) {
        WorkCalendar calendar = requireCalendarVersion(
                calendarId, versionId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        return upsertDays(calendar, command, expectedVersion);
    }

    @Transactional
    public WorkCalendar publish(
            String calendarId, long expectedVersion, String reason) {
        requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        repository.lockCalendar(calendarId);
        WorkCalendar current = requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        return publishLocked(
                current,
                expectedVersion,
                reason,
                "WORK_CALENDAR_PUBLISHED",
                "WORK_CALENDAR");
    }

    @Transactional
    public WorkCalendar publishVersion(
            String calendarId,
            String versionId,
            long expectedVersion,
            String reason) {
        requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        repository.lockCalendar(calendarId);
        WorkCalendar current = requireCalendarVersion(
                calendarId, versionId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        return publishLocked(
                current,
                expectedVersion,
                reason,
                "WORK_CALENDAR_VERSION_PUBLISHED",
                "WORK_CALENDAR_VERSION");
    }

    @Transactional
    public WorkCalendar deactivate(
            String calendarId,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String reason) {
        requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        repository.lockCalendar(calendarId);
        WorkCalendar current = requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        return deactivateLocked(
                current,
                expectedVersion,
                businessEffectiveFrom,
                reason,
                "WORK_CALENDAR_DEACTIVATED",
                "WORK_CALENDAR");
    }

    @Transactional
    public WorkCalendar deactivateVersion(
            String calendarId,
            String versionId,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String reason) {
        requireCalendar(
                calendarId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        repository.lockCalendar(calendarId);
        WorkCalendar current = requireCalendarVersion(
                calendarId, versionId,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR);
        return deactivateLocked(
                current,
                expectedVersion,
                businessEffectiveFrom,
                reason,
                "WORK_CALENDAR_VERSION_DEACTIVATED",
                "WORK_CALENDAR_VERSION");
    }

    @Transactional
    public WorkCalendar changeStatus(
            String calendarId,
            CalendarStatus target,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String reason) {
        if (target == CalendarStatus.INACTIVE) {
            return deactivate(
                    calendarId,
                    expectedVersion,
                    businessEffectiveFrom,
                    reason);
        }
        throw AttendanceSetupRules.invalid(
                "工作日历状态只能通过发布或启用/停用流程变更");
    }

    private WorkCalendar publishLocked(
            WorkCalendar current,
            long expectedVersion,
            String reason,
            String action,
            String resourceType) {
        if (current.status() != CalendarStatus.DRAFT) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_INVALID_TRANSITION",
                    "只有草稿工作日历版本可以发布");
        }
        requireVersion(current.rowVersion(), expectedVersion);
        requireComplete(current);
        if (repository.hasPublishedOverlap(
                current.calendarId(),
                current.effectiveFrom(),
                current.effectiveTo(),
                current.calendarVersionId())) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_VERSION_OVERLAP",
                    "已发布工作日历版本期间发生重叠");
        }
        requireAdjacentTimeline(
                current, allVersions(current.calendarId()));
        List<WorkCalendarDay> days = allDays(
                current.calendarVersionId(),
                current.effectiveFrom(),
                current.effectiveTo().minusDays(1));
        String canonicalDigest =
                CalendarSnapshotDigest.digest(current, days);
        if (!canonicalDigest.equals(current.snapshotDigest())) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_SNAPSHOT_DIGEST_MISMATCH",
                    "工作日历草稿摘要与 canonical 内容不一致");
        }
        String normalizedReason = AttendanceSetupRules.reason(reason);
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        if (!repository.publishVersion(
                current.calendarVersionId(),
                expectedVersion,
                canonicalDigest,
                actor,
                normalizedReason,
                now)) {
            throw new OptimisticLockingFailureException("work calendar version changed");
        }
        WorkCalendar published = repository.findVersion(
                current.calendarVersionId()).orElseThrow();
        if (!canonicalDigest.equals(published.snapshotDigest())) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_SNAPSHOT_DIGEST_MISMATCH",
                    "发布响应与持久化日历摘要不一致");
        }
        audit(
                actor,
                action,
                resourceType,
                "WORK_CALENDAR".equals(resourceType)
                        ? current.calendarId()
                        : current.calendarVersionId(),
                normalizedReason,
                current,
                published);
        return published;
    }

    private WorkCalendar deactivateLocked(
            WorkCalendar current,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String reason,
            String action,
            String resourceType) {
        if (current.status() != CalendarStatus.PUBLISHED) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_INVALID_TRANSITION",
                    "只有已发布工作日历版本可以停用");
        }
        requireVersion(current.rowVersion(), expectedVersion);
        LocalDate boundary = requireFutureDeactivation(
                current, businessEffectiveFrom);
        List<WorkCalendar> versions = allVersions(current.calendarId());
        requireContinuousPublishedTimeline(versions);
        List<WorkCalendar> successors = versions.stream()
                .filter(version -> !version.calendarVersionId()
                        .equals(current.calendarVersionId()))
                .filter(version -> version.status() == CalendarStatus.PUBLISHED)
                .filter(version -> version.effectiveFrom().equals(boundary))
                .toList();
        if (successors.size() > 1) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_AMBIGUOUS_SUCCESSOR",
                    "停用边界存在多个工作日历承接版本");
        }
        WorkCalendar successor = successors.isEmpty()
                ? null : successors.getFirst();
        if (successor != null
                && !current.effectiveTo().equals(boundary)) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_NON_ADJACENT_SUCCESSOR",
                    "工作日历承接版本必须与当前版本半开期间相邻");
        }
        if (successor == null
                && repository.hasActiveGroupReferencesAtOrAfter(
                current.calendarId(), boundary)) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_VERSION_GAP",
                    "被考勤组引用的工作日历时间线必须有相邻发布版本承接");
        }
        String normalizedReason = AttendanceSetupRules.reason(reason);
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        if (!repository.scheduleVersionDeactivation(
                current.calendarVersionId(),
                expectedVersion,
                boundary,
                successor == null ? null : successor.calendarVersionId(),
                actor,
                normalizedReason,
                now)) {
            throw new OptimisticLockingFailureException(
                    "work calendar version changed");
        }
        verifyDeactivationResolution(
                current, successor, boundary, now);
        WorkCalendar updated = repository.findVersion(
                current.calendarVersionId()).orElseThrow();
        audit(
                actor,
                action,
                resourceType,
                "WORK_CALENDAR".equals(resourceType)
                        ? current.calendarId()
                        : current.calendarVersionId(),
                normalizedReason,
                current,
                updated);
        return updated;
    }

    private WorkCalendar upsertDays(
            WorkCalendar calendar,
            ReplaceDaysCommand command,
            long expectedVersion) {
        if (calendar.status() != CalendarStatus.DRAFT) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_IMMUTABLE",
                    "只有草稿工作日历版本可以修改日期");
        }
        requireVersion(calendar.rowVersion(), expectedVersion);
        String reason = AttendanceSetupRules.reason(command.reason());
        if (command.days() == null
                || command.days().isEmpty()
                || command.days().size() > 366) {
            throw AttendanceSetupRules.invalid("日历日必须包含 1 至 366 条记录");
        }
        Set<LocalDate> dates = new HashSet<>();
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        List<WorkCalendarDay> days = command.days().stream()
                .map(day -> validateDay(calendar, day, dates, reason, actor, now))
                .toList();
        repository.replaceDays(
                calendar.calendarVersionId(), days, expectedVersion,
                actor, reason, now);
        WorkCalendar updated =
                repository.findCalendar(calendar.calendarId()).orElseThrow();
        audit(actor, "WORK_CALENDAR_DAYS_UPSERTED", "WORK_CALENDAR_VERSION",
                calendar.calendarVersionId(), reason, calendar, updated);
        return updated;
    }

    private WorkCalendarDay validateDay(
            WorkCalendar calendar,
            CalendarDayCommand command,
            Set<LocalDate> dates,
            String reason,
            String actor,
            Instant now) {
        if (command.businessDate() == null
                || command.dayType() == null
                || command.businessDate().getYear() != calendar.calendarYear()) {
            throw AttendanceSetupRules.invalid("日历日必须属于日历年度并声明日期类型");
        }
        if (!dates.add(command.businessDate())) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_DATE_DUPLICATE", "工作日历日期重复");
        }
        if (command.businessDate().isBefore(calendar.effectiveFrom())
                || !command.businessDate().isBefore(calendar.effectiveTo())) {
            throw AttendanceSetupRules.invalid("日历日必须位于版本生效期间");
        }
        if (command.shiftVersionOverrideId() != null) {
            var version = shiftRepository.findVersion(command.shiftVersionOverrideId())
                    .filter(value -> value.status() == VersionStatus.PUBLISHED)
                    .filter(value -> !command.businessDate().isBefore(value.effectiveFrom()))
                    .filter(value -> value.effectiveTo() == null
                            || command.businessDate().isBefore(value.effectiveTo()))
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            var template = shiftRepository.findTemplate(version.shiftId())
                    .filter(value -> value.legalEntityId().equals(calendar.legalEntityId()))
                    .filter(value -> value.locationId().equals(calendar.locationId()))
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            if (!version.timeZone().equals(calendar.timeZone())
                    || template.status()
                    != com.szsemicon.hr.attendance.domain.AttendanceGroupModels
                            .LifecycleStatus.ACTIVE) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
        }
        WorkCalendarDay current =
                repository.findDay(
                        calendar.calendarVersionId(), command.businessDate(), clock.instant())
                        .orElse(null);
        if (current == null) {
            return new WorkCalendarDay(
                    UUID.randomUUID().toString(), calendar.calendarId(),
                    calendar.calendarVersionId(), command.businessDate(),
                    command.dayType(), command.shiftVersionOverrideId(),
                    0, reason, actor, now, actor, now);
        }
        return new WorkCalendarDay(
                current.calendarDayId(), current.calendarId(),
                current.calendarVersionId(), current.businessDate(),
                command.dayType(), command.shiftVersionOverrideId(),
                current.rowVersion() + 1,
                reason, current.createdBy(), current.createdAt(), actor, now);
    }

    private WorkCalendar requireCalendar(String calendarId, String capability) {
        capabilityService.require(capability);
        WorkCalendar calendar = repository.findCalendar(calendarId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireLegalEntity(calendar.legalEntityId(), capability);
        return calendar;
    }

    private WorkCalendar requireCalendarVersion(
            String calendarId, String versionId, String capability) {
        capabilityService.require(capability);
        WorkCalendar calendar = repository.findVersion(versionId)
                .filter(value -> value.calendarId().equals(calendarId))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireLegalEntity(calendar.legalEntityId(), capability);
        return calendar;
    }

    private void requireLegalEntity(String legalEntityId, String capability) {
        if (!peopleRepository.canAccessLegalEntity(
                principalProvider.currentPrincipalId(), capability,
                legalEntityId, clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private CalendarCommand normalize(CalendarCommand command) {
        if (command.calendarYear() < 2000 || command.calendarYear() > 2100) {
            throw AttendanceSetupRules.invalid("日历年度必须在 2000 至 2100 之间");
        }
        AttendanceSetupRules.halfOpenPeriod(
                command.effectiveFrom(), command.effectiveTo());
        if (command.effectiveFrom().getYear() != command.calendarYear()
                || command.effectiveTo().isAfter(
                        LocalDate.of(command.calendarYear() + 1, 1, 1))) {
            throw AttendanceSetupRules.invalid(
                    "日历版本期间必须位于声明年度内");
        }
        return new CalendarCommand(
                Objects.requireNonNull(command.legalEntityId()),
                Objects.requireNonNull(command.locationId()),
                AttendanceSetupRules.code(command.code()),
                AttendanceSetupRules.name(command.name()),
                command.calendarYear(),
                AttendanceSetupRules.timeZone(command.timeZone()),
                Objects.requireNonNull(command.effectiveFrom()),
                Objects.requireNonNull(command.effectiveTo()),
                AttendanceSetupRules.reason(command.reason()));
    }

    private CalendarVersionCommand normalizeVersion(
            WorkCalendar family, CalendarVersionCommand command) {
        CalendarCommand normalized = normalize(new CalendarCommand(
                family.legalEntityId(), family.locationId(), family.code(),
                command.name(), command.calendarYear(), command.timeZone(),
                command.effectiveFrom(), command.effectiveTo(), command.reason()));
        var locationRevisions = groupRepository.resolveLocationRevisions(
                family.locationId(), normalized.effectiveFrom(), clock.instant());
        if (locationRevisions.size() != 1
                || !locationRevisions.getFirst().legalEntityId()
                        .equals(family.legalEntityId())
                || !locationRevisions.getFirst().timeZone()
                        .equals(normalized.timeZone())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return new CalendarVersionCommand(
                normalized.name(), normalized.calendarYear(),
                normalized.timeZone(), normalized.effectiveFrom(),
                normalized.effectiveTo(), normalized.reason());
    }

    private boolean same(WorkCalendar calendar, CalendarCommand command) {
        return calendar.legalEntityId().equals(command.legalEntityId())
                && calendar.code().equals(command.code())
                && calendar.locationId().equals(command.locationId())
                && calendar.name().equals(command.name())
                && calendar.calendarYear() == command.calendarYear()
                && calendar.timeZone().equals(command.timeZone())
                && calendar.effectiveFrom().equals(command.effectiveFrom())
                && calendar.effectiveTo().equals(command.effectiveTo());
    }

    private boolean sameVersion(
            WorkCalendar calendar, CalendarVersionCommand command) {
        return calendar.name().equals(command.name())
                && calendar.calendarYear() == command.calendarYear()
                && calendar.timeZone().equals(command.timeZone())
                && calendar.effectiveFrom().equals(command.effectiveFrom())
                && calendar.effectiveTo().equals(command.effectiveTo());
    }

    private void requireComplete(WorkCalendar current) {
        List<WorkCalendarDay> days = allDays(
                current.calendarVersionId(),
                current.effectiveFrom(),
                current.effectiveTo().minusDays(1));
        long expectedDays = current.effectiveFrom()
                .datesUntil(current.effectiveTo()).count();
        if (days.size() != expectedDays) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_INCOMPLETE",
                    "发布前必须完整配置 effective interval 内每个日历日");
        }
    }

    private LocalDate requireFutureDeactivation(
            WorkCalendar current, LocalDate boundary) {
        if (boundary == null
                || !boundary.isAfter(LocalDate.now(clock))
                || !boundary.isAfter(current.effectiveFrom())) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_HISTORY_PROTECTED",
                    "工作日历停用必须声明晚于今天和版本起点的未来业务边界");
        }
        if (boundary.isAfter(current.effectiveTo())) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_DEACTIVATION_OUT_OF_RANGE",
                    "工作日历停用边界不得晚于版本的半开期间终点");
        }
        return boundary;
    }

    private void requireAdjacentTimeline(
            WorkCalendar candidate, List<WorkCalendar> versions) {
        List<WorkCalendar> published = versions.stream()
                .filter(value -> value.status() == CalendarStatus.PUBLISHED)
                .sorted(Comparator.comparing(WorkCalendar::effectiveFrom))
                .toList();
        WorkCalendar previous = published.stream()
                .filter(value -> value.effectiveFrom()
                        .isBefore(candidate.effectiveFrom()))
                .reduce((left, right) -> right)
                .orElse(null);
        WorkCalendar next = published.stream()
                .filter(value -> value.effectiveFrom()
                        .isAfter(candidate.effectiveFrom()))
                .findFirst()
                .orElse(null);
        if (previous != null
                && !previous.effectiveTo().equals(candidate.effectiveFrom())) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_VERSION_GAP",
                    "工作日历版本时间线不能出现间隙");
        }
        if (next != null
                && !candidate.effectiveTo().equals(next.effectiveFrom())) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_VERSION_GAP",
                    "工作日历版本时间线不能出现间隙");
        }
    }

    private void requireContinuousPublishedTimeline(
            List<WorkCalendar> versions) {
        List<WorkCalendar> published = versions.stream()
                .filter(version -> version.status() == CalendarStatus.PUBLISHED)
                .sorted(Comparator.comparing(WorkCalendar::effectiveFrom))
                .toList();
        for (int index = 1; index < published.size(); index++) {
            WorkCalendar previous = published.get(index - 1);
            WorkCalendar next = published.get(index);
            if (!previous.effectiveTo().equals(next.effectiveFrom())) {
                throw AttendanceSetupRules.conflict(
                        "WORK_CALENDAR_VERSION_GAP",
                        "工作日历停用前的已发布时间线已存在间隙");
            }
        }
    }

    private void verifyDeactivationResolution(
            WorkCalendar current,
            WorkCalendar successor,
            LocalDate boundary,
            Instant knowledgeAsOf) {
        List<WorkCalendar> before = repository.resolvePublishedVersions(
                current.calendarId(), boundary.minusDays(1), knowledgeAsOf);
        if (boundary.minusDays(1).isBefore(current.effectiveFrom())
                || before.size() != 1
                || !before.getFirst().calendarVersionId()
                .equals(current.calendarVersionId())
                || before.getFirst().status() != CalendarStatus.PUBLISHED) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_DEACTIVATION_INVARIANT",
                    "工作日历停用未能保持边界前的唯一发布解析");
        }
        List<WorkCalendar> after = repository.resolvePublishedVersions(
                current.calendarId(), boundary, knowledgeAsOf);
        if (successor == null) {
            if (!after.isEmpty()) {
                throw AttendanceSetupRules.conflict(
                        "WORK_CALENDAR_DEACTIVATION_INVARIANT",
                        "工作日历终止边界解析不唯一");
            }
            return;
        }
        if (after.size() != 1
                || !after.getFirst().calendarVersionId()
                .equals(successor.calendarVersionId())
                || after.getFirst().status() != CalendarStatus.PUBLISHED) {
            throw AttendanceSetupRules.conflict(
                    "WORK_CALENDAR_DEACTIVATION_INVARIANT",
                    "工作日历停用未由相邻发布版本原子承接");
        }
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) {
            throw new OptimisticLockingFailureException("work calendar version changed");
        }
    }

    private void requireDayRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw AttendanceSetupRules.invalid("日历查询日期范围无效");
        }
    }

    private List<WorkCalendar> allCalendars(
            String principalId, Integer year, Instant at) {
        java.util.ArrayList<WorkCalendar> calendars =
                new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<WorkCalendar> page = repository.listCalendars(
                    principalId,
                    CapabilityCodes.ATTENDANCE_SETUP_READ,
                    year,
                    100,
                    offset,
                    at);
            calendars.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(calendars);
            }
            offset += page.size();
        }
    }

    private List<WorkCalendar> allVersions(String calendarId) {
        java.util.ArrayList<WorkCalendar> versions =
                new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<WorkCalendar> page =
                    repository.listVersions(calendarId, 100, offset);
            versions.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(versions);
            }
            offset += page.size();
        }
    }

    private List<WorkCalendarDay> allDays(
            String calendarVersionId, LocalDate from, LocalDate to) {
        java.util.ArrayList<WorkCalendarDay> days =
                new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<WorkCalendarDay> page = repository.listDays(
                    calendarVersionId, from, to, 100, offset);
            days.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(days);
            }
            offset += page.size();
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
}
