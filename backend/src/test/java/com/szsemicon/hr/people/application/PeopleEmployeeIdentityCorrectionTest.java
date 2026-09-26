package com.szsemicon.hr.people.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.people.application.PeopleCommands.UpdateEmployee;
import com.szsemicon.hr.people.domain.PeopleModels.EmployeeVersion;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PeopleEmployeeIdentityCorrectionTest {

    private static final Instant NOW = Instant.parse("2026-09-01T02:00:00Z");

    @Mock
    private CurrentCapabilityService capabilities;
    @Mock
    private CurrentPrincipalProvider principals;
    @Mock
    private PeopleRepository repository;
    @Mock
    private AuditService audit;

    private PeopleManagementService service;

    @BeforeEach
    void setUp() {
        service = new PeopleManagementService(
                capabilities,
                principals,
                repository,
                audit,
                new SecurityTokenService(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(principals.currentPrincipalId()).thenReturn("principal-1");
        when(repository.findIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.canAccessEmployee(
                anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);
        org.mockito.Mockito.lenient()
                .when(repository.listAccessibleEmploymentPeriods(
                        anyString(), any(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient()
                .when(repository.listAllPriorServiceRecords(anyString()))
                .thenReturn(List.of());
    }

    @Test
    void sameDayNameCorrectionUpdatesCurrentVersionWithoutRecalcSplit() {
        EmployeeVersion current = employee("何伟寰", 3);
        EmployeeVersion corrected = employee("何伟豪", 4);
        when(repository.findCurrentEmployee("emp-1"))
                .thenReturn(Optional.of(current), Optional.of(current), Optional.of(corrected));
        when(repository.employeeNumberExists("company-1", "SZST0716", "emp-1"))
                .thenReturn(false);

        PeopleManagementService.EmployeeDetail detail = service.updateEmployee(
                "emp-1",
                new UpdateEmployee(
                        "SZST0716",
                        "何伟豪",
                        "ACTIVE",
                        LocalDate.of(2026, 8, 10),
                        null,
                        "录入错误"),
                3,
                "identity-correction-1");

        assertThat(detail.employee().displayName()).isEqualTo("何伟豪");
        assertThat(detail.employee().employeeVersionId()).isEqualTo("ver-1");
        verify(repository).correctCurrentEmployeeVersion(
                eq("emp-1"),
                eq("SZST0716"),
                eq("何伟豪"),
                eq("ACTIVE"),
                eq(null),
                eq("录入错误"),
                eq(3L));
        verify(repository, never()).closeCurrentEmployeeVersion(anyString(), any(), anyLong());
        verify(repository, never()).saveEmployeeVersion(any());
    }

    @Test
    void earlierEffectiveFromIsRejectedWithADateMessageNotStaleVersion() {
        when(repository.findCurrentEmployee("emp-1"))
                .thenReturn(Optional.of(employee("何伟寰", 3)));
        when(repository.employeeNumberExists("company-1", "SZST0716", "emp-1"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.updateEmployee(
                "emp-1",
                new UpdateEmployee(
                        "SZST0716",
                        "何伟豪",
                        "ACTIVE",
                        LocalDate.of(2026, 8, 9),
                        null,
                        "录入错误"),
                3,
                "identity-correction-earlier"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("新版本生效日期必须晚于当前版本");
        verify(repository, never()).correctCurrentEmployeeVersion(
                anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyLong());
        verify(repository, never()).updateEmployeeIdentity(
                anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    private static EmployeeVersion employee(String name, long version) {
        return new EmployeeVersion(
                "ver-1",
                "emp-1",
                "company-1",
                "SZST0716",
                name,
                "ACTIVE",
                null,
                LocalDate.of(2026, 8, 10),
                null,
                "LOCAL",
                null,
                version,
                "入职",
                "principal-1",
                Instant.parse("2026-08-10T00:00:00Z"));
    }
}
