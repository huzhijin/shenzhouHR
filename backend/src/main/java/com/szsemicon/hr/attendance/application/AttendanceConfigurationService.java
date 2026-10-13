package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.ConfigurationSnapshot;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import com.szsemicon.hr.attendance.domain.CalendarModels.CalendarStatus;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import com.szsemicon.hr.attendance.domain.ShiftModels.VersionStatus;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceConfigurationService {

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository peopleRepository;
    private final AttendanceGroupRepository groupRepository;
    private final CalendarRepository calendarRepository;
    private final ShiftRepository shiftRepository;
    private final AttendancePolicyService policyService;
    private final SecurityTokenService tokenService;
    private final Clock clock;

    public AttendanceConfigurationService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository peopleRepository,
            AttendanceGroupRepository groupRepository,
            CalendarRepository calendarRepository,
            ShiftRepository shiftRepository,
            AttendancePolicyService policyService,
            SecurityTokenService tokenService,
            Clock clock) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.peopleRepository = peopleRepository;
        this.groupRepository = groupRepository;
        this.calendarRepository = calendarRepository;
        this.shiftRepository = shiftRepository;
        this.policyService = policyService;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConfigurationSnapshot resolve(String employeeId, LocalDate businessDate) {
        return resolve(employeeId, businessDate, clock.instant());
    }

    @Transactional(readOnly = true)
    public ConfigurationSnapshot resolve(
            String employeeId,
            LocalDate businessDate,
            Instant knowledgeAsOf) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        if (employeeId == null || businessDate == null) {
            throw AttendanceSetupRules.invalid("员工和业务日必填");
        }
        String principal = principalProvider.currentPrincipalId();
        if (!peopleRepository.canAccessEmployee(
                principal, CapabilityCodes.ATTENDANCE_SETUP_READ,
                employeeId, businessDate, clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (groupRepository.hasAssignmentCompanyMismatch(
                employeeId, businessDate, knowledgeAsOf)) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        List<Assignment> assignments =
                groupRepository.resolveAssignments(employeeId, businessDate, knowledgeAsOf);
        if (assignments.size() > 1) {
            throw AttendanceSetupRules.conflict(
                    "ASSIGNMENT_AMBIGUOUS",
                    "同一业务日解析到多个有效考勤组分配");
        }
        Assignment assignment = assignments.isEmpty() ? null : assignments.getFirst();
        String monthlyKey = employeeId + ":" + businessDate.getYear() + "-"
                + "%02d".formatted(businessDate.getMonthValue());
        if (assignment == null) {
            return missing(
                    "ASSIGNMENT_MISSING", employeeId, businessDate,
                    null, null, null, monthlyKey,
                    "业务日没有有效考勤组期限分配");
        }
        var employee = peopleRepository.findEmployeeAsOf(employeeId, businessDate)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        List<AttendanceGroup> groupRevisions =
                groupRepository.resolveGroupRevisions(
                        assignment.groupId(), businessDate, knowledgeAsOf);
        if (groupRevisions.size() != 1) {
            return missing(
                    groupRevisions.isEmpty()
                            ? "GROUP_REVISION_MISSING"
                            : "GROUP_REVISION_AMBIGUOUS",
                    employeeId, businessDate, assignment.groupId(),
                    null, null, monthlyKey,
                    "业务日必须恰好解析一个不可变考勤组版本");
        }
        AttendanceGroup group = groupRevisions.getFirst();
        if (!group.companyId().equals(employee.companyId())
                || !peopleRepository.canAccessCompany(
                        principal,
                        CapabilityCodes.ATTENDANCE_SETUP_READ,
                        group.companyId(),
                        clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (group.status() != LifecycleStatus.ACTIVE) {
            return missing(
                    "GROUP_INACTIVE", employeeId, businessDate,
                    group.groupId(), null, null, monthlyKey,
                    "期限分配指向未启用的考勤组");
        }
        List<WorkCalendar> calendars =
                calendarRepository.resolvePublishedVersions(
                        group.calendarId(), businessDate, knowledgeAsOf);
        if (calendars.size() != 1) {
            return missing(
                    calendars.isEmpty()
                            ? "CALENDAR_VERSION_MISSING"
                            : "CALENDAR_VERSION_AMBIGUOUS",
                    employeeId, businessDate,
                    group.groupId(), null, null, monthlyKey,
                    "业务日必须恰好解析一个已发布日历版本");
        }
        WorkCalendar effectiveCalendar = calendars.getFirst();
        var locationCandidates = groupRepository.resolveLocationRevisions(
                        group.locationId(), businessDate, knowledgeAsOf)
                .stream()
                .filter(value -> value.locationRevisionId()
                        .equals(group.locationRevisionId()))
                .toList();
        var location = locationCandidates.size() == 1
                ? locationCandidates.getFirst() : null;
        if (location == null
                || location.status() != LifecycleStatus.ACTIVE
                || !group.companyId().equals(location.companyId())
                || !group.companyId().equals(effectiveCalendar.companyId())
                || !effectiveCalendar.locationId().equals(location.locationId())
                || !effectiveCalendar.timeZone().equals(location.timeZone())
                || effectiveCalendar.status() != CalendarStatus.PUBLISHED) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        WorkCalendarDay day =
                calendarRepository.findDay(
                        effectiveCalendar.calendarVersionId(), businessDate, knowledgeAsOf)
                        .orElse(null);
        if (day == null) {
            return missing(
                    "CALENDAR_DAY_MISSING", employeeId, businessDate,
                    group.groupId(), null, null, monthlyKey,
                    "对应年度工作日历缺少该业务日");
        }
        ShiftVersion shift = null;
        if (day.shiftVersionOverrideId() != null) {
            shift = shiftRepository.findVersion(
                            day.shiftVersionOverrideId(), knowledgeAsOf)
                    .filter(value -> publishedAt(value, businessDate))
                    .orElse(null);
        } else if (day.workingDay()) {
            List<ShiftVersion> candidates =
                    shiftRepository.resolvePublishedAt(
                            group.shiftTemplateId(), businessDate, knowledgeAsOf);
            if (candidates.size() != 1) {
                return missing(
                        candidates.isEmpty()
                                ? "SHIFT_VERSION_MISSING"
                                : "SHIFT_VERSION_AMBIGUOUS",
                        employeeId, businessDate, group.groupId(),
                        day, null, monthlyKey,
                        "工作日必须恰好解析一个默认班次 family 的已发布版本");
            }
            shift = candidates.getFirst();
        }
        if (day.shiftVersionOverrideId() != null && shift == null) {
            return missing(
                    "SHIFT_OVERRIDE_VERSION_MISSING",
                    employeeId,
                    businessDate,
                    group.groupId(),
                    day,
                    null,
                    monthlyKey,
                    "日历日显式班次覆盖在当前知识时态不可解析");
        }
        if (day.workingDay() && shift == null) {
            return missing(
                    "SHIFT_VERSION_MISSING", employeeId, businessDate,
                    group.groupId(), day, null, monthlyKey,
                    "工作日没有可解析的不可变已发布班次版本");
        }
        if (shift != null) {
            var template = shiftRepository.findTemplate(shift.shiftId())
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            if (!template.companyId().equals(group.companyId())
                    || !template.locationId().equals(location.locationId())
                    || !shift.timeZone().equals(location.timeZone())) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
        }
        var bindings = policyService.resolveForGroup(
                group.groupId(), group.groupRevisionId(),
                businessDate, knowledgeAsOf);
        String configurationDigest = tokenService.digest(String.join(
                "|",
                group.companyId(),
                group.snapshotDigest(),
                location.snapshotDigest(),
                effectiveCalendar.snapshotDigest(),
                day.toString(),
                shift == null ? "NO_SHIFT" : shift.snapshotDigest(),
                bindings.toString(),
                monthlyKey));
        return new ConfigurationSnapshot(
                "RESOLVED", employeeId, businessDate, group.groupId(),
                group.groupRevisionId(), location.locationRevisionId(),
                effectiveCalendar.calendarVersionId(), day,
                shift, bindings, configurationDigest, monthlyKey,
                "按期限分配、年度日历、不可变班次版本和稳定策略绑定族确定性解析");
    }

    private ConfigurationSnapshot missing(
            String status,
            String employeeId,
            LocalDate businessDate,
            String groupId,
            WorkCalendarDay day,
            ShiftVersion shift,
            String monthlyKey,
            String explanation) {
        return new ConfigurationSnapshot(
                status, employeeId, businessDate, groupId,
                null, null, null, day, shift,
                List.of(), null, monthlyKey, explanation);
    }

    private boolean publishedAt(ShiftVersion value, LocalDate businessDate) {
        return value.status() == VersionStatus.PUBLISHED
                && !businessDate.isBefore(value.effectiveFrom())
                && (value.effectiveTo() == null
                    || businessDate.isBefore(value.effectiveTo()));
    }
}
