package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.PunchLocationService;
import com.szsemicon.hr.reporting.application.PunchLocationService.DayPunchPage;
import com.szsemicon.hr.reporting.application.PunchLocationService.LocationView;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PunchLocationController {

    private final PunchLocationService service;

    public PunchLocationController(PunchLocationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/attendance-report-queries/day-punches")
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT_QUERY:READ')")
    ResponseEntity<DayPunchPage> dayPunches(
            @RequestParam String companyId,
            @RequestParam String employeeId,
            @RequestParam LocalDate businessDate) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listDayPunches(companyId, employeeId, businessDate));
    }

    @GetMapping("/api/v1/me/attendance/day-punches")
    @PreAuthorize("hasAuthority('ATTENDANCE_SELF:READ')")
    ResponseEntity<DayPunchPage> selfDayPunches(@RequestParam LocalDate businessDate) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listSelfDayPunches(businessDate));
    }

    @GetMapping("/api/v1/checkins/{rawFactId}/location")
    // Capability denial is handled inside the service so every denied view is audited.
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<LocationView> location(
            @PathVariable String rawFactId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.viewLocation(rawFactId, reason));
    }
}
