package com.szsemicon.hr.employee.interfaces.rest;

import com.szsemicon.hr.employee.application.PunchExemptionApplicationService;
import com.szsemicon.hr.employee.application.PunchExemptionApplicationService.PunchExemptionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees/{employeeId}/punch-exemption")
public class PunchExemptionController {

    private final PunchExemptionApplicationService service;

    public PunchExemptionController(PunchExemptionApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EMPLOYEE:READ')")
    ResponseEntity<PunchExemptionResponse> get(@PathVariable String employeeId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(service.get(employeeId)));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('EMPLOYEE:EDIT')")
    ResponseEntity<PunchExemptionResponse> put(
            @PathVariable String employeeId,
            @Valid @RequestBody PunchExemptionUpdateRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(service.setStanding(employeeId, request.standingExempt())));
    }

    private static PunchExemptionResponse toResponse(PunchExemptionStatus status) {
        return new PunchExemptionResponse(
                status.standingExempt(),
                status.executiveExempt());
    }

    public record PunchExemptionResponse(
            boolean standingExempt,
            boolean executiveExempt) {
    }

    public record PunchExemptionUpdateRequest(@NotNull Boolean standingExempt) {
    }
}
