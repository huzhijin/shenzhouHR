package com.szsemicon.hr.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public final class AttendancePolicyModels {

    private AttendancePolicyModels() {
    }

    public enum PolicyKind {
        MEAL_DEDUCTION,
        LATE_GRACE,
        MONTHLY_LATE_EXEMPTION,
        /** 打卡取卡窗口：管理类规则，有绑定则透传参数，无绑定不阻断试算。 */
        PUNCH_WINDOW,
        /** 月结封账：管理类规则，有绑定则透传参数，无绑定不阻断试算。 */
        PERIOD_CLOSE
    }

    public enum SimulationStatus {
        MATCHED,
        NOT_MATCHED,
        ON_TIME,
        EXEMPTED,
        LATE
    }

    public enum PunchDirection {
        ENTRY,
        EXIT
    }

    public record PolicyBinding(
            String bindingId,
            String bindingRevisionId,
            int revisionNumber,
            String companyId,
            PolicyKind policyKind,
            String policyVersionId,
            String groupId,
            String groupRevisionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            AttendanceGroupModels.LifecycleStatus status,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    public record SimulationInput(
            String employeeId,
            LocalDate businessDate,
            List<PunchInput> punches,
            Instant correctionAsOf) {

        public SimulationInput {
            punches = punches == null ? List.of() : List.copyOf(punches);
        }
    }

    public record PunchInput(
            PunchDirection direction,
            OffsetDateTime instant,
            String workSegmentId,
            String association) {
    }

    public record SimulationResult(
            PolicyKind policyKind,
            SimulationStatus status,
            String policyVersionId,
            String configurationDigest,
            boolean matched,
            boolean consumesAllowance,
            Integer rawLateMinutes,
            int predictedMonthlyConsumption,
            String usageProvenance,
            Instant usageKnowledgeTime,
            Integer deductionMinutes,
            List<MatchedMealWindow> matchedMealWindows,
            LocalDate correctionDeadline,
            String affectedSegment,
            String explanation,
            boolean writesFormalResult) {

        public SimulationResult(
                PolicyKind policyKind,
                SimulationStatus status,
                String policyVersionId,
                String configurationDigest,
                boolean matched,
                boolean consumesAllowance,
                Integer rawLateMinutes,
                int predictedMonthlyConsumption,
                String usageProvenance,
                Instant usageKnowledgeTime,
                Integer deductionMinutes,
                LocalDate correctionDeadline,
                String affectedSegment,
                String explanation,
                boolean writesFormalResult) {
            this(
                    policyKind,
                    status,
                    policyVersionId,
                    configurationDigest,
                    matched,
                    consumesAllowance,
                    rawLateMinutes,
                    predictedMonthlyConsumption,
                    usageProvenance,
                    usageKnowledgeTime,
                    deductionMinutes,
                    List.of(),
                    correctionDeadline,
                    affectedSegment,
                    explanation,
                    writesFormalResult);
        }

        public SimulationResult {
            matchedMealWindows = matchedMealWindows == null
                    ? List.of()
                    : List.copyOf(matchedMealWindows);
            if (deductionMinutes != null && deductionMinutes < 0) {
                throw new IllegalArgumentException(
                        "deductionMinutes must be non-negative");
            }
            if (!matchedMealWindows.isEmpty()) {
                long uniqueWindows = matchedMealWindows.stream()
                        .map(MatchedMealWindow::windowId)
                        .distinct()
                        .count();
                int windowTotal = matchedMealWindows.stream()
                        .mapToInt(MatchedMealWindow::deductionMinutes)
                        .sum();
                if (uniqueWindows != matchedMealWindows.size()) {
                    throw new IllegalArgumentException(
                            "matched meal windows must be unique");
                }
                if (deductionMinutes == null || deductionMinutes != windowTotal) {
                    throw new IllegalArgumentException(
                            "deductionMinutes must equal matched meal window total");
                }
            }
        }
    }

    public record MatchedMealWindow(
            String windowId,
            MealDeductionPolicyResolver.MealType mealType,
            MealDeductionPolicyResolver.Source source,
            LocalTime windowStart,
            LocalTime windowEnd,
            int deductionMinutes,
            int triggerMinutes) {

        public MatchedMealWindow {
            if (windowId == null || windowId.isBlank()) {
                throw new IllegalArgumentException("windowId must not be blank");
            }
            Objects.requireNonNull(mealType, "mealType");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(windowStart, "windowStart");
            Objects.requireNonNull(windowEnd, "windowEnd");
            if (windowStart.equals(windowEnd)) {
                throw new IllegalArgumentException(
                        "meal window start and end must differ");
            }
            if (deductionMinutes < 0 || triggerMinutes < 0) {
                throw new IllegalArgumentException(
                        "meal deduction minutes must be non-negative");
            }
        }
    }

    public record Impact(
            int groupCount,
            int assignmentCount,
            String countSource,
            String impactToken,
            Instant expiresAt) {
    }

    public record ConfigurationSnapshot(
            String status,
            String employeeId,
            LocalDate businessDate,
            String groupId,
            String groupRevisionId,
            String locationRevisionId,
            String calendarVersionId,
            CalendarModels.WorkCalendarDay calendarDay,
            ShiftModels.ShiftVersion shiftVersion,
            List<PolicyBinding> policyBindings,
            String configurationDigest,
            String monthlyContextKey,
            String explanation) {

        public ConfigurationSnapshot {
            policyBindings = List.copyOf(policyBindings);
        }
    }
}
