package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.AttendanceDashboardService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-dashboards")
public class AttendanceDashboardController {

    private static final Set<String> PARAMETERS = Set.of("companyId");

    private final AttendanceDashboardService dashboardService;

    public AttendanceDashboardController(
            AttendanceDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ATTENDANCE_DASHBOARD:READ')")
    ResponseEntity<AttendanceDashboardResponse> dashboard(
            @RequestParam(required = false) String companyId,
            @RequestParam MultiValueMap<String, String> requestParameters) {
        rejectUnknownParameters(requestParameters);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AttendanceDashboardResponse.from(
                        dashboardService.query(companyId)));
    }

    private static void rejectUnknownParameters(
            MultiValueMap<String, String> requestParameters) {
        if (requestParameters != null
                && requestParameters.entrySet().stream()
                        .allMatch(entry ->
                                PARAMETERS.contains(entry.getKey())
                                        && entry.getValue() != null
                                        && entry.getValue().size() == 1)) {
            return;
        }
        throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求包含不支持的查询参数");
    }
}
