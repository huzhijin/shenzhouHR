package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.ExceptionTypeCount;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.RecentException;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardService;
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

class SelfAttendanceDashboardContractTest {

    private static final Path OPEN_API =
            Path.of("../api/openapi.yaml")
                    .toAbsolutePath()
                    .normalize();

    @Test
    void endpointRequiresExactSelfCapabilityAndRejectsAllParameters()
            throws Exception {
        Method method = SelfAttendanceDashboardController.class
                .getDeclaredMethod(
                        "dashboard",
                        java.time.YearMonth.class,
                        String.class,
                        org.springframework.util.MultiValueMap.class);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(
                        "hasAuthority('ATTENDANCE_SELF:READ')");
        var service = mock(SelfAttendanceDashboardService.class);
        var controller =
                new SelfAttendanceDashboardController(service);
        var parameters =
                new LinkedMultiValueMap<String, String>();
        parameters.add("companyId", "company-a");

        assertThatThrownBy(() ->
                controller.dashboard(null, null, parameters))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void readyResponseIsNoStoreAndContainsOnlySelfSafeFields() {
        var service = mock(SelfAttendanceDashboardService.class);
        when(service.query(null, null)).thenReturn(dashboard());
        var response = new SelfAttendanceDashboardController(service)
                .dashboard(null, null, new LinkedMultiValueMap<>());

        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().kind())
                .isEqualTo("SELF_ATTENDANCE_DASHBOARD");
        assertThat(response.getBody().metadata().scope())
                .isEqualTo(new SelfAttendanceDashboardResponse.Scope(
                        "SELF", "current-principal", "本人"));
        assertThat(response.getBody().today().statusLabel())
                .isEqualTo("存在未解决异常");
        assertThat(response.getBody().recentExceptions())
                .extracting(
                        SelfAttendanceDashboardResponse
                                .RecentException::safeEvidenceSummary)
                .containsExactly("下班有效打卡缺失");
    }

    @Test
    void openApiPromotesSelfDashboardWithoutCrossEmployeeFields()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String path = pathItem(
                contract,
                "  /me/attendance-dashboard:");
        String schemas = between(
                contract,
                "    SelfAttendanceDashboardScope:",
                "    AttendanceDashboardScope:");

        assertThat(path)
                .contains(
                        "operationId: getSelfAttendanceDashboard")
                .contains("x-capability: ATTENDANCE_SELF:READ")
                .contains("SelfAttendanceDashboardView")
                .contains("'403'")
                .contains("'409'")
                .doesNotContain(
                        "companyId",
                        "employeeId",
                        "organizationId");
        assertThat(schemas)
                .contains(
                        "const: SELF_ATTENDANCE_DASHBOARD",
                        "const: SELF",
                        "const: current-principal",
                        "dailyTrend",
                        "today",
                        "exceptionTypeDistribution",
                        "recentExceptions",
                        "safeEvidenceSummary",
                        "maxItems: 10")
                .doesNotContain(
                        "employeeId",
                        "employeeNumber",
                        "employeeName",
                        "organizationId",
                        "organizationName",
                        "companyId",
                        "exceptionReference",
                        "caseId",
                        "rawPunch",
                        "device",
                        "payroll");
    }

    private static SelfAttendanceDashboardService.Dashboard dashboard() {
        LocalDate date = LocalDate.of(2026, 7, 29);
        return new SelfAttendanceDashboardService.Dashboard(
                date,
                "self-projection-1",
                List.of("deli:20"),
                Instant.parse("2026-07-29T01:00:00Z"),
                "OPEN",
                new SelfAttendanceDashboardService.Summary(
                        480, 450, 30, 0, 1),
                List.of(
                        new SelfAttendanceDashboardService
                                .DailyTrendPoint(
                                        date,
                                        480,
                                        450,
                                        30,
                                        0,
                                        1,
                                        Instant.parse("2026-07-29T00:25:00Z"),
                                        null)),
                new SelfAttendanceDashboardService.Today(
                        "日班",
                        Instant.parse("2026-07-29T00:25:00Z"),
                        null,
                        "存在未解决异常",
                        450,
                        List.of("MISSING_PUNCH")),
                List.of(new ExceptionTypeCount(
                        "MISSING_PUNCH", 1)),
                List.of(new RecentException(
                        date,
                        "MISSING_PUNCH",
                        "ERROR",
                        "OPEN",
                        0,
                        "下班有效打卡缺失")));
    }

    private static String between(
            String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(
                end, startIndex + start.length());
        return value.substring(startIndex, endIndex);
    }

    private static String pathItem(
            String contract, String pathStart) {
        int startIndex = contract.indexOf(pathStart);
        int endIndex = contract.indexOf(
                "\n  /", startIndex + pathStart.length());
        return contract.substring(startIndex, endIndex);
    }
}
