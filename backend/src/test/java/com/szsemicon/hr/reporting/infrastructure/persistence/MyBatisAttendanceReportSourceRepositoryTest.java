package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.reporting.application.DepartmentPathNames;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceWorkWindowQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MyBatisAttendanceReportSourceRepositoryTest {

    @Test
    void multipleScopesHaveAnOrderIndependentOpaqueDigestAndSafeLabel() {
        var self = new ReportRows.ScopeRow(
                "scope-self",
                "SELF",
                null,
                null,
                false,
                "employee-secret");
        var organization = new ReportRows.ScopeRow(
                "scope-organization",
                "ORGANIZATION",
                null,
                "organization-secret",
                true,
                "employee-secret");

        var forward =
                MyBatisAttendanceReportSourceRepository.authorizedScope(
                        List.of(self, organization));
        var reverse =
                MyBatisAttendanceReportSourceRepository.authorizedScope(
                        List.of(organization, self, self));

        assertThat(reverse).isEqualTo(forward);
        assertThat(forward.type()).isEqualTo(ScopeType.ORGANIZATION);
        assertThat(forward.label()).isEqualTo("组合授权范围");
        assertThat(forward.authorizationDigest()).matches("[a-f0-9]{64}");
        assertThat(forward.reference())
                .isEqualTo(
                        "authorized-scope-set:"
                                + forward.authorizationDigest())
                .doesNotContain(
                        "scope-self",
                        "scope-organization",
                        "organization-secret",
                        "employee-secret");
    }

    @Test
    void realtimeAuthorizationDigestIncludesExpandedEmployeesAndOrganizations() {
        var scope = new ReportRows.ScopeRow(
                "scope-organization",
                "ORGANIZATION",
                null,
                "organization-root",
                true,
                "employee-self");

        var before = MyBatisAttendanceReportSourceRepository.authorizedScope(
                List.of(scope),
                Set.of("employee-self"),
                Set.of("organization-root"));
        var reordered = MyBatisAttendanceReportSourceRepository.authorizedScope(
                List.of(scope),
                Set.of("employee-child", "employee-self"),
                Set.of("organization-child", "organization-root"));
        var sameReordered =
                MyBatisAttendanceReportSourceRepository.authorizedScope(
                        List.of(scope),
                        Set.of("employee-self", "employee-child"),
                        Set.of("organization-root", "organization-child"));

        assertThat(reordered).isEqualTo(sameReordered);
        assertThat(reordered.authorizationDigest())
                .isNotEqualTo(before.authorizationDigest())
                .matches("[a-f0-9]{64}");
    }

    @Test
    void unsupportedCapabilityFailsClosedBeforeAnyDatabaseRead() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());

        var result = repository.loadAuthorizedSnapshot(
                "principal-1",
                "ATTENDANCE_REPORT:EXPORT",
                new ReportFilter(
                        YearMonth.of(2026, 7),
                        null,
                        null,
                        null,
                        null),
                Instant.parse("2026-07-29T00:00:00Z"));

        assertThat(result).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void authorizedCompanyDirectoryReturnsOnlyMapperAuthorizedRows() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var period = YearMonth.of(2026, 7);
        var authorizationTime =
                Instant.parse("2026-07-29T00:00:00Z");
        when(mapper.listAuthorizedCompanies(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        authorizationTime))
                .thenReturn(List.of(
                        new ReportRows.CompanyRow(
                                "company-a", "神州半导体"),
                        new ReportRows.CompanyRow(
                                "company-b", "神州科技")));

        assertThat(repository.listAuthorizedCompanies(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        period,
                        authorizationTime))
                .extracting(option -> option.companyId())
                .containsExactly("company-a", "company-b");
    }

    @Test
    void principalHomeKeepsOneEmployeeAcrossMultipleOrganizationRows() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var businessDate = java.time.LocalDate.parse("2026-08-19");
        when(mapper.resolvePrincipalHome("principal-1", businessDate))
                .thenReturn(List.of(
                        new ReportRows.PrincipalHomeRow(
                                "employee-1",
                                "SZST0004",
                                "company-a",
                                "神州半导体",
                                "org-sales",
                                "销售中心"),
                        new ReportRows.PrincipalHomeRow(
                                "employee-1",
                                "SZST0004",
                                "company-a",
                                "神州半导体",
                                "org-support",
                                "技术支持中心")));

        var home = repository.resolvePrincipalHome(
                "principal-1", businessDate);

        assertThat(home).isPresent();
        assertThat(home.orElseThrow().employeeNumber())
                .isEqualTo("SZST0004");
        assertThat(home.orElseThrow().organizationName())
                .isEqualTo("销售中心");
    }

    @Test
    void realtimeAuthorizationExpandsCurrentScopeWithoutProjectionRead() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var authorizationTime =
                Instant.parse("2026-08-17T04:00:00Z");
        var organizationScope = new ReportRows.ScopeRow(
                "scope-organization",
                "ORGANIZATION",
                null,
                "organization-root",
                true,
                "employee-self");
        var selfScope = new ReportRows.ScopeRow(
                "scope-self",
                "SELF",
                null,
                null,
                false,
                "employee-self");
        var scopes = List.of(organizationScope, selfScope);
        when(mapper.listRealtimeAuthorizedScopes(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        "company-a",
                        authorizationTime))
                .thenReturn(scopes);
        when(mapper.listAuthorizedEmployeeIdsInScopeIntersection(
                        "company-a", scopes, scopes, authorizationTime))
                .thenReturn(List.of("employee-self", "employee-child"));
        when(mapper.listAuthorizedOrganizationIds(
                        "company-a", scopes, authorizationTime))
                .thenReturn(List.of(
                        "organization-root", "organization-child"));

        var result = repository.resolveRealtimeAuthorization(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                "company-a",
                authorizationTime);

        assertThat(result).isPresent();
        var authorization = result.orElseThrow();
        assertThat(authorization.scope().type())
                .isEqualTo(ScopeType.ORGANIZATION);
        assertThat(authorization.companyId()).isEqualTo("company-a");
        assertThat(authorization.companyWide()).isFalse();
        assertThat(authorization.principalEmployeeId())
                .isEqualTo("employee-self");
        assertThat(authorization.employeeIds())
                .containsExactlyInAnyOrder(
                        "employee-self", "employee-child");
        assertThat(authorization.organizationIds())
                .containsExactlyInAnyOrder(
                        "organization-root", "organization-child");
        assertThatThrownBy(() -> authorization.employeeIds().add("employee-x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> authorization.organizationIds()
                        .add("organization-x"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(mapper, never()).listLatestAuthorizedProjections(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void companyWideAuthorizationDoesNotExpandEveryEmployeeId() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var authorizationTime = Instant.parse("2026-08-17T04:00:00Z");
        when(mapper.listRealtimeAuthorizedScopes(
                        "principal-admin",
                        "ATTENDANCE_DASHBOARD:READ",
                        "company-a",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ScopeRow(
                        "scope-company",
                        "COMPANY",
                        "company-a",
                        null,
                        false,
                        null)));

        var result = repository.resolveRealtimeAuthorization(
                "principal-admin",
                "ATTENDANCE_DASHBOARD:READ",
                "company-a",
                authorizationTime);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().companyWide()).isTrue();
        assertThat(result.orElseThrow().employeeIds()).isEmpty();
        verify(mapper, never()).listAuthorizedEmployeeIdsInScopeIntersection(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).listAuthorizedOrganizationIds(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void selfAuthorizationDoesNotExpandTheWholeCompanyRoster() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var authorizationTime = Instant.parse("2026-08-17T04:00:00Z");
        when(mapper.listRealtimeAuthorizedScopes(
                        "principal-self",
                        "ATTENDANCE_SELF:READ",
                        "company-a",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ScopeRow(
                        "scope-self",
                        "SELF",
                        null,
                        null,
                        false,
                        "employee-self")));

        var result = repository.resolveRealtimeAuthorization(
                "principal-self",
                "ATTENDANCE_SELF:READ",
                "company-a",
                authorizationTime);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().scope().type())
                .isEqualTo(ScopeType.SELF);
        assertThat(result.orElseThrow().employeeIds())
                .containsExactly("employee-self");
        verify(mapper, never()).listAuthorizedEmployeeIdsInScopeIntersection(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).listAuthorizedOrganizationIds(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void realtimeAuthorizationRejectsCrossCompanyOrMalformedScopeRows() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var authorizationTime =
                Instant.parse("2026-08-17T04:00:00Z");
        when(mapper.listRealtimeAuthorizedScopes(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        "company-a",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ScopeRow(
                        "scope-company-b",
                        "COMPANY",
                        "company-b",
                        null,
                        true,
                        null)));

        assertThat(repository.resolveRealtimeAuthorization(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        "company-a",
                        authorizationTime))
                .isEmpty();
        verify(mapper, never())
                .listAuthorizedEmployeeIdsInScopeIntersection(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).listAuthorizedOrganizationIds(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unsupportedRealtimeCapabilityFailsClosedBeforeAnyDatabaseRead() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());

        assertThat(repository.resolveRealtimeAuthorization(
                        "principal-1",
                        "EMPLOYEE:READ",
                        "company-a",
                        Instant.parse("2026-08-17T04:00:00Z")))
                .isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void multipleAuthorizedCompaniesFailClosedUntilApiSelectsOne() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var period = YearMonth.of(2026, 7);
        var authorizationTime =
                Instant.parse("2026-07-29T00:00:00Z");
        when(mapper.listLatestAuthorizedProjections(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        null,
                        authorizationTime))
                .thenReturn(List.of(
                        new ReportRows.ProjectionRow(
                                "projection-company-a",
                                "company-a",
                                "version-a",
                                "OPEN",
                                "[]",
                                authorizationTime),
                        new ReportRows.ProjectionRow(
                                "projection-company-b",
                                "company-b",
                                "version-b",
                                "OPEN",
                                "[]",
                                authorizationTime)));

        var result = repository.loadAuthorizedSnapshot(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                new ReportFilter(
                        period, null, null, null, null),
                authorizationTime);

        assertThat(result).isEmpty();
    }

    @Test
    void oneAuthorizedCompanyRemainsBackwardCompatibleAndIsResolved() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var period = YearMonth.of(2026, 7);
        var authorizationTime =
                Instant.parse("2026-07-29T00:00:00Z");
        when(mapper.listLatestAuthorizedProjections(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        null,
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ProjectionRow(
                        "projection-company-a",
                        "company-a",
                        "version-a",
                        "OPEN",
                        "[]",
                        authorizationTime)));
        when(mapper.listAuthorizedScopes(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        "projection-company-a",
                        "company-a",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ScopeRow(
                        "scope-company-a",
                        "COMPANY",
                        "company-a",
                        null,
                        true,
                        null)));

        var result = repository.loadAuthorizedSnapshot(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                new ReportFilter(period, null, null, null, null),
                authorizationTime);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().filter().companyId())
                .isEqualTo("company-a");
    }

    @Test
    void explicitCompanySelectionNarrowsAuthorizedProjection() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var period = YearMonth.of(2026, 7);
        var authorizationTime =
                Instant.parse("2026-07-29T00:00:00Z");
        var filter = new ReportFilter(
                period, "company-b", null, null, null);
        when(mapper.listLatestAuthorizedProjections(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        "company-b",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ProjectionRow(
                        "projection-company-b",
                        "company-b",
                        "version-b",
                        "OPEN",
                        "[]",
                        authorizationTime)));
        when(mapper.listAuthorizedScopes(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        "projection-company-b",
                        "company-b",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ScopeRow(
                        "scope-company-b",
                        "COMPANY",
                        "company-b",
                        null,
                        true,
                        null)));

        var result = repository.loadAuthorizedSnapshot(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                filter,
                authorizationTime);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().filter().companyId())
                .isEqualTo("company-b");
        verify(mapper).listLatestAuthorizedProjections(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                period.atDay(1),
                period.plusMonths(1).atDay(1),
                "company-b",
                authorizationTime);
    }

    @Test
    void organizationScopesInDifferentCompaniesRemainSelectableWithoutRoleBypass() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var period = YearMonth.of(2026, 7);
        var authorizationTime =
                Instant.parse("2026-07-29T00:00:00Z");
        for (String suffix : List.of("a", "b")) {
            String companyId = "company-" + suffix;
            String projectionId = "projection-company-" + suffix;
            when(mapper.listLatestAuthorizedProjections(
                            "principal-1",
                            "ATTENDANCE_REPORT:READ",
                            period.atDay(1),
                            period.plusMonths(1).atDay(1),
                            companyId,
                            authorizationTime))
                    .thenReturn(List.of(new ReportRows.ProjectionRow(
                            projectionId,
                            companyId,
                            "version-" + suffix,
                            "OPEN",
                            "[]",
                            authorizationTime)));
            when(mapper.listAuthorizedScopes(
                            "principal-1",
                            "ATTENDANCE_REPORT:READ",
                            projectionId,
                            companyId,
                            authorizationTime))
                    .thenReturn(List.of(new ReportRows.ScopeRow(
                            "scope-org-" + suffix,
                            "ORGANIZATION",
                            null,
                            "organization-" + suffix,
                            true,
                            null)));

            var result = repository.loadAuthorizedSnapshot(
                    "principal-1",
                    "ATTENDANCE_REPORT:READ",
                    new ReportFilter(
                            period,
                            companyId,
                            "organization-" + suffix,
                            null,
                            null),
                    authorizationTime);

            assertThat(result).isPresent();
            assertThat(result.orElseThrow().filter().companyId())
                    .isEqualTo(companyId);
            assertThat(result.orElseThrow().scope().type())
                    .isEqualTo(ScopeType.ORGANIZATION);
        }
    }

    @Test
    void mismatchedProjectionCannotEscapeExplicitCompanySelection() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var period = YearMonth.of(2026, 7);
        var authorizationTime =
                Instant.parse("2026-07-29T00:00:00Z");
        when(mapper.listLatestAuthorizedProjections(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        "company-a",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ProjectionRow(
                        "projection-company-b",
                        "company-b",
                        "version-b",
                        "OPEN",
                        "[]",
                        authorizationTime)));

        var result = repository.loadAuthorizedSnapshot(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                new ReportFilter(
                        period, "company-a", null, null, null),
                authorizationTime);

        assertThat(result).isEmpty();
        verify(mapper).listLatestAuthorizedProjections(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                period.atDay(1),
                period.plusMonths(1).atDay(1),
                "company-a",
                authorizationTime);
    }

    @Test
    void pinnedDailyFactsUseClosureDepartmentPathAtReadTime() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper,
                new ObjectMapper());
        var period = YearMonth.of(2026, 8);
        var authorizationTime =
                Instant.parse("2026-08-21T00:00:00Z");
        when(mapper.listLatestAuthorizedProjections(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        "company-a",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ProjectionRow(
                        "projection-company-a",
                        "company-a",
                        "version-a",
                        "OPEN",
                        "[]",
                        authorizationTime)));
        when(mapper.listAuthorizedScopes(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        "projection-company-a",
                        "company-a",
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.ScopeRow(
                        "scope-company-a",
                        "COMPANY",
                        "company-a",
                        null,
                        true,
                        null)));
        when(mapper.listAuthorizedDailyFacts(
                        "principal-1",
                        "ATTENDANCE_REPORT:READ",
                        "projection-company-a",
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        "company-a",
                        null,
                        null,
                        authorizationTime))
                .thenReturn(List.of(new ReportRows.DailyRow(
                        "fact-1",
                        "company-a",
                        "employee-1",
                        "SZST0001",
                        "张三",
                        "leaf-id",
                        "org-version-1",
                        "DC组",
                        LocalDate.of(2026, 8, 3),
                        "WEEKDAY",
                        "白班",
                        480,
                        480,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        null,
                        0,
                        480,
                        1,
                        1.0,
                        0,
                        0,
                        0,
                        0,
                        null,
                        null,
                        "calc-1",
                        "digest-1")));
        when(mapper.findCurrentOrganizationAncestors("company-a"))
                .thenReturn(List.of(
                        new ReportRows.OrganizationAncestorRow(
                                "leaf-id",
                                "江苏神州半导体科技股份有限公司",
                                "COMPANY",
                                3),
                        new ReportRows.OrganizationAncestorRow(
                                "leaf-id",
                                "服务中心",
                                "DEPARTMENT",
                                2),
                        new ReportRows.OrganizationAncestorRow(
                                "leaf-id",
                                "工程一部",
                                "DEPARTMENT",
                                1),
                        new ReportRows.OrganizationAncestorRow(
                                "leaf-id",
                                "DC组",
                                "DEPARTMENT",
                                0)));

        var result = repository.loadAuthorizedSnapshot(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                new ReportFilter(period, "company-a", null, null, null),
                authorizationTime);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().dailyFacts())
                .extracting(fact -> DepartmentPathNames.visibleDepartment(
                        fact.organizationName()))
                .containsExactly("服务中心-工程一部-DC组");
    }

    @Test
    void pagedMatrixReadDoesNotReconstructCompanyShiftSegments() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        AttendanceWorkWindowQuery workWindows =
                mock(AttendanceWorkWindowQuery.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper, workWindows, new ObjectMapper());
        var period = YearMonth.of(2026, 8);
        var authorizationTime = Instant.parse("2026-08-26T00:00:00Z");
        when(mapper.listLatestAuthorizedProjections(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new ReportRows.ProjectionRow(
                        "projection-1",
                        "company-a",
                        "version-a",
                        "OPEN",
                        "[]",
                        authorizationTime)));
        when(mapper.listAuthorizedScopes(
                        any(), any(), any(), any(), any()))
                .thenReturn(List.of(new ReportRows.ScopeRow(
                        "scope-1",
                        "COMPANY",
                        "company-a",
                        null,
                        true,
                        null)));
        when(mapper.countAuthorizedMatrixEmployees(
                        any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(mapper.listAuthorizedMatrixEmployees(
                        any(), any(), any(), any(), any(), any(), any(), any(),
                        anyInt(), anyInt()))
                .thenReturn(List.of(new ReportRows.MatrixEmployeeRow(
                        "employee-1", "0001", "张三")));
        when(mapper.listAuthorizedDailyFactsForEmployees(
                        any(), any(), any(), any(), any(), anyList()))
                .thenReturn(List.of());
        when(mapper.listAuthorizedOaFactsForEmployees(
                        any(), any(), any(), any(), any(), anyList()))
                .thenReturn(List.of());
        when(mapper.listAuthorizedExceptionFactsForEmployees(
                        any(), any(), any(), any(), any(), any(), any(),
                        anyList()))
                .thenReturn(List.of());
        when(mapper.findCurrentOrganizationGraph(any()))
                .thenReturn(List.of());
        when(mapper.findCurrentOrganizationAncestors(any()))
                .thenReturn(List.of());

        var result = repository.loadAuthorizedPagedSnapshot(
                "principal-1",
                "ATTENDANCE_REPORT:READ",
                new ReportFilter(period, "company-a", null, null, null),
                0,
                50,
                authorizationTime);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().snapshot().workWindows()).isEmpty();
        verify(workWindows, never()).list(any(), any(), any());
        verify(mapper, never()).listNegativeLeaveBalanceExceptions(
                any(), anyInt(), any(), any());
    }

    @Test
    void departmentPathsAreReusedWithinTtl() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceReportSourceRepository(
                mapper, new ObjectMapper());
        when(mapper.findCurrentOrganizationGraph("company-a"))
                .thenReturn(List.of());
        when(mapper.findCurrentOrganizationAncestors("company-a"))
                .thenReturn(List.of());

        repository.reportDepartmentPaths("company-a");
        repository.reportDepartmentPaths("company-a");

        verify(mapper, times(1)).findCurrentOrganizationGraph("company-a");
        verify(mapper, times(1)).findCurrentOrganizationAncestors("company-a");
    }
}
