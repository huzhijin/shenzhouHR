package com.szsemicon.hr.people.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.application.AttendanceGroupService;
import com.szsemicon.hr.people.application.NewHireAttendanceRecovery;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.people.application.PeopleCommands.CreateEmployee;
import com.szsemicon.hr.people.application.PeopleCommands.CreateOrganization;
import com.szsemicon.hr.people.domain.PeopleModels.OrganizationVersion;
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
class PeopleLocalCreateTest {

    private static final Instant NOW = Instant.parse("2026-09-11T02:00:00Z");

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
        org.mockito.Mockito.lenient().when(principals.currentPrincipalId()).thenReturn("principal-1");
        org.mockito.Mockito.lenient().when(repository.findIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(repository.companyExists("company-1")).thenReturn(true);
        org.mockito.Mockito.lenient().when(repository.canAccessCompany(anyString(), anyString(), eq("company-1"), any()))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(repository.listAccessibleEmploymentPeriods(
                        anyString(), any(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(repository.listAllPriorServiceRecords(anyString()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(repository.findCurrentEmployee(anyString()))
                .thenAnswer(invocation -> Optional.empty());
    }

    @Test
    void createEmployeeRequiresDepartmentAndWritesEmployment() {
        when(repository.findCurrentOrganization("org-1")).thenReturn(Optional.of(organization()));
        when(repository.canAccessOrganization(anyString(), anyString(), eq("org-1"), any())).thenReturn(true);
        when(repository.employeeNumberExists("company-1", "SZST0743", null)).thenReturn(false);

        service.createEmployee(
                new CreateEmployee(
                        "company-1", "SZST0743", "张立强", null, "org-1",
                        LocalDate.parse("2026-09-07"), "9月入职", null),
                0,
                "idempotency-key-create-1");

        verify(repository).createEmployeeIdentity(
                anyString(), eq("company-1"), eq("SZST0743"), eq("张立强"),
                eq("ACTIVE"), eq(LocalDate.parse("2026-09-07")), eq(NOW));
        verify(repository).saveEmploymentPeriodVersion(any(), eq(true));
    }

    @Test
    void createEmployeeSchedulesQuarantineRecoveryFromHireDate() {
        NewHireAttendanceRecovery recovery = org.mockito.Mockito.mock(NewHireAttendanceRecovery.class);
        service = new PeopleManagementService(
                capabilities,
                principals,
                repository,
                audit,
                new SecurityTokenService(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                recovery);
        when(repository.findCurrentOrganization("org-1")).thenReturn(Optional.of(organization()));
        when(repository.canAccessOrganization(anyString(), anyString(), eq("org-1"), any())).thenReturn(true);
        when(repository.employeeNumberExists("company-1", "SZST0743", null)).thenReturn(false);

        service.createEmployee(
                new CreateEmployee(
                        "company-1", "SZST0743", "张立强", null, "org-1",
                        LocalDate.parse("2026-09-07"), "9月入职", null),
                0,
                "idempotency-key-create-recovery");

        verify(recovery).noteHire(LocalDate.parse("2026-09-07"));
    }

    @Test
    void createEmployeeAssignsChosenAttendanceGroupFromHireDate() {
        AttendanceGroupService groups = org.mockito.Mockito.mock(AttendanceGroupService.class);
        service = new PeopleManagementService(
                capabilities,
                principals,
                repository,
                audit,
                new SecurityTokenService(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                null,
                groups);
        when(repository.findCurrentOrganization("org-1")).thenReturn(Optional.of(organization()));
        when(repository.canAccessOrganization(anyString(), anyString(), eq("org-1"), any())).thenReturn(true);
        when(repository.employeeNumberExists("company-1", "SZST0743", null)).thenReturn(false);

        service.createEmployee(
                new CreateEmployee(
                        "company-1", "SZST0743", "张立强", null, "org-1",
                        LocalDate.parse("2026-09-07"), "9月入职", "group-yangzhou"),
                0,
                "idempotency-key-create-group");

        verify(groups).createAssignment(
                eq("group-yangzhou"),
                org.mockito.ArgumentMatchers.argThat(command ->
                        command.effectiveFrom().equals(LocalDate.parse("2026-09-07"))
                                && "9月入职".equals(command.reason())
                                && command.employeeId() != null
                                && !command.employeeId().isBlank()),
                eq("idempotency-key-create-group:group"));
    }

    @Test
    void createEmployeeWithoutDepartmentDoesNotCreateIdentity() {
        assertThatThrownBy(() -> service.createEmployee(
                new CreateEmployee(
                        "company-1", "SZST0743", "张立强", null, " ",
                        LocalDate.parse("2026-09-07"), "9月入职", null),
                0,
                "idempotency-key-create-2"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("任职部门不能为空");
        verify(repository, never()).createEmployeeIdentity(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void createOrganizationGeneratesCodeWhenBlank() {
        when(repository.organizationCodeExists(eq("company-1"), anyString(), isNull())).thenReturn(false);
        when(repository.findCurrentOrganization(anyString())).thenAnswer(invocation -> Optional.of(organization()));

        OrganizationVersion created = service.createOrganization(
                new CreateOrganization(
                        "company-1", null, "  ", "硬件组", "TEAM",
                        LocalDate.parse("2026-09-02"), "聚能新组"),
                0,
                "idempotency-key-org-1");

        verify(repository).createOrganizationIdentity(anyString(), eq("company-1"), eq("ACTIVE"), eq(NOW));
        assertThat(created).isNotNull();
    }

    @Test
    void createOrganizationRejectsParentInAnotherCompany() {
        when(repository.findCurrentOrganization("parent-2")).thenReturn(Optional.of(
                new OrganizationVersion(
                        "v1", "parent-2", "company-2", null, "P2", "其他公司部门",
                        "DEPARTMENT", "ACTIVE", LocalDate.parse("2026-01-01"),
                        null, "LOCAL", null, 0, "init", "actor", NOW, 0)));

        assertThatThrownBy(() -> service.createOrganization(
                new CreateOrganization(
                        "company-1", "parent-2", "HW", "硬件组", "TEAM",
                        LocalDate.parse("2026-09-02"), "跨公司"),
                0,
                "idempotency-key-org-2"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("不属于同一家公司");
        verify(repository, never()).createOrganizationIdentity(
                anyString(), anyString(), anyString(), any());
    }

    private static OrganizationVersion organization() {
        return new OrganizationVersion(
                "ov1", "org-1", "company-1", "root", "WH", "武汉产品服务组",
                "TEAM", "ACTIVE", LocalDate.parse("2026-01-01"),
                null, "LOCAL", null, 0, "init", "actor", NOW, 0);
    }
}
