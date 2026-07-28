package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.application.AttendancePolicyCommands.BindingCommand;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Page;
import com.szsemicon.hr.attendance.domain.AttendancePolicyCatalog;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.Impact;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.ConfigurationSnapshot;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyBinding;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PunchDirection;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PunchInput;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationInput;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationResult;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationStatus;
import com.szsemicon.hr.attendance.domain.AttendancePolicyParameterValidator;
import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.SegmentType;
import com.szsemicon.hr.attendance.domain.ShiftSegmentValidator;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AttendancePolicyService {

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository peopleRepository;
    private final AttendanceGroupRepository groupRepository;
    private final AttendancePolicyRepository repository;
    private final AttendanceSetupIdempotencyService idempotencyService;
    private final AttendancePolicyImpactTokenService impactTokenService;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttendancePolicyService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository peopleRepository,
            AttendanceGroupRepository groupRepository,
            AttendancePolicyRepository repository,
            AttendanceSetupIdempotencyService idempotencyService,
            AttendancePolicyImpactTokenService impactTokenService,
            AuditService auditService,
            SecurityTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.peopleRepository = peopleRepository;
        this.groupRepository = groupRepository;
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.impactTokenService = impactTokenService;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<AttendancePolicyCatalog.TemplateDefinition> catalog() {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        return AttendancePolicyCatalog.templates();
    }

    @Transactional(readOnly = true)
    public Page<PolicyBinding> listBindings(
            String groupId, LocalDate asOf, int page, int size) {
        AttendanceSetupRules.page(page, size);
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        if (groupId != null) {
            requireGroup(groupId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        }
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        return new Page<>(
                repository.listBindings(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SETUP_READ,
                        groupId,
                        asOf,
                        size,
                        page * size,
                        at),
                repository.countBindings(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SETUP_READ,
                        groupId,
                        asOf,
                        at),
                page,
                size);
    }

    @Transactional
    public PolicyBinding createBinding(
            BindingCommand command,
            String impactToken,
            String idempotencyKey) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        BindingCommand normalized = normalize(command);
        AttendanceGroup group = requireGroup(
                normalized.groupId(),
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        String actor = principalProvider.currentPrincipalId();
        return idempotencyService.execute(
                actor,
                "CREATE_BINDING",
                "ATTENDANCE_POLICY_BINDING",
                group.groupId(),
                idempotencyKey,
                normalized,
                () -> {
                    capabilityService.require(
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
                    requireGroup(
                            normalized.groupId(),
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
                },
                () -> lockBindingGroup(normalized.groupId()),
                201,
                PolicyBinding::bindingRevisionId,
                PolicyBinding.class,
                () -> createBindingLocked(
                        normalized, impactToken, actor, idempotencyKey));
    }

    @Transactional
    public PolicyBinding updateBinding(
            String bindingId,
            BindingCommand command,
            long expectedVersion,
            String impactToken,
            String idempotencyKey) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        BindingCommand normalized = normalize(command);
        String actor = principalProvider.currentPrincipalId();
        requireBinding(
                bindingId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        return idempotencyService.execute(
                actor,
                "UPDATE_BINDING",
                "ATTENDANCE_POLICY_BINDING",
                bindingId,
                idempotencyKey,
                new BindingMutationRequest(expectedVersion, normalized),
                () -> {
                    capabilityService.require(
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
                    requireBinding(
                            bindingId,
                            CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
                },
                () -> lockBindingUpdateResource(bindingId),
                200,
                PolicyBinding::bindingRevisionId,
                PolicyBinding.class,
                () -> updateBindingLocked(
                        bindingId,
                        normalized,
                        expectedVersion,
                        impactToken,
                        actor,
                        idempotencyKey));
    }

    private void lockBindingGroup(String groupId) {
        groupRepository.lockGroup(groupId);
        AttendanceGroup locked = requireGroup(
                groupId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        groupRepository.lockGroupRevision(locked.groupRevisionId());
    }

    private void lockBindingUpdateResource(String bindingId) {
        PolicyBinding binding = repository.findBinding(bindingId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        lockBindingGroup(binding.groupId());
        repository.lockBindingFamily(binding.bindingId());
    }

    private PolicyBinding createBindingLocked(
            BindingCommand command,
            String impactToken,
            String actor,
            String idempotencyKey) {
        AttendanceGroup group = requireGroupRevision(
                command,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        requireFuture(command.effectiveFrom());
        requireContainedByGroup(group, command);
        String policyVersionDigest = requirePublishedVersion(group, command);
        requireCurrentImpact(command, impactToken, actor);
        if (repository.hasOtherFamily(
                command.policyKind(), command.groupId(), null)) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_POLICY_BINDING_FAMILY_EXISTS",
                    "考勤组同一策略类型只能存在一个稳定绑定族");
        }
        Instant now = clock.instant();
        PolicyBinding created = new PolicyBinding(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, group.legalEntityId(), command.policyKind(),
                command.policyVersionId(), group.groupId(),
                group.groupRevisionId(),
                command.effectiveFrom(), command.effectiveTo(),
                LifecycleStatus.ACTIVE,
                bindingDigest(command, policyVersionDigest),
                0, command.reason(),
                actor, now, actor, now);
        repository.insertBinding(
                created, AttendanceSetupRules.idempotencyKey(idempotencyKey));
        auditService.record(
                actor, "ATTENDANCE_POLICY_BINDING_CREATED",
                "ATTENDANCE_POLICY_BINDING", created.bindingId(), "SUCCESS",
                command.reason(), null, tokenService.digest(created.toString()));
        return created;
    }

    private PolicyBinding updateBindingLocked(
            String bindingId,
            BindingCommand command,
            long expectedVersion,
            String impactToken,
            String actor,
            String idempotencyKey) {
        PolicyBinding current = requireBinding(
                bindingId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        if (current.status() != LifecycleStatus.ACTIVE) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_POLICY_BINDING_INACTIVE",
                    "已停用策略绑定不可更新");
        }
        if (current.policyKind() != command.policyKind()
                || !current.groupId().equals(command.groupId())) {
            throw AttendanceSetupRules.invalid(
                    "策略绑定更新不能改变策略类型或考勤组");
        }
        requireVersion(current.rowVersion(), expectedVersion);
        requireFuture(command.effectiveFrom());
        requireSuccessorBoundary(current, command.effectiveFrom());
        requirePublishedSourceThrough(current, command.effectiveFrom());
        AttendanceGroup group = requireGroupRevision(
                command,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        requireContainedByGroup(group, command);
        String policyVersionDigest = requirePublishedVersion(group, command);
        requireCurrentImpact(command, impactToken, actor);
        if (repository.hasOtherFamily(
                command.policyKind(), command.groupId(), current.bindingId())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_POLICY_BINDING_FAMILY_AMBIGUOUS",
                    "考勤组同一策略类型存在多个稳定绑定族");
        }
        Instant now = clock.instant();
        PolicyBinding replacement = new PolicyBinding(
                current.bindingId(), UUID.randomUUID().toString(),
                current.revisionNumber() + 1, current.legalEntityId(),
                command.policyKind(), command.policyVersionId(), command.groupId(),
                command.groupRevisionId(),
                command.effectiveFrom(), command.effectiveTo(),
                LifecycleStatus.ACTIVE,
                bindingDigest(command, policyVersionDigest),
                current.rowVersion() + 1,
                command.reason(), current.createdBy(), current.createdAt(), actor, now);
        if (!repository.updateBinding(replacement, expectedVersion)) {
            throw new OptimisticLockingFailureException(
                    "attendance policy binding changed");
        }
        auditService.record(
                actor, "ATTENDANCE_POLICY_BINDING_REPLACED",
                "ATTENDANCE_POLICY_BINDING", replacement.bindingId(), "SUCCESS",
                command.reason(), tokenService.digest(current.toString()),
                tokenService.digest(replacement.toString()));
        return replacement;
    }

    static void requireSuccessorBoundary(
            PolicyBinding current, LocalDate successorEffectiveFrom) {
        if (!successorEffectiveFrom.isAfter(current.effectiveFrom())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_POLICY_BINDING_BOUNDARY_INVALID",
                    "策略绑定 successor 生效日必须晚于 predecessor 生效日");
        }
        if (current.effectiveTo() != null
                && successorEffectiveFrom.isAfter(current.effectiveTo())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_POLICY_BINDING_GAP",
                    "有限期间策略绑定 successor 不能晚于 predecessor 的计划结束边界");
        }
    }

    private void requirePublishedSourceThrough(
            PolicyBinding current, LocalDate successorEffectiveFrom) {
        if (!repository.publishedVersionMatchesKind(
                current.legalEntityId(),
                current.policyVersionId(),
                current.policyKind(),
                current.effectiveFrom(),
                successorEffectiveFrom)) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_POLICY_BINDING_SOURCE_PERIOD_INVALID",
                    "策略绑定 predecessor 引用的策略版本无法覆盖派生有效期");
        }
    }

    @Transactional(readOnly = true)
    public Impact previewImpact(BindingCommand command) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        BindingCommand normalized = normalize(command);
        AttendanceGroup group = requireGroupRevision(
                normalized,
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        requireFuture(normalized.effectiveFrom());
        requireContainedByGroup(group, normalized);
        requirePublishedVersion(group, normalized);
        int assignmentCount = groupRepository.countEffectiveAssignments(
                normalized.groupId(),
                normalized.effectiveFrom(),
                normalized.effectiveTo());
        var token = impactTokenService.issue(
                principalProvider.currentPrincipalId(),
                normalized,
                1,
                assignmentCount);
        return new Impact(
                1,
                assignmentCount,
                "REAL_ATTENDANCE_ASSIGNMENTS",
                token.token(),
                token.expiresAt());
    }

    private void requireCurrentImpact(
            BindingCommand command,
            String impactToken,
            String actor) {
        impactTokenService.requireCurrent(
                impactToken,
                actor,
                command,
                1,
                groupRepository.countEffectiveAssignments(
                        command.groupId(),
                        command.effectiveFrom(),
                        command.effectiveTo()));
    }

    List<SimulationResult> simulateResolved(
            SimulationInput input,
            ConfigurationSnapshot configuration,
            AttendanceMonthlyExemptionUsageProvider.UsageSnapshot usage) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        validateSimulationInput(input, configuration);
        Map<PolicyKind, PolicyBinding> bindings = new EnumMap<>(PolicyKind.class);
        for (PolicyBinding binding : configuration.policyBindings()) {
            if (bindings.put(binding.policyKind(), binding) != null) {
                throw AttendanceSetupRules.conflict(
                        "POLICY_AMBIGUOUS", "同一策略类型解析出多个绑定");
            }
        }
        if (bindings.size() != PolicyKind.values().length) {
            throw AttendanceSetupRules.conflict(
                    "POLICY_MISSING", "权威试算要求三类策略恰好各一个绑定");
        }
        PolicyBinding meal = bindings.get(PolicyKind.MEAL_DEDUCTION);
        PolicyBinding grace = bindings.get(PolicyKind.LATE_GRACE);
        PolicyBinding monthly = bindings.get(PolicyKind.MONTHLY_LATE_EXEMPTION);
        Map<String, Object> mealParameters = parameters(meal.policyVersionId());
        Map<String, Object> graceParameters = parameters(grace.policyVersionId());
        Map<String, Object> monthlyParameters = parameters(monthly.policyVersionId());
        requireValidPublishedParameters(PolicyKind.MEAL_DEDUCTION, mealParameters);
        requireValidPublishedParameters(PolicyKind.LATE_GRACE, graceParameters);
        requireValidPublishedParameters(
                PolicyKind.MONTHLY_LATE_EXEMPTION, monthlyParameters);
        LateDecision late = lateDecision(
                input, configuration,
                graceParameters, monthlyParameters, usage);
        return List.of(
                simulateMeal(input, configuration,
                        mealParameters, meal, usage),
                lateResult(grace, configuration, late, usage, false),
                lateResult(monthly, configuration, late, usage, true));
    }

    private void requireValidPublishedParameters(
            PolicyKind kind, Map<String, Object> parameters) {
        var issues = AttendancePolicyParameterValidator.validate(
                        kind,
                        parameters.entrySet().stream()
                                .map(entry -> new com.szsemicon.hr.attendance.domain
                                        .AttendancePolicyLifecycleModels.ParameterValue(
                                                entry.getKey(), entry.getValue()))
                                .toList());
        if (!issues.isEmpty()) {
            throw AttendanceSetupRules.conflict(
                    "POLICY_CONFIGURATION_INVALID",
                    "已发布考勤策略参数不符合受控目录");
        }
    }

    @Transactional(readOnly = true)
    public List<PolicyBinding> resolveForGroup(String groupId, LocalDate businessDate) {
        return resolveForGroup(groupId, businessDate, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<PolicyBinding> resolveForGroup(
            String groupId, LocalDate businessDate, Instant knowledgeAsOf) {
        List<AttendanceGroup> revisions = groupRepository.resolveGroupRevisions(
                groupId, businessDate, knowledgeAsOf);
        if (revisions.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    revisions.isEmpty()
                            ? "GROUP_REVISION_MISSING"
                            : "GROUP_REVISION_AMBIGUOUS",
                    "策略绑定解析要求唯一考勤组 revision");
        }
        return resolveForGroup(
                groupId,
                revisions.getFirst().groupRevisionId(),
                businessDate,
                knowledgeAsOf);
    }

    @Transactional(readOnly = true)
    public List<PolicyBinding> resolveForGroup(
            String groupId,
            String groupRevisionId,
            LocalDate businessDate,
            Instant knowledgeAsOf) {
        List<PolicyBinding> candidates = repository.resolveBindings(
                groupId, groupRevisionId, businessDate, knowledgeAsOf);
        Map<PolicyKind, List<PolicyBinding>> byKind = new EnumMap<>(PolicyKind.class);
        candidates.forEach(binding ->
                byKind.computeIfAbsent(binding.policyKind(), ignored -> new ArrayList<>())
                        .add(binding));
        List<PolicyBinding> resolved = new ArrayList<>();
        for (PolicyKind kind : PolicyKind.values()) {
            List<PolicyBinding> matching = byKind.getOrDefault(kind, List.of());
            if (matching.isEmpty()) {
                throw AttendanceSetupRules.conflict(
                        "POLICY_MISSING",
                        "考勤组缺少已发布且在业务日有效的 " + kind.name() + " 策略");
            }
            if (matching.size() > 1) {
                throw AttendanceSetupRules.conflict(
                        "ATTENDANCE_POLICY_AMBIGUOUS",
                        "考勤基础策略解析出现多绑定歧义");
            }
            resolved.add(matching.getFirst());
        }
        return List.copyOf(resolved);
    }

    private SimulationResult simulateMeal(
            SimulationInput input,
            ConfigurationSnapshot configuration,
            Map<String, Object> parameters,
            PolicyBinding binding,
            AttendanceMonthlyExemptionUsageProvider.UsageSnapshot usage) {
        boolean enabled = bool(parameters, "enabled");
        int configuredDeduction = integer(parameters, "deductionMinutes");
        int trigger = integer(parameters, "triggerMinutes");
        DayType dayType = configuration.calendarDay().dayType();
        Object applicableValue = parameters.get("applicableDayTypes");
        boolean applicable = applicableValue instanceof List<?> values
                && values.contains(dayType.name());
        ZoneId zone = ZoneId.of(configuration.shiftVersion().timeZone());
        Instant firstEntry = punchTime(
                input.punches(), PunchDirection.ENTRY, zone, true, null);
        Instant lastExit = punchTime(
                input.punches(), PunchDirection.EXIT, zone, false, null);
        Instant[] window = mealWindow(
                input.businessDate(),
                Objects.toString(parameters.get("mealWindowStart"), ""),
                Objects.toString(parameters.get("mealWindowEnd"), ""),
                zone);
        boolean intervalValid = firstEntry != null
                && lastExit != null
                && lastExit.isAfter(firstEntry);
        long attendedMinutes = intervalValid
                ? Duration.between(firstEntry, lastExit).toMinutes()
                : 0;
        boolean serverWindowMatched = intervalValid
                && !firstEntry.isAfter(window[0])
                && !lastExit.isBefore(window[1]);
        boolean matched = enabled
                && serverWindowMatched
                && applicable
                && attendedMinutes >= trigger;
        return new SimulationResult(
                binding.policyKind(),
                matched ? SimulationStatus.MATCHED : SimulationStatus.NOT_MATCHED,
                binding.policyVersionId(), configuration.configurationDigest(),
                matched, false,
                null, 0, usage.provenance(), usage.knowledgeTime(),
                matched ? configuredDeduction : null, null, null,
                matched
                        ? "服务端根据打卡时间、班次时区、已发布晚餐窗口、门槛和日期类型判定命中"
                        : "服务端计算的打卡区间未同时覆盖晚餐窗口、门槛和日期类型",
                false);
    }

    private LateDecision lateDecision(
            SimulationInput input,
            ConfigurationSnapshot configuration,
            Map<String, Object> graceParameters,
            Map<String, Object> monthlyParameters,
            AttendanceMonthlyExemptionUsageProvider.UsageSnapshot usage) {
        int graceMinutes = integer(graceParameters, "graceMinutes");
        int monthlyGraceMinutes = integer(monthlyParameters, "graceMinutes");
        int monthlyUses = integer(monthlyParameters, "monthlyUses");
        Object resetValue = monthlyParameters.get("resetOnGroupChange");
        if (!bool(graceParameters, "enabled")
                || !bool(monthlyParameters, "enabled")
                || graceMinutes != monthlyGraceMinutes
                || monthlyUses != 1
                || !(resetValue instanceof Boolean reset)
                || reset) {
            throw AttendanceSetupRules.conflict(
                    "POLICY_CONFIGURATION_INVALID",
                    "迟到策略必须启用、阈值一致、monthlyUses=1 且换组不重置");
        }
        ZoneId zone = ZoneId.of(configuration.shiftVersion().timeZone());
        List<Segment> segments = configuration.shiftVersion().segments();
        int firstWorkIndex = -1;
        Segment firstWork = null;
        for (int index = 0; index < segments.size(); index++) {
            Segment candidate = segments.get(index);
            if (candidate.segmentType() == SegmentType.WORK
                    && (firstWork == null
                    || candidate.normalizedStartMinute() < firstWork.normalizedStartMinute())) {
                firstWork = candidate;
                firstWorkIndex = index;
            }
        }
        if (firstWork == null) {
            throw AttendanceSetupRules.conflict(
                    "SHIFT_WORK_SEGMENT_MISSING",
                    "已解析班次没有 WORK segment");
        }
        Instant scheduledStart = resolveLocalInstant(
                input.businessDate().atStartOfDay()
                        .plusMinutes(firstWork.normalizedStartMinute()),
                zone,
                true);
        Instant firstEntry =
                punchTime(
                        input.punches(), PunchDirection.ENTRY, zone, true,
                        segmentAssociation(firstWorkIndex));
        int lateMinutes = firstEntry == null
                ? Integer.MIN_VALUE
                : Math.max(0, Math.toIntExact(
                        Duration.between(scheduledStart, firstEntry).toMinutes()));
        if (firstEntry == null) {
            throw AttendanceSetupRules.conflict(
                    "PUNCH_ENTRY_MISSING",
                    "迟到试算必须包含关联首个 WORK segment 的有效 ENTRY");
        }
        boolean withinBoundary = lateMinutes > 0 && lateMinutes <= graceMinutes;
        boolean exempted = withinBoundary && usage.usedCount() < monthlyUses;
        SimulationStatus status = lateMinutes <= 0
                ? SimulationStatus.ON_TIME
                : exempted ? SimulationStatus.EXEMPTED : SimulationStatus.LATE;
        String explanation;
        if (firstEntry == null) {
            explanation = "没有可用于服务端迟到计算的 ENTRY 打卡";
        } else if (lateMinutes <= 0) {
            explanation = "迟到分钟必须大于 0，未消费自然月宽限次数";
        } else if (lateMinutes > graceMinutes) {
            explanation = "迟到分钟超过 "
                    + graceMinutes + " 分钟边界，未消费自然月宽限次数";
        } else if (usage.usedCount() >= monthlyUses) {
            explanation = "同一员工自然月宽限次数已使用，换组不会重置";
        } else {
            explanation = "命中同一员工自然月一次迟到宽限";
        }
        return new LateDecision(
                lateMinutes, status, withinBoundary, exempted, explanation);
    }

    private SimulationResult lateResult(
            PolicyBinding binding,
            ConfigurationSnapshot configuration,
            LateDecision late,
            AttendanceMonthlyExemptionUsageProvider.UsageSnapshot usage,
            boolean monthlyPolicy) {
        boolean matched = monthlyPolicy ? late.exempted() : late.withinGrace();
        boolean consumesAllowance = monthlyPolicy && late.exempted();
        return new SimulationResult(
                binding.policyKind(), late.status(),
                binding.policyVersionId(), configuration.configurationDigest(),
                matched, consumesAllowance, late.rawLateMinutes(),
                consumesAllowance ? 1 : 0,
                usage.provenance(), usage.knowledgeTime(),
                null, null, null, late.explanation(), false);
    }

    private record LateDecision(
            int rawLateMinutes,
            SimulationStatus status,
            boolean withinGrace,
            boolean exempted,
            String explanation) {
    }

    private void validateSimulationInput(
            SimulationInput input,
            ConfigurationSnapshot configuration) {
        if (input.employeeId() == null || input.employeeId().isBlank()
                || input.businessDate() == null
                || configuration == null
                || configuration.calendarDay() == null
                || configuration.shiftVersion() == null
                || input.correctionAsOf() == null) {
            throw AttendanceSetupRules.invalid("策略试算输入不完整");
        }
        AttendanceSetupRules.timeZone(configuration.shiftVersion().timeZone());
        try {
            ShiftSegmentValidator.validateAndNormalize(
                    configuration.shiftVersion().segments());
        } catch (IllegalArgumentException exception) {
            throw AttendanceSetupRules.invalid(exception.getMessage());
        }
        for (PunchInput punch : input.punches()) {
            if (punch == null
                    || punch.direction() == null
                    || punch.instant() == null) {
                throw AttendanceSetupRules.invalid("打卡试算输入无效");
            }
            if (punch.association() != null
                    && !punch.association().isBlank()
                    && !"SCHEDULED_WORK".equals(punch.association())) {
                throw AttendanceSetupRules.invalid(
                        "association 仅允许 SCHEDULED_WORK");
            }
            if (punch.workSegmentId() != null && !punch.workSegmentId().isBlank()) {
                int segmentIndex = parseSegmentAssociation(punch.workSegmentId());
                if (segmentIndex < 0
                        || segmentIndex >= configuration.shiftVersion().segments().size()
                        || configuration.shiftVersion().segments()
                                .get(segmentIndex).segmentType() != SegmentType.WORK) {
                    throw AttendanceSetupRules.invalid(
                            "workSegmentId 必须引用已解析班次的 WORK segment");
                }
            }
        }
    }

    private Instant punchTime(
            List<PunchInput> punches,
            PunchDirection direction,
            ZoneId zone,
            boolean earliest,
            String workSegmentId) {
        var values = punches.stream()
                .filter(punch -> punch.direction() == direction)
                .filter(punch -> punch.association() == null
                        || punch.association().isBlank()
                        || "SCHEDULED_WORK".equals(punch.association()))
                .filter(punch -> workSegmentId == null
                        || punch.workSegmentId() == null
                        || punch.workSegmentId().isBlank()
                        || workSegmentId.equals(punch.workSegmentId()))
                .map(punch -> punch.instant().toInstant());
        return earliest
                ? values.min(Instant::compareTo).orElse(null)
                : values.max(Instant::compareTo).orElse(null);
    }

    private String segmentAssociation(int segmentIndex) {
        return "segment-" + segmentIndex;
    }

    private int parseSegmentAssociation(String workSegmentId) {
        if (!workSegmentId.startsWith("segment-")) {
            return -1;
        }
        try {
            return Integer.parseInt(workSegmentId.substring("segment-".length()));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private Instant[] mealWindow(
            LocalDate businessDate,
            String configuredStart,
            String configuredEnd,
            ZoneId zone) {
        try {
            LocalTime start = LocalTime.parse(configuredStart);
            LocalTime end = LocalTime.parse(configuredEnd);
            LocalDateTime from = businessDate.atTime(start);
            LocalDateTime to = businessDate.atTime(end);
            if (!to.isAfter(from)) {
                to = to.plusDays(1);
            }
            return new Instant[]{
                    resolveLocalInstant(from, zone, true),
                    resolveLocalInstant(to, zone, false)
            };
        } catch (RuntimeException exception) {
            throw AttendanceSetupRules.invalid("已发布 mealWindow 参数无效");
        }
    }

    /**
     * Resolves a wall-clock boundary deterministically without discarding the
     * zone's DST rules. Gaps advance to the first valid instant; overlaps use
     * the earlier instant for a start boundary and the later instant for an
     * end boundary.
     */
    static Instant resolveLocalInstant(
            LocalDateTime localDateTime, ZoneId zone, boolean startBoundary) {
        var rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(localDateTime);
        if (offsets.size() == 1) {
            return localDateTime.toInstant(offsets.getFirst());
        }
        if (offsets.size() == 2) {
            Instant first = localDateTime.toInstant(offsets.get(0));
            Instant second = localDateTime.toInstant(offsets.get(1));
            if (startBoundary) {
                return first.isBefore(second) ? first : second;
            }
            return first.isAfter(second) ? first : second;
        }
        ZoneOffsetTransition transition = rules.getTransition(localDateTime);
        if (transition == null || !transition.isGap()) {
            throw AttendanceSetupRules.invalid(
                    "无法按班次 IANA 时区解析本地时间边界");
        }
        return transition.getInstant();
    }

    private Map<String, Object> parameters(String policyVersionId) {
        String json = repository.publishedVersionParameters(policyVersionId);
        try {
            Map<String, Object> result = new HashMap<>();
            if (json.stripLeading().startsWith("{")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stored = objectMapper.readValue(json, Map.class);
                result.putAll(stored);
            } else {
                ParameterValue[] values =
                        objectMapper.readValue(json, ParameterValue[].class);
                for (ParameterValue value : values) {
                    result.put(value.key(), value.value());
                }
            }
            return Map.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("published attendance policy parameters invalid", exception);
        }
    }

    private AttendanceGroup requireGroup(String groupId, String capability) {
        AttendanceGroup group = groupRepository.findGroup(groupId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!peopleRepository.canAccessLegalEntity(
                principalProvider.currentPrincipalId(), capability,
                group.legalEntityId(), clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return group;
    }

    private AttendanceGroup requireGroupRevision(
            BindingCommand command,
            String capability) {
        AttendanceGroup identity = requireGroup(command.groupId(), capability);
        List<AttendanceGroup> revisions = groupRepository.resolveGroupRevisions(
                command.groupId(), command.effectiveFrom(), clock.instant());
        if (revisions.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    revisions.isEmpty()
                            ? "GROUP_REVISION_MISSING"
                            : "GROUP_REVISION_AMBIGUOUS",
                    "策略绑定生效日必须解析唯一考勤组 revision");
        }
        AttendanceGroup revision = revisions.getFirst();
        if (!revision.groupRevisionId().equals(command.groupRevisionId())
                || !revision.legalEntityId().equals(identity.legalEntityId())) {
            throw AttendanceSetupRules.conflict(
                    "GROUP_REVISION_MISMATCH",
                    "策略绑定必须显式引用生效日解析出的考勤组 revision");
        }
        return revision;
    }

    private PolicyBinding requireBinding(String bindingId, String capability) {
        PolicyBinding binding = repository.findBinding(bindingId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireGroup(binding.groupId(), capability);
        return binding;
    }

    private boolean accessible(String groupId, String capability) {
        return groupRepository.findGroup(groupId)
                .map(group -> peopleRepository.canAccessLegalEntity(
                        principalProvider.currentPrincipalId(), capability,
                        group.legalEntityId(), clock.instant()))
                .orElse(false);
    }

    private BindingCommand normalize(BindingCommand command) {
        Objects.requireNonNull(command.policyKind());
        Objects.requireNonNull(command.policyVersionId());
        Objects.requireNonNull(command.groupId());
        Objects.requireNonNull(command.groupRevisionId());
        AttendanceSetupRules.halfOpenPeriod(
                command.effectiveFrom(), command.effectiveTo());
        return new BindingCommand(
                command.policyKind(), command.policyVersionId(), command.groupId(),
                command.groupRevisionId(),
                command.effectiveFrom(), command.effectiveTo(),
                AttendanceSetupRules.reason(command.reason()));
    }

    private void requireFuture(LocalDate effectiveFrom) {
        if (!effectiveFrom.isAfter(LocalDate.now(clock))) {
            throw AttendanceSetupRules.conflict(
                    "FROZEN_PERIOD_PROTECTION_UNAVAILABLE",
                    "真实月结保护提供方尚未交付，只允许未来生效的策略绑定");
        }
    }

    private String requirePublishedVersion(
            AttendanceGroup group, BindingCommand command) {
        if (!repository.publishedVersionMatchesKind(
                group.legalEntityId(), command.policyVersionId(),
                command.policyKind(), command.effectiveFrom(),
                command.effectiveTo())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        String digest = repository.publishedVersionDigest(command.policyVersionId());
        if (digest == null || digest.isBlank()) {
            throw AttendanceSetupRules.conflict(
                    "POLICY_SNAPSHOT_MISSING", "已发布策略缺少不可变快照");
        }
        return digest;
    }

    private String bindingDigest(
            BindingCommand command, String policyVersionDigest) {
        return tokenService.digest(String.join(
                "|",
                command.groupRevisionId(),
                command.policyVersionId(),
                policyVersionDigest,
                command.effectiveFrom().toString(),
                command.effectiveTo() == null
                        ? "NULL" : command.effectiveTo().toString()));
    }

    private void requireContainedByGroup(
            AttendanceGroup group, BindingCommand command) {
        if (command.effectiveFrom().isBefore(group.effectiveFrom())
                || group.effectiveTo() != null
                && (command.effectiveTo() == null
                    || command.effectiveTo().isAfter(group.effectiveTo()))) {
            throw AttendanceSetupRules.invalid(
                    "策略绑定期间必须被考勤组版本期间完整包含");
        }
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) {
            throw new OptimisticLockingFailureException(
                    "attendance policy binding changed");
        }
    }

    private boolean bool(Map<String, Object> parameters, String key) {
        if (parameters.get(key) instanceof Boolean value) {
            return value;
        }
        throw AttendanceSetupRules.invalid("已发布策略参数 " + key + " 不是 BOOLEAN");
    }

    private int integer(Map<String, Object> parameters, String key) {
        if (parameters.get(key) instanceof Number value) {
            return value.intValue();
        }
        throw AttendanceSetupRules.invalid("已发布策略参数 " + key + " 不是 INTEGER");
    }

    private record BindingMutationRequest(
            long expectedVersion,
            BindingCommand command) {
    }

}
