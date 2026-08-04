package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.AuthorizedScope;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DailyTrendPoint;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardAnalytics;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardSnapshot;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.ExceptionSummary;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.OrganizationRankingItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.SeverityDistributionItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.TypeDistributionItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardService;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;

class AttendanceDashboardContractTest {

    private static final Path OPEN_API =
            Path.of("../api/openapi.yaml").toAbsolutePath().normalize();

    @Test
    void endpointRequiresExactDashboardReadCapability() throws Exception {
        Method method = AttendanceDashboardController.class
                .getDeclaredMethod(
                        "dashboard",
                        String.class,
                        org.springframework.util.MultiValueMap.class);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(
                        "hasAuthority('ATTENDANCE_DASHBOARD:READ')");
    }

    @Test
    void duplicateOrUnknownParametersFailBeforeDashboardRead() {
        AttendanceDashboardService service =
                mock(AttendanceDashboardService.class);
        var controller = new AttendanceDashboardController(service);
        var duplicate = new LinkedMultiValueMap<String, String>();
        duplicate.add("companyId", "company-a");
        duplicate.add("companyId", "company-b");
        var retired = new LinkedMultiValueMap<String, String>();
        retired.add("legalEntityId", "company-a");

        assertValidationError(() ->
                controller.dashboard("company-a", duplicate));
        assertValidationError(() ->
                controller.dashboard(null, retired));
        verifyNoInteractions(service);
    }

    @Test
    void multiCompanySelectionHasAParseableTwoHundredEnvelope() {
        var selection =
                new AttendanceDashboardService.CompanySelection(
                        LocalDate.of(2026, 7, 29),
                        List.of(
                                new CompanyOption("company-a", "公司甲"),
                                new CompanyOption("company-b", "公司乙")));

        var response = AttendanceDashboardResponse.from(selection);

        assertThat(response)
                .isInstanceOfSatisfying(
                        AttendanceDashboardResponse.CompanySelection.class,
                        view -> {
                            assertThat(view.kind()).isEqualTo(
                                    "DASHBOARD_COMPANY_SELECTION");
                            assertThat(view.selectedCompanyId()).isNull();
                            assertThat(view.companies()).hasSize(2);
                        });
    }

    @Test
    void readyEnvelopePublishesServerAggregatedAnalytics() {
        LocalDate businessDate = LocalDate.of(2026, 7, 29);
        CompanyOption company =
                new CompanyOption("company-a", "公司甲");
        var analytics = new DashboardAnalytics(
                List.of(
                        new DailyTrendPoint(
                                LocalDate.of(2026, 7, 28),
                                1,
                                0,
                                1),
                        new DailyTrendPoint(
                                businessDate,
                                2,
                                1,
                                2)),
                List.of(
                        new SeverityDistributionItem("INFO", 0),
                        new SeverityDistributionItem("WARNING", 1),
                        new SeverityDistributionItem("ERROR", 1)),
                List.of(new TypeDistributionItem(
                        "MISSING_PUNCH_PENDING", 2)),
                List.of(new OrganizationRankingItem(
                        "制造中心", 2, 1)));
        var snapshot = new DashboardSnapshot(
                "company-a",
                "projection-1",
                List.of("deli:20"),
                Instant.parse("2026-07-29T01:00:00Z"),
                "OPEN",
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "authorized-scope-set:abc",
                        "公司授权范围",
                        "a".repeat(64)),
                new ExceptionSummary(2, 2, 1),
                analytics,
                List.of());
        var result = new AttendanceDashboardService.Ready(
                businessDate,
                company,
                List.of(company),
                snapshot,
                List.of());

        var response = AttendanceDashboardResponse.from(result);

        assertThat(response)
                .isInstanceOfSatisfying(
                        AttendanceDashboardResponse.Ready.class,
                        view -> {
                            assertThat(view.analytics().dailyTrend())
                                    .hasSize(2)
                                    .last()
                                    .extracting(
                                            point -> point.exceptionCount())
                                    .isEqualTo(2L);
                            assertThat(view.analytics()
                                            .severityDistribution())
                                    .extracting(item -> item.severity())
                                    .containsExactly(
                                            "INFO",
                                            "WARNING",
                                            "ERROR");
                            assertThat(view.analytics()
                                            .organizationRanking())
                                    .extracting(
                                            item -> item.organizationName())
                                    .containsExactly("制造中心");
                        });
    }

    @Test
    void openApiPromotesDashboardAndFreezesItsSafeFields()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String path = between(
                contract,
                "  /attendance-dashboards:",
                "  /attendance-reports:");
        String view = between(
                contract,
                "    AttendanceDashboardView:",
                "    AttendanceDashboardCompanySelection:");
        String analytics = between(
                contract,
                "    AttendanceDashboardAnalytics:",
                "    AttendanceDashboardException:");
        String exception = between(
                contract,
                "    AttendanceDashboardException:",
                "    AttendanceDashboardCompanyOption:");

        assertThat(path)
                .contains("operationId: getAttendanceDashboard")
                .contains("x-capability: ATTENDANCE_DASHBOARD:READ")
                .contains("AttendanceDashboardView")
                .contains("AttendanceDashboardCompanySelection")
                .contains("'409'");
        assertThat(view)
                .contains("const: DASHBOARD")
                .contains("selectedCompanyId")
                .contains("summary")
                .contains("analytics")
                .contains("exceptions")
                .contains("companies");
        assertThat(analytics)
                .contains(
                        "dailyTrend",
                        "severityDistribution",
                        "typeDistribution",
                        "organizationRanking",
                        "maxItems: 7",
                        "maxItems: 10",
                        "maxItems: 5",
                        "INFO",
                        "WARNING",
                        "ERROR");
        assertThat(exception)
                .contains("maxLength: 500")
                .contains("PENDING_EVIDENCE")
                .contains("PENDING_REVIEW")
                .doesNotContain(
                        "coordinate",
                        "device",
                        "payroll",
                        "leaveReason");
        assertThat(contract)
                .doesNotContain(
                        "method: GET, path: /attendance-dashboards,"
                                + " responseSchema:"
                                + " AttendanceDashboardView");
    }

    private static void assertValidationError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo("VALIDATION_ERROR"));
    }

    private static String between(
            String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(
                end, startIndex + start.length());
        return value.substring(startIndex, endIndex);
    }
}
