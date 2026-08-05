package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
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
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
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
}
