package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AttendanceMetrics;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ExplanationGraph;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultItem;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.RuleHit;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.AttendanceExceptionCase;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionFinding;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionSeverity;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionTransition;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionTransitionType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionType;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.CurrentExceptionProjectionContext;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionContext;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttendanceReportFactProjectorTest {

    @Test
    void projectsPrdMetricsAndCommonExceptionsWithoutRawEvidenceValues() {
        var result = new DailyAttendanceResult(
                "calculation-v1",
                "input-digest",
                "result-digest",
                "algorithm-v1",
                new AttendanceMetrics(480, 460, 0, 0, 0, 20, 460),
                List.of(
                        item(
                                "late",
                                "08:30:00Z",
                                "08:40:00Z",
                                ResultCategory.LATE,
                                0,
                                "MONTHLY_LATE_GRACE_CONSUMED",
                                null),
                        item(
                                "early",
                                "17:20:00Z",
                                "17:30:00Z",
                                ResultCategory.EARLY_DEPARTURE,
                                10,
                                "EARLY_DEPARTURE_CHARGEABLE",
                                null),
                        item(
                                "missing",
                                "13:00:00Z",
                                "13:20:00Z",
                                ResultCategory.MISSING_PUNCH_PENDING,
                                0,
                                "MISSING_PUNCH_PENDING",
                                "missing-fingerprint")),
                List.of(
                        new RuleHit(
                                "late-rule",
                                "policy-v1",
                                "segment-am",
                                "MONTHLY_LATE_GRACE_CONSUMED",
                                10,
                                0,
                                List.of("secret-punch-id")),
                        new RuleHit(
                                "early-rule",
                                "policy-v1",
                                "segment-pm",
                                "EARLY_DEPARTURE_CHARGEABLE",
                                10,
                                10,
                                List.of("secret-punch-id"))),
                List.of(),
                new ExplanationGraph(List.of(), List.of()),
                Set.of("secret-punch-id"),
                List.of("missing-fingerprint"));
        var context = new ProjectionContext(
                "daily-fact-v1",
                "legal-a",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "organization-version-a",
                "制造中心",
                LocalDate.of(2026, 7, 15),
                DayType.WEEKDAY,
                "总部夏令班",
                Instant.parse("2026-07-15T00:40:00Z"),
                Instant.parse("2026-07-15T09:20:00Z"));

        var projected =
                new AttendanceReportFactProjector().project(result, context);

        assertThat(projected.dailyFact().lateMinutes()).isEqualTo(10);
        assertThat(projected.dailyFact().penalizedLateMinutes()).isZero();
        assertThat(projected.dailyFact().earlyDepartureMinutes())
                .isEqualTo(10);
        assertThat(projected.dailyFact().missingPunchCount()).isEqualTo(1);
        assertThat(projected.exceptionFacts())
                .extracting(value -> value.exceptionType())
                .containsExactlyInAnyOrder(
                        "EARLY_DEPARTURE",
                        "MISSING_PUNCH_PENDING");
        assertThat(projected.exceptionFacts())
                .extracting(value -> value.exceptionType())
                .doesNotContain("LATE");
        assertThat(projected.exceptionFacts())
                .allSatisfy(value -> assertThat(value.safeEvidenceSummary())
                        .doesNotContain("secret-punch-id"));
    }

    @Test
    void reasonCodePreventsAmbiguousPunchFromBecomingMissingPunchOverdue() {
        var result = new DailyAttendanceResult(
                "calculation-v1",
                "input-digest",
                "result-digest",
                "algorithm-v1",
                new AttendanceMetrics(480, 0, 0, 0, 0, 480, 0),
                List.of(item(
                        "ambiguous",
                        "08:30:00Z",
                        "17:30:00Z",
                        ResultCategory.ABSENCE,
                        480,
                        "AMBIGUOUS_PUNCH_MATCH_OVERDUE",
                        "ambiguous-fingerprint")),
                List.of(),
                List.of(),
                new ExplanationGraph(List.of(), List.of()),
                Set.of(),
                List.of("ambiguous-fingerprint"));

        var projected = new AttendanceReportFactProjector().project(
                result, context());

        assertThat(projected.exceptionFacts())
                .extracting(value -> value.exceptionType())
                .containsExactly("AMBIGUOUS_PUNCH_MATCH");
        assertThat(projected.dailyFact().missingPunchCount()).isZero();
        assertThat(projected.exceptionFacts())
                .extracting(value -> value.state())
                .containsExactly(
                        com.szsemicon.hr.reporting.domain
                                .AttendanceReportModels.ExceptionState
                                .PENDING_REVIEW);
    }

    @Test
    void lateConvertedToAbsencePreservesAuditMinutesButRemovesAttendanceCredit() {
        var result = new DailyAttendanceResult(
                "calculation-v1",
                "input-digest",
                "result-digest",
                "algorithm-v1",
                new AttendanceMetrics(480, 0, 0, 0, 0, 480, 0),
                List.of(item(
                        "late-absence",
                        "08:30:00Z",
                        "16:30:00Z",
                        ResultCategory.ABSENCE,
                        480,
                        "LATE_CONVERTED_TO_ABSENCE",
                        "late-absence-fingerprint")),
                List.of(new RuleHit(
                        "late-absence-rule",
                        "policy-v1",
                        "segment-late-absence",
                        "LATE_CONVERTED_TO_ABSENCE",
                        45,
                        0,
                        List.of("secret-punch-id"))),
                List.of(),
                new ExplanationGraph(List.of(), List.of()),
                Set.of("secret-punch-id"),
                List.of("late-absence-fingerprint"));

        var projected = new AttendanceReportFactProjector().project(
                result, context());

        assertThat(result.ruleHits().getFirst().rawMinutes()).isEqualTo(45);
        assertThat(projected.dailyFact().scheduledAttendanceDays()).isEqualTo(1);
        assertThat(projected.dailyFact().actualAttendanceDays()).isZero();
        assertThat(projected.dailyFact().lateMinutes()).isZero();
        assertThat(projected.dailyFact().penalizedLateMinutes()).isZero();
        assertThat(projected.dailyFact().absenceMinutes()).isEqualTo(480);
        assertThat(projected.exceptionFacts())
                .extracting(value -> value.exceptionType())
                .containsExactly("LATE_CONVERTED_TO_ABSENCE");
    }

    @Test
    void restDayOvertimeDoesNotCreateAnAttendanceDay() {
        var result = result(new AttendanceMetrics(
                0,
                0,
                120,
                120,
                120,
                0,
                0,
                120,
                0,
                0,
                120));

        var daily = new AttendanceReportFactProjector()
                .project(result, context())
                .dailyFact();

        assertThat(daily.scheduledAttendanceDays()).isZero();
        assertThat(daily.actualAttendanceDays()).isZero();
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(120);
    }

    @Test
    void explicitPaidAndUnpaidLeaveTypesControlAttendanceCredit() {
        var result = result(new AttendanceMetrics(
                480, 0, 0, 0, 480, 0, 0));
        var projector = new AttendanceReportFactProjector();

        var annual = projector.project(
                result, context(LeaveType.ANNUAL)).dailyFact();
        var personal = projector.project(
                result, context(LeaveType.PERSONAL)).dailyFact();

        assertThat(annual.actualAttendanceDays()).isEqualTo(1);
        assertThat(annual.leaveType()).isEqualTo(LeaveType.ANNUAL);
        assertThat(personal.actualAttendanceDays()).isZero();
        assertThat(personal.leaveType()).isEqualTo(LeaveType.PERSONAL);
    }

    @Test
    void projectsEveryReconciledExceptionTypeFromItsCurrentState() {
        var projector = new AttendanceReportFactProjector();
        var context = new CurrentExceptionProjectionContext(
                "legal-a",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                LocalDate.of(2026, 7, 15),
                0);

        for (ExceptionType type : ExceptionType.values()) {
            ExceptionFinding finding = new ExceptionFinding(
                    "fingerprint-" + type.name(),
                    "legal-a",
                    "employee-a",
                    LocalDate.of(2026, 7, 15),
                    "slice-a",
                    type,
                    ExceptionSeverity.ERROR,
                    "calculation-v1",
                    List.of("secret-evidence-a", "secret-evidence-b"),
                    type.name());
            var exceptionCase = new AttendanceExceptionCase(
                    "case-" + type.name(),
                    finding,
                    List.of(finding),
                    List.of(new ExceptionTransition(
                            "transition-" + type.name(),
                            ExceptionTransitionType.OPENED,
                            "calculation-v1",
                            type.name(),
                            "request-a",
                            Instant.parse("2026-07-15T10:00:00Z"))));

            var fact = projector.projectCurrentException(
                    exceptionCase, context);

            assertThat(fact.exceptionType()).isEqualTo(type.name());
            assertThat(fact.safeEvidenceSummary())
                    .isEqualTo("原因码=" + type.name() + "；证据数量=2")
                    .doesNotContain("secret-evidence");
        }
    }

    private ResultItem item(
            String key,
            String startTime,
            String endTime,
            ResultCategory category,
            long minutes,
            String reason,
            String fingerprint) {
        return new ResultItem(
                key,
                "segment-" + key,
                new TimeInterval(
                        Instant.parse("2026-07-15T" + startTime),
                        Instant.parse("2026-07-15T" + endTime)),
                category,
                minutes,
                reason,
                List.of("secret-punch-id"),
                fingerprint);
    }

    private ProjectionContext context() {
        return new ProjectionContext(
                "daily-fact-v1",
                "legal-a",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "organization-version-a",
                "制造中心",
                LocalDate.of(2026, 7, 15),
                DayType.WEEKDAY,
                "总部夏令班",
                Instant.parse("2026-07-15T00:40:00Z"),
                Instant.parse("2026-07-15T09:20:00Z"));
    }

    private ProjectionContext context(LeaveType leaveType) {
        ProjectionContext base = context();
        return new ProjectionContext(
                base.factId(),
                base.companyId(),
                base.employeeId(),
                base.employeeNumber(),
                base.employeeName(),
                base.organizationId(),
                base.organizationVersionId(),
                base.organizationName(),
                base.businessDate(),
                base.dayType(),
                base.shiftLabel(),
                base.firstPunchAt(),
                base.lastPunchAt(),
                leaveType);
    }

    private DailyAttendanceResult result(AttendanceMetrics metrics) {
        return new DailyAttendanceResult(
                "calculation-v1",
                "input-digest",
                "result-digest",
                "algorithm-v1",
                metrics,
                List.of(),
                List.of(),
                List.of(),
                new ExplanationGraph(List.of(), List.of()),
                Set.of(),
                List.of());
    }
}
