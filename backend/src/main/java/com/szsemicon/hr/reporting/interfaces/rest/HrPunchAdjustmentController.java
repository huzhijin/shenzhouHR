package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.HrPunchAdjustmentService;
import com.szsemicon.hr.reporting.application.HrPunchAdjustmentService.SaveCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance/hr-adjustments")
public class HrPunchAdjustmentController {

    private final HrPunchAdjustmentService service;

    public HrPunchAdjustmentController(HrPunchAdjustmentService service) {
        this.service = service;
    }

    @PostMapping("/punches")
    @PreAuthorize("hasAuthority('"
            + CapabilityCodes.ATTENDANCE_ADJUST_MANAGE + "')")
    ResponseEntity<HrPunchAdjustmentService.SavedAdjustment> savePunch(
            @Valid @RequestBody PunchRequest request) {
        var saved = service.save(toCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(saved);
    }

    @PostMapping("/punches/batch")
    @PreAuthorize("hasAuthority('"
            + CapabilityCodes.ATTENDANCE_ADJUST_MANAGE + "')")
    ResponseEntity<BatchSaveResponse> savePunches(
            @Valid @RequestBody BatchPunchRequest request) {
        var saved = service.saveAll(
                request.items().stream().map(this::toCommand).toList());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new BatchSaveResponse(saved.size(), saved));
    }

    private SaveCommand toCommand(PunchRequest request) {
        boolean recalculate = request.recalculate() == null
                || request.recalculate();
        return new SaveCommand(
                request.companyId(),
                request.employeeId(),
                request.businessDate(),
                request.onDutyAt(),
                request.offDutyAt(),
                request.reason(),
                request.overtimeHours(),
                request.clearedExceptionTypes(),
                request.dayTypes(),
                recalculate);
    }

    public record PunchRequest(
            @Size(max = 36) String companyId,
            @NotBlank @Size(max = 36) String employeeId,
            @NotNull LocalDate businessDate,
            Instant onDutyAt,
            Instant offDutyAt,
            @NotBlank @Size(max = 500) String reason,
            @DecimalMin("0") @DecimalMax("24") BigDecimal overtimeHours,
            List<@Size(max = 32) String> clearedExceptionTypes,
            List<@Size(max = 32) String> dayTypes,
            Boolean recalculate) {
    }

    public record BatchPunchRequest(
            @NotEmpty @Size(max = 300) List<@Valid PunchRequest> items) {
    }

    public record BatchSaveResponse(
            int savedCount,
            List<HrPunchAdjustmentService.SavedAdjustment> items) {
    }
}
