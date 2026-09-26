package com.szsemicon.hr.payroll.infrastructure;

import com.szsemicon.hr.payroll.application.FrozenAttendanceSnapshotPort;
import com.szsemicon.hr.payroll.application.PayrollCapabilityAuthorizer;
import com.szsemicon.hr.payroll.application.PayrollReservationAudit;
import com.szsemicon.hr.payroll.application.PayrollReservationService;
import com.szsemicon.hr.payroll.application.PayrollReservationSettings;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PayrollReservationConfiguration {

    @Bean
    PayrollReservationService payrollReservationService(
            @Value("${shenzhouhr.payroll-reservation.enabled:false}") boolean enabled,
            PayrollCapabilityAuthorizer authorizer,
            PayrollReservationAudit audit,
            ObjectProvider<FrozenAttendanceSnapshotPort> snapshotPorts) {
        List<FrozenAttendanceSnapshotPort> adapters = snapshotPorts.orderedStream().toList();
        if (adapters.size() > 1) {
            throw new IllegalStateException(
                    "only one frozen attendance snapshot adapter may be registered");
        }
        return new PayrollReservationService(
                new PayrollReservationSettings(enabled),
                authorizer,
                audit,
                adapters.isEmpty() ? Optional.empty() : Optional.of(adapters.getFirst()));
    }
}
