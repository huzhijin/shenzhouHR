package com.szsemicon.hr.employee.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.employee.infrastructure.persistence.EmployeeReadMapper;
import com.szsemicon.hr.employee.infrastructure.persistence.PunchExemptionMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PunchExemptionApplicationServiceTest {

    @Mock
    private EmployeeReadMapper employeeReadMapper;
    @Mock
    private PunchExemptionMapper punchExemptionMapper;

    private PunchExemptionApplicationService service;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-08-20T08:00:00Z");
        service = new PunchExemptionApplicationService(
                () -> "principal-1",
                employeeReadMapper,
                punchExemptionMapper,
                Clock.fixed(now, ZoneOffset.UTC));
        when(employeeReadMapper.canAccessEmployee(
                eq("principal-1"), any(), any(), eq("emp-1")))
                .thenReturn(true);
    }

    @Test
    void enablingStandingListDoesNotGrantExecutive() {
        when(punchExemptionMapper.currentEmployeeNumber("emp-1")).thenReturn("SZST0004");
        when(punchExemptionMapper.hasStandingExemption("emp-1")).thenReturn(true);
        when(punchExemptionMapper.hasExecutiveRole("emp-1")).thenReturn(false);

        var status = service.setStanding("emp-1", true);

        assertThat(status.standingExempt()).isTrue();
        assertThat(status.executiveExempt()).isFalse();
        verify(punchExemptionMapper).insertStanding(
                any(), eq("emp-1"), eq("SZST0004"), any());
        verify(punchExemptionMapper, never()).closeStanding(any(), any());
    }

    @Test
    void executiveFlagIsIndependentOfStandingList() {
        when(punchExemptionMapper.hasStandingExemption("emp-1")).thenReturn(false);
        when(punchExemptionMapper.hasExecutiveRole("emp-1")).thenReturn(true);

        var status = service.get("emp-1");

        assertThat(status.standingExempt()).isFalse();
        assertThat(status.executiveExempt()).isTrue();
        verify(employeeReadMapper).canAccessEmployee(
                "principal-1", CapabilityCodes.EMPLOYEE_READ, Instant.parse("2026-08-20T08:00:00Z"), "emp-1");
    }
}
