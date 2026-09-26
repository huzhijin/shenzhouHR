package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationApplicationService;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublicationResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.YearMonth;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Allows an authorized administrator to manually trigger a report projection
 * publication for a given company-month.
 *
 * <p>{@code POST /api/v1/attendance-reports/publications}</p>
 *
 * <p>The endpoint returns {@code 503 CALCULATION_ENGINE_NOT_AVAILABLE} until
 * an {@code AttendanceReportCalculationOrchestrator} bean is registered.</p>
 */
@RestController
@RequestMapping("/api/v1/attendance-reports/publications")
public class AttendanceReportPublicationController {

    private final AttendanceReportPublicationApplicationService service;

    public AttendanceReportPublicationController(
            AttendanceReportPublicationApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(
            "hasAuthority('"
                    + CapabilityCodes.ATTENDANCE_REPORT_REFRESH
                    + "')")
    ResponseEntity<PublicationView> publish(
            @Valid @RequestBody PublishRequest request) {
        PublicationResult result = service.publish(
                request.companyId(),
                request.period(),
                request.periodState(),
                request.reason());
        HttpStatus status = result.created()
                ? HttpStatus.CREATED
                : HttpStatus.OK;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(PublicationView.from(result));
    }

    record PublishRequest(
            @NotBlank @Size(max = 36) String companyId,
            @NotNull YearMonth period,
            @NotNull PeriodState periodState,
            @NotBlank @Size(min = 2, max = 500) String reason) {

        @Override
        public String toString() {
            return "PublishRequest[companyId=" + companyId
                    + ", period=" + period
                    + ", periodState=" + periodState
                    + ", reason=<redacted>]";
        }
    }

    record PublicationView(
            String projectionId,
            String projectionVersion,
            String projectionDigest,
            String periodState,
            String dataAsOf,
            String publishedAt,
            boolean created) {

        static PublicationView from(PublicationResult result) {
            return new PublicationView(
                    result.projectionId(),
                    result.projectionVersion(),
                    result.projectionDigest(),
                    result.periodState().name(),
                    result.dataAsOf().toString(),
                    result.publishedAt().toString(),
                    result.created());
        }
    }
}
