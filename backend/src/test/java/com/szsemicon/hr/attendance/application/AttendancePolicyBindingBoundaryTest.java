package com.szsemicon.hr.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyBinding;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AttendancePolicyBindingBoundaryTest {

    private static final LocalDate PREDECESSOR_START =
            LocalDate.parse("2026-07-01");
    private static final LocalDate PREDECESSOR_END =
            LocalDate.parse("2026-08-01");

    @Test
    void already_effective_open_predecessor_accepts_a_later_successor() {
        assertThatCode(() -> AttendancePolicyService.requireSuccessorBoundary(
                predecessor(null), PREDECESSOR_END))
                .doesNotThrowAnyException();
    }

    @Test
    void finite_predecessor_accepts_cutover_through_its_planned_end_without_gap() {
        PolicyBinding predecessor = predecessor(PREDECESSOR_END);

        assertThatCode(() -> AttendancePolicyService.requireSuccessorBoundary(
                predecessor, PREDECESSOR_END.minusDays(1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AttendancePolicyService.requireSuccessorBoundary(
                predecessor, PREDECESSOR_END))
                .doesNotThrowAnyException();

        assertConflict(
                PREDECESSOR_START,
                "ATTENDANCE_POLICY_BINDING_BOUNDARY_INVALID");
        assertConflict(
                PREDECESSOR_END.plusDays(1),
                "ATTENDANCE_POLICY_BINDING_GAP");
    }

    private void assertConflict(LocalDate successorBoundary, String code) {
        assertThatThrownBy(() ->
                AttendancePolicyService.requireSuccessorBoundary(
                        predecessor(PREDECESSOR_END), successorBoundary))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            assertThat(problem.status().value()).isEqualTo(409);
                            assertThat(problem.code()).isEqualTo(code);
                        });
    }

    private PolicyBinding predecessor(LocalDate effectiveTo) {
        Instant createdAt = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyBinding(
                "binding",
                "binding-revision",
                1,
                "company",
                PolicyKind.LATE_GRACE,
                "policy-version",
                "group",
                "group-revision",
                PREDECESSOR_START,
                effectiveTo,
                LifecycleStatus.ACTIVE,
                "snapshot-digest",
                0,
                "finite predecessor",
                "actor",
                createdAt,
                "actor",
                createdAt);
    }
}
