package com.szsemicon.hr.payroll.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.payroll.application.PayrollCapabilityAuthorizer;
import com.szsemicon.hr.payroll.application.PayrollReservationAudit;
import com.szsemicon.hr.payroll.application.PayrollReservationService;
import com.szsemicon.hr.payroll.application.PayrollReservationService.InspectFrozenAttendanceSnapshot;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollPeriod;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollPeriodStatus;
import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PayrollReservationConfigurationTest {

    @Test
    void missingConfigurationUsesDisabledDefault() {
        assertDisabled(new ApplicationContextRunner());
    }

    @Test
    void explicitFalseConfigurationStaysDisabled() {
        assertDisabled(new ApplicationContextRunner()
                .withPropertyValues("shenzhouhr.payroll-reservation.enabled=false"));
    }

    private static void assertDisabled(ApplicationContextRunner base) {
        AtomicInteger capabilityCalls = new AtomicInteger();
        AtomicInteger auditCalls = new AtomicInteger();
        base.withUserConfiguration(PayrollReservationConfiguration.class)
                .withBean(
                        PayrollCapabilityAuthorizer.class,
                        () -> capabilityCalls::incrementAndGet)
                .withBean(
                        PayrollReservationAudit.class,
                        () -> (periodId, reason) -> auditCalls.incrementAndGet())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PayrollReservationService service =
                            context.getBean(PayrollReservationService.class);
                    assertThatThrownBy(() ->
                            service.inspectFrozenAttendanceSnapshot(command()))
                            .isInstanceOf(
                                    ResourceNotAvailableAccessDeniedException.class);
                    assertThat(capabilityCalls).hasValue(0);
                    assertThat(auditCalls).hasValue(1);
                });
    }

    private static InspectFrozenAttendanceSnapshot command() {
        PayrollPeriod period = new PayrollPeriod(
                new ExternalPreciseId("period-1"),
                "P202607",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1),
                PayrollPeriodStatus.RESERVED,
                1);
        return new InspectFrozenAttendanceSnapshot(
                period, new ExternalPreciseId("snapshot-1"));
    }
}
