package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AttendanceMetrics;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodClosePolicy;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ClosePrecheckInput;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.CloseSnapshotMember;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodIdentity;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodState;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodStateSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttendancePeriodClosePolicyTest {

    private final AttendancePeriodClosePolicy policy =
            new AttendancePeriodClosePolicy();
    private final Instant checkedAt =
            Instant.parse("2026-08-02T01:00:00Z");

    @Test
    void precheck_reports_every_domain_blocker() {
        var report = policy.precheck(
                new ClosePrecheckInput(
                        period(1, PeriodState.OPEN, "token-v1", null),
                        10,
                        9,
                        2,
                        1,
                        1,
                        false,
                        false,
                        dependencies(),
                        "synthetic-precheck-request"),
                checkedAt);

        assertThat(report.closable()).isFalse();
        assertThat(report.blockers())
                .extracting(value -> value.blockerCode())
                .containsExactly(
                        "CALCULATION_COVERAGE_MISMATCH",
                        "CONTROL_TOTAL_MISMATCH",
                        "RECALCULATION_RUNNING",
                        "SOURCE_NOT_FRESH",
                        "SOURCE_OR_IMPORT_RUNNING",
                        "UNRESOLVED_BLOCKING_EXCEPTIONS");
    }

    @Test
    void green_precheck_closes_immutable_members_and_reconciles() {
        PeriodStateSnapshot open =
                period(1, PeriodState.OPEN, "token-v1", null);
        var report = policy.precheck(green(open), checkedAt);
        var metrics = new AttendanceMetrics(480, 240, 0, 0, 240, 0, 240);
        var snapshot = policy.close(
                "synthetic-close-v1",
                report,
                open,
                List.of(new CloseSnapshotMember(
                        "synthetic-employee-001",
                        LocalDate.parse("2026-07-15"),
                        "synthetic-calculation-v1",
                        "synthetic-result-digest",
                        metrics)),
                "synthetic-close-actor",
                "synthetic month close",
                "synthetic-close-request",
                "synthetic-close-correlation",
                Instant.parse("2026-08-02T02:00:00Z"));

        assertThat(report.closable()).isTrue();
        assertThat(snapshot.snapshotDigest()).isNotBlank();
        assertThat(snapshot.members()).hasSize(1);
        assertThat(policy.reconciles(snapshot)).isTrue();
        assertThatThrownBy(() -> snapshot.members().add(
                snapshot.members().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stale_locked_token_rejects_close_without_snapshot() {
        PeriodStateSnapshot open =
                period(1, PeriodState.OPEN, "token-v1", null);
        var report = policy.precheck(green(open), checkedAt);

        assertThatThrownBy(() -> policy.close(
                "synthetic-close-v1",
                report,
                period(2, PeriodState.REOPENED, "token-v2", "close-v0"),
                List.of(),
                "synthetic-actor",
                "synthetic reason",
                "synthetic-request",
                "synthetic-correlation",
                checkedAt.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ATTENDANCE_PERIOD_VERSION_STALE");
    }

    @Test
    void reopen_preserves_old_snapshot_reference_and_issues_new_token() {
        PeriodStateSnapshot closed =
                period(1, PeriodState.CLOSED, "closed-token-v1", "close-v1");
        PeriodStateSnapshot reopened = policy.reopen(
                closed,
                "synthetic-supervisor-approval",
                "synthetic correction required",
                "synthetic-supervisor",
                "synthetic-reopen-request",
                Instant.parse("2026-08-03T00:00:00Z"));

        assertThat(reopened.version()).isEqualTo(2);
        assertThat(reopened.state()).isEqualTo(PeriodState.REOPENED);
        assertThat(reopened.token()).isNotEqualTo(closed.token());
        assertThat(reopened.closeSnapshotReference()).isEqualTo("close-v1");
    }

    private ClosePrecheckInput green(PeriodStateSnapshot period) {
        return new ClosePrecheckInput(
                period,
                1,
                1,
                0,
                0,
                0,
                true,
                true,
                dependencies(),
                "synthetic-precheck-request");
    }

    private Map<String, String> dependencies() {
        return Map.of(
                "calculations", "synthetic-calculation-digest",
                "exceptions", "synthetic-exception-digest",
                "source", "synthetic-source-digest",
                "recalculation", "synthetic-recalculation-digest",
                "results", "synthetic-result-control-digest");
    }

    private PeriodStateSnapshot period(
            long version,
            PeriodState state,
            String token,
            String closeReference) {
        return new PeriodStateSnapshot(
                new PeriodIdentity(
                        "synthetic-period",
                        "synthetic-company",
                        LocalDate.parse("2026-07-01"),
                        LocalDate.parse("2026-08-01")),
                version,
                state,
                token,
                closeReference);
    }
}
