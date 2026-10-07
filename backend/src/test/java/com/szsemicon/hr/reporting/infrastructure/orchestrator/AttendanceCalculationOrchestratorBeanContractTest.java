package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

class AttendanceCalculationOrchestratorBeanContractTest {

    @Test
    void onlyFullCalculationEngineIsRegisteredForPublication() {
        assertThat(FullCalculationEngineOrchestrator.class)
                .hasAnnotation(Service.class)
                .hasAnnotation(Primary.class);
        assertThat(CalculationEngineOrchestrator.class
                .getDeclaredAnnotation(Service.class)).isNull();
    }
}
