package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.ConfigurationSnapshot;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.MatchedMealWindow;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PunchDirection;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.Impact;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyBinding;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationResult;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Page;
import com.szsemicon.hr.attendance.domain.MealDeductionPolicyResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

final class AttendancePolicyDtos {

    private AttendancePolicyDtos() {
    }

    record BindingRequest(
            @NotNull PolicyKind policyKind,
            @NotBlank String policyVersionId,
            @NotBlank String groupId,
            @NotBlank String groupRevisionId,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason,
            @NotBlank String impactToken) {
    }

    record BindingPreviewRequest(
            @NotNull PolicyKind policyKind,
            @NotBlank String policyVersionId,
            @NotBlank String groupId,
            @NotBlank String groupRevisionId,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record BindingView(
            String bindingId,
            String bindingRevisionId,
            int revisionNumber,
            String legalEntityId,
            String policyKind,
            String policyVersionId,
            String groupId,
            String groupRevisionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            java.time.Instant updatedAt) {
    }

    record BindingPage(
            List<BindingView> items,
            long total,
            int page,
            int size) {
    }

    record SimulationRequest(
            @NotBlank String employeeId,
            @NotNull LocalDate businessDate,
            @NotNull OffsetDateTime correctionAsOf,
            @NotNull @Size(max = 48) List<@Valid PunchSimulationInput> punches) {
    }

    record PunchSimulationInput(
            @NotNull PunchDirection direction,
            @NotNull OffsetDateTime instant,
            @Size(max = 100) String workSegmentId,
            @Size(max = 100) String association) {
    }

    record SimulationView(
            String policyKind,
            String status,
            String policyVersionId,
            String configurationDigest,
            boolean matched,
            boolean consumesAllowance,
            Integer rawLateMinutes,
            int predictedMonthlyConsumption,
            String usageProvenance,
            java.time.Instant usageKnowledgeTime,
            Integer deductionMinutes,
            List<MatchedMealWindowView> matchedMealWindows,
            LocalDate correctionDeadline,
            String affectedSegment,
            String explanation,
            boolean writesFormalResult) {
    }

    record MatchedMealWindowView(
            String windowId,
            MealDeductionPolicyResolver.MealType mealType,
            MealDeductionPolicyResolver.Source source,
            LocalTime windowStart,
            LocalTime windowEnd,
            int deductionMinutes,
            int triggerMinutes) {
    }

    record SimulationBatchView(
            String configurationDigest,
            List<SimulationView> results) {
    }

    record ImpactView(
            int groupCount,
            int assignmentCount,
            String countSource,
            String impactToken,
            java.time.Instant expiresAt) {
    }

    record ConfigurationView(
            String status,
            String employeeId,
            LocalDate businessDate,
            String groupId,
            String groupRevisionId,
            String locationRevisionId,
            String calendarVersionId,
            ShiftCalendarDtos.CalendarDayView calendarDay,
            ShiftCalendarDtos.ShiftVersionView shiftVersion,
            List<BindingView> policyBindings,
            String configurationDigest,
            String monthlyContextKey,
            String explanation) {
    }

    static BindingView binding(PolicyBinding value) {
        return new BindingView(
                value.bindingId(), value.bindingRevisionId(),
                value.revisionNumber(), value.legalEntityId(),
                value.policyKind().name(),
                value.policyVersionId(), value.groupId(),
                value.groupRevisionId(),
                value.effectiveFrom(), value.effectiveTo(), value.status().name(),
                value.snapshotDigest(), value.rowVersion(), value.changeReason(),
                value.updatedAt());
    }

    static BindingPage bindings(Page<PolicyBinding> value) {
        return new BindingPage(
                value.items().stream()
                        .map(AttendancePolicyDtos::binding)
                        .toList(),
                value.total(),
                value.page(),
                value.size());
    }

    static SimulationView simulation(SimulationResult value) {
        return new SimulationView(
                value.policyKind().name(), value.status().name(), value.policyVersionId(),
                value.configurationDigest(), value.matched(),
                value.consumesAllowance(), value.rawLateMinutes(),
                value.predictedMonthlyConsumption(), value.usageProvenance(),
                value.usageKnowledgeTime(), value.deductionMinutes(),
                value.matchedMealWindows().stream()
                        .map(AttendancePolicyDtos::matchedMealWindow)
                        .toList(),
                value.correctionDeadline(), value.affectedSegment(),
                value.explanation(), value.writesFormalResult());
    }

    private static MatchedMealWindowView matchedMealWindow(
            MatchedMealWindow value) {
        return new MatchedMealWindowView(
                value.windowId(),
                value.mealType(),
                value.source(),
                value.windowStart(),
                value.windowEnd(),
                value.deductionMinutes(),
                value.triggerMinutes());
    }

    static ImpactView impact(Impact value) {
        return new ImpactView(
                value.groupCount(),
                value.assignmentCount(),
                value.countSource(),
                value.impactToken(),
                value.expiresAt());
    }

    static ConfigurationView configuration(ConfigurationSnapshot value) {
        return new ConfigurationView(
                value.status(), value.employeeId(), value.businessDate(), value.groupId(),
                value.groupRevisionId(), value.locationRevisionId(),
                value.calendarVersionId(),
                value.calendarDay() == null
                        ? null : ShiftCalendarDtos.day(value.calendarDay()),
                value.shiftVersion() == null
                        ? null : ShiftCalendarDtos.version(value.shiftVersion()),
                value.policyBindings().stream()
                        .map(AttendancePolicyDtos::binding).toList(),
                value.configurationDigest(), value.monthlyContextKey(),
                value.explanation());
    }
}
