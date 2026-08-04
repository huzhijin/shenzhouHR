package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardService;
import com.szsemicon.hr.shared.web.ApiProblemException;
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
@RequestMapping("/api/v1/me/attendance-dashboard")
public class SelfAttendanceDashboardController {

    private final SelfAttendanceDashboardService dashboardService;

    public SelfAttendanceDashboardController(
            SelfAttendanceDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ATTENDANCE_SELF:READ')")
    ResponseEntity<SelfAttendanceDashboardResponse> dashboard(
            @RequestParam MultiValueMap<String, String>
                    requestParameters) {
        if (requestParameters != null
                && !requestParameters.isEmpty()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "本人考勤工作台不支持查询参数");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SelfAttendanceDashboardResponse.from(
                        dashboardService.query()));
    }
}
