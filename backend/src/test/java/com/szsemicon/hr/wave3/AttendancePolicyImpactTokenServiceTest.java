package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.application.AttendancePolicyCommands.BindingCommand;
import com.szsemicon.hr.attendance.application.AttendancePolicyImpactTokenService;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttendancePolicyImpactTokenServiceTest {

    private static final String ACTOR =
            "10000000-0000-0000-0000-000000000001";
    private static final Instant NOW =
            Instant.parse("2026-07-27T00:00:00Z");

    private MutableClock clock;
    private AttendancePolicyImpactTokenService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new AttendancePolicyImpactTokenService(
                new SecurityTokenService(),
                JsonMapper.builder().findAndAddModules().build(),
                clock);
    }

    @Test
    void token_is_bound_to_actor_exact_request_and_current_real_counts() {
        BindingCommand request = request("same reason");
        var issued = service.issue(ACTOR, request, 1, 7);

        assertThat(issued.token()).hasSize(43);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        service.requireCurrent(issued.token(), ACTOR, request, 1, 7);

        assertInvalid(() -> service.requireCurrent(
                issued.token(), "other-actor", request, 1, 7));
        assertInvalid(() -> service.requireCurrent(
                issued.token(), ACTOR, request("changed reason"), 1, 7));
        assertInvalid(() -> service.requireCurrent(
                issued.token(), ACTOR, request, 1, 8));
        assertInvalid(() -> service.requireCurrent(
                "unknown-token", ACTOR, request, 1, 7));
    }

    @Test
    void token_expires_at_the_exact_deadline() {
        BindingCommand request = request("same reason");
        var issued = service.issue(ACTOR, request, 1, 0);

        clock.set(NOW.plusSeconds(599));
        service.requireCurrent(issued.token(), ACTOR, request, 1, 0);

        clock.set(NOW.plusSeconds(600));
        assertInvalid(() -> service.requireCurrent(
                issued.token(), ACTOR, request, 1, 0));
    }

    private static BindingCommand request(String reason) {
        return new BindingCommand(
                PolicyKind.LATE_GRACE,
                "97000000-0000-0000-0000-000000000002",
                "94000000-0000-0000-0000-000000000001",
                "94100000-0000-0000-0000-000000000001",
                LocalDate.parse("2026-08-16"),
                LocalDate.parse("2026-08-17"),
                reason);
    }

    private static void assertInvalid(ThrowingRunnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            assertThat(problem.status().value()).isEqualTo(409);
                            assertThat(problem.code()).isEqualTo(
                                    "ATTENDANCE_POLICY_IMPACT_TOKEN_INVALID");
                            assertThat(problem.retryable()).isTrue();
                        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
