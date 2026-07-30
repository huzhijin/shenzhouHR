package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;

class AttendanceReportControllerLegacyParameterTest {

    @Test
    void retiredCompanyQueryParameterFailsBeforeAnyReportRead() {
        AttendanceReportQueryService queryService =
                mock(AttendanceReportQueryService.class);
        var controller = new AttendanceReportController(
                queryService,
                Clock.fixed(
                        Instant.parse("2026-07-29T00:00:00Z"),
                        ZoneOffset.UTC));
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add(
                "legalEntityId",
                "30000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> controller.report(
                        ReportType.ATTENDANCE_DETAIL,
                        YearMonth.of(2026, 7),
                        null,
                        null,
                        null,
                        null,
                        0,
                        50,
                        parameters))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            org.assertj.core.api.Assertions.assertThat(
                                            problem.status())
                                    .isEqualTo(HttpStatus.BAD_REQUEST);
                            org.assertj.core.api.Assertions.assertThat(
                                            problem.code())
                                    .isEqualTo("VALIDATION_ERROR");
                        });
        verifyNoInteractions(queryService);
    }

    @Test
    void duplicateCompanyQueryParameterFailsBeforeAnyReportRead() {
        AttendanceReportQueryService queryService =
                mock(AttendanceReportQueryService.class);
        var controller = controller(queryService);
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add(
                "companyId",
                "30000000-0000-0000-0000-000000000001");
        parameters.add(
                "companyId",
                "30000000-0000-0000-0000-000000000002");

        assertValidationError(() -> controller.report(
                ReportType.ATTENDANCE_DETAIL,
                YearMonth.of(2026, 7),
                "30000000-0000-0000-0000-000000000001",
                null,
                null,
                null,
                0,
                50,
                parameters));
        verifyNoInteractions(queryService);
    }

    @Test
    void retiredCompanyDirectoryParameterFailsBeforeAnyDirectoryRead() {
        AttendanceReportQueryService queryService =
                mock(AttendanceReportQueryService.class);
        var controller = controller(queryService);
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add(
                "legalEntityId",
                "30000000-0000-0000-0000-000000000001");

        assertValidationError(() -> controller.companies(
                YearMonth.of(2026, 7), parameters));
        verifyNoInteractions(queryService);
    }

    private static AttendanceReportController controller(
            AttendanceReportQueryService queryService) {
        return new AttendanceReportController(
                queryService,
                Clock.fixed(
                        Instant.parse("2026-07-29T00:00:00Z"),
                        ZoneOffset.UTC));
    }

    private static void assertValidationError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            org.assertj.core.api.Assertions.assertThat(
                                            problem.status())
                                    .isEqualTo(HttpStatus.BAD_REQUEST);
                            org.assertj.core.api.Assertions.assertThat(
                                            problem.code())
                                    .isEqualTo("VALIDATION_ERROR");
                        });
    }
}
