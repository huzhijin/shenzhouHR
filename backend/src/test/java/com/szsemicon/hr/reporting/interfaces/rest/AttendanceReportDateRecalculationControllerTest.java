package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.reporting.application.AttendanceReportRecalculateResult;
import com.szsemicon.hr.reporting.application.RecalcWindow;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttendanceReportDateRecalculationControllerTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private final AttendanceReportQueryService service = mock(AttendanceReportQueryService.class);
    private final AttendanceReportController controller = new AttendanceReportController(
            service, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AttendanceReportRecalculateResult result = new AttendanceReportRecalculateResult(
            "new-pin", NOW, List.of("OA:refreshed"), false);

    @Test
    void companyDatesWithoutEmployeesUseExactCompanyDateRoute() {
        when(service.recalculateCompanyDays(MONTH, "company-1", DAY, DAY)).thenReturn(result);
        var response = controller.recalculate(request(null, DAY, DAY, null));
        assertThat(response.getBody().projectionVersion()).isEqualTo("new-pin");
        verify(service).recalculateCompanyDays(MONTH, "company-1", DAY, DAY);
        verifyNoMoreInteractions(service);
    }

    @Test
    void emptyEmployeeListWithDatesAlsoUsesCompanyDateRoute() {
        when(service.recalculateCompanyDays(MONTH, "company-1", DAY, DAY)).thenReturn(result);
        controller.recalculate(request(List.of(), DAY, DAY, "MONTH"));
        verify(service).recalculateCompanyDays(MONTH, "company-1", DAY, DAY);
        verifyNoMoreInteractions(service);
    }

    @Test
    void employeeDateRequestRetainsItsExistingEmployeeRoute() {
        when(service.recalculateEmployees(MONTH, "company-1", List.of("employee-1"), DAY, DAY, null))
                .thenReturn(result);
        controller.recalculate(request(List.of("employee-1"), DAY, DAY, null));
        verify(service).recalculateEmployees(MONTH, "company-1", List.of("employee-1"), DAY, DAY, null);
        verifyNoMoreInteractions(service);
    }

    @Test
    void omittedDatesRetainTheMonthlyRouteAndJsonContract() throws Exception {
        var request = json.readValue("""
                {"companyId":"company-1","period":"2026-09","window":"MONTH"}
                """, AttendanceReportController.RecalculateRequest.class);
        when(service.recalculate(MONTH, "company-1", RecalcWindow.MONTH)).thenReturn(result);
        controller.recalculate(request);
        verify(service).recalculate(MONTH, "company-1", RecalcWindow.MONTH);
        verifyNoMoreInteractions(service);
    }

    @Test
    void incompleteReversedOrCrossMonthDatesFailBeforeAnyCalculation() {
        for (LocalDate[] invalid : List.of(
                new LocalDate[] {DAY, null}, new LocalDate[] {null, DAY},
                new LocalDate[] {DAY, DAY.minusDays(1)},
                new LocalDate[] {DAY.minusMonths(1), DAY},
                new LocalDate[] {DAY, DAY.plusDays(1)})) {
            assertThatThrownBy(() -> request(null, invalid[0], invalid[1], null))
                    .isInstanceOf(ApiProblemException.class).extracting("code")
                    .isEqualTo("VALIDATION_ERROR");
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "2026-09-31", "not-a-date"})
    void malformedOrEmptyJsonDatesCannotBecomeAnImplicitMonthRequest(String date) {
        String input = "{\"companyId\":\"company-1\",\"period\":\"2026-09\","
                + "\"fromDate\":\"" + date + "\",\"toDate\":\"" + date + "\"}";
        assertThatThrownBy(() -> json.readValue(input, AttendanceReportController.RecalculateRequest.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        verifyNoInteractions(service);
    }

    @Test
    void explicitlyNullJsonDatesCannotBecomeAnImplicitMonthRequest() {
        assertThatThrownBy(() -> json.readValue("""
                {"companyId":"company-1","period":"2026-09","fromDate":null,"toDate":null}
                """, AttendanceReportController.RecalculateRequest.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        verifyNoInteractions(service);
    }

    @Test
    void invalidWindowCannotSilentlyBecomeMonth() {
        assertThatThrownBy(() -> request(null, null, null, "DAYY"))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(service);
    }

    private AttendanceReportController.RecalculateRequest request(
            List<String> employeeIds, LocalDate from, LocalDate to, String window) {
        return new AttendanceReportController.RecalculateRequest(
                "company-1", MONTH, window, employeeIds, from, to);
    }
}
