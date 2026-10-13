package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionFinding;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionSeverity;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionTransitionType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionReconciler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceExceptionReconcilerTest {

    @Test
    void observes_resolves_and_reopens_one_append_only_case() {
        var reconciler = new AttendanceExceptionReconciler();
        var first = finding("calculation-v1");
        var opened = reconciler.reconcile(
                List.of(),
                List.of(first),
                "synthetic-request-1",
                Instant.parse("2026-07-23T01:00:00Z"));

        var observed = reconciler.reconcile(
                opened,
                List.of(finding("calculation-v2")),
                "synthetic-request-2",
                Instant.parse("2026-07-23T02:00:00Z"));
        var resolved = reconciler.reconcile(
                observed,
                List.of(),
                "synthetic-request-3",
                Instant.parse("2026-07-23T03:00:00Z"));
        var reopened = reconciler.reconcile(
                resolved,
                List.of(finding("calculation-v4")),
                "synthetic-request-4",
                Instant.parse("2026-07-23T04:00:00Z"));

        assertThat(reopened).hasSize(1);
        assertThat(opened.getFirst().observations()).hasSize(1);
        assertThat(opened.getFirst().transitions()).hasSize(1);
        assertThat(resolved.getFirst().resolved()).isTrue();
        assertThat(reopened.getFirst().observations()).hasSize(3);
        assertThat(reopened.getFirst().transitions())
                .extracting(value -> value.type())
                .containsExactly(
                        ExceptionTransitionType.OPENED,
                        ExceptionTransitionType.OBSERVED_BY_RECALCULATION,
                        ExceptionTransitionType.RESOLVED_BY_RECALCULATION,
                        ExceptionTransitionType.REOPENED_BY_RECALCULATION);
        assertThat(reopened.getFirst().resolved()).isFalse();
    }

    private ExceptionFinding finding(String calculationVersionId) {
        return new ExceptionFinding(
                "synthetic-missing-punch-fingerprint",
                "synthetic-company",
                "synthetic-employee-001",
                SyntheticAttendanceFixtures.BUSINESS_DATE,
                "synthetic-segment-am",
                ExceptionType.MISSING_PUNCH_PENDING,
                ExceptionSeverity.ERROR,
                calculationVersionId,
                List.of("synthetic-punch-in"),
                "MISSING_DEPARTURE");
    }
}
