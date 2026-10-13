package com.szsemicon.hr.leavetimeaccount.interfaces.rest;

import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.AdjustBalanceCommand;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.LeaveAccountView;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.LedgerEntryView;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.OpeningBalanceCommand;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees/{employeeId}")
public class AnnualLeaveManagementController {

    private static final String ANNUAL_LEAVE = "ANNUAL_LEAVE";
    private static final String TIME_OFF = "TIME_OFF";

    private final AnnualLeaveManagementService service;

    public AnnualLeaveManagementController(AnnualLeaveManagementService service) {
        this.service = service;
    }

    @GetMapping("/annual-leave")
    @PreAuthorize("hasAuthority('ANNUAL_LEAVE:READ')")
    ResponseEntity<LeaveAccountResponse> getAccount(
            @PathVariable String employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer year) {
        return read(employeeId, page, size, year, ANNUAL_LEAVE);
    }

    @GetMapping("/time-off")
    @PreAuthorize("hasAuthority('ANNUAL_LEAVE:READ')")
    ResponseEntity<LeaveAccountResponse> getTimeOffAccount(
            @PathVariable String employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer year) {
        return read(employeeId, page, size, year, TIME_OFF);
    }

    @PostMapping("/annual-leave/opening")
    @PreAuthorize("hasAuthority('ANNUAL_LEAVE:ADJUST')")
    ResponseEntity<LeaveAccountResponse> setOpeningBalance(
            @PathVariable String employeeId,
            @Valid @RequestBody OpeningBalanceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return open(employeeId, request, idempotencyKey, ANNUAL_LEAVE);
    }

    @PostMapping("/time-off/opening")
    @PreAuthorize("hasAuthority('ANNUAL_LEAVE:ADJUST')")
    ResponseEntity<LeaveAccountResponse> setTimeOffOpeningBalance(
            @PathVariable String employeeId,
            @Valid @RequestBody OpeningBalanceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return open(employeeId, request, idempotencyKey, TIME_OFF);
    }

    @PostMapping("/annual-leave/adjust")
    @PreAuthorize("hasAuthority('ANNUAL_LEAVE:ADJUST')")
    ResponseEntity<LeaveAccountResponse> adjustBalance(
            @PathVariable String employeeId,
            @Valid @RequestBody AdjustBalanceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return adjust(employeeId, request, idempotencyKey, ANNUAL_LEAVE);
    }

    @PostMapping("/time-off/adjust")
    @PreAuthorize("hasAuthority('ANNUAL_LEAVE:ADJUST')")
    ResponseEntity<LeaveAccountResponse> adjustTimeOffBalance(
            @PathVariable String employeeId,
            @Valid @RequestBody AdjustBalanceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return adjust(employeeId, request, idempotencyKey, TIME_OFF);
    }

    private ResponseEntity<LeaveAccountResponse> read(
            String employeeId, int page, int size, Integer year, String accountType) {
        int resolvedYear = year != null ? year
                : java.time.Year.now().getValue();
        LeaveAccountView view = service.getAccount(
                employeeId, resolvedYear, page, Math.min(size, 100), accountType);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(LeaveAccountResponse.from(view));
    }

    private ResponseEntity<LeaveAccountResponse> open(
            String employeeId,
            OpeningBalanceRequest request,
            String idempotencyKey,
            String accountType) {
        int year = request.year() != null ? request.year()
                : java.time.Year.now().getValue();
        var command = new OpeningBalanceCommand(
                employeeId, request.balanceHours(), year,
                request.openingDate(), request.reason(), idempotencyKey);
        LeaveAccountView view = service.setOpeningBalance(command, accountType);
        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(LeaveAccountResponse.from(view));
    }

    private ResponseEntity<LeaveAccountResponse> adjust(
            String employeeId,
            AdjustBalanceRequest request,
            String idempotencyKey,
            String accountType) {
        int year = request.year() != null ? request.year()
                : java.time.Year.now().getValue();
        var command = new AdjustBalanceCommand(
                employeeId, request.adjustmentHours(), year,
                request.reason(), idempotencyKey);
        LeaveAccountView view = service.adjustBalance(command, accountType);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(LeaveAccountResponse.from(view));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record OpeningBalanceRequest(
            @NotNull @DecimalMin("-9999.00") @DecimalMax("9999.00") BigDecimal balanceHours,
            Integer year,
            /** Optional; defaults to 1 August of the account year. */
            LocalDate openingDate,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record AdjustBalanceRequest(
            @NotNull @DecimalMin("-9999.00") @DecimalMax("9999.00") BigDecimal adjustmentHours,
            Integer year,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record LeaveAccountResponse(
            String accountId,
            String employeeId,
            int year,
            double balanceHours,
            double equivalentDays,
            long rowVersion,
            List<LedgerEntryResponse> entries,
            long totalEntries) {

        static LeaveAccountResponse from(LeaveAccountView view) {
            return new LeaveAccountResponse(
                    view.accountId(),
                    view.employeeId(),
                    view.year(),
                    view.balanceHours().doubleValue(),
                    view.equivalentDays().doubleValue(),
                    view.rowVersion(),
                    view.entries().stream().map(LedgerEntryResponse::from).toList(),
                    view.totalEntries());
        }
    }

    record LedgerEntryResponse(
            String entryId,
            String entryType,
            String entryTypeLabel,
            double amountHours,
            String sourceType,
            LocalDate businessDate,
            LocalDate effectiveFrom,
            LocalDate expiresOn,
            Instant occurredAt) {

        static LedgerEntryResponse from(LedgerEntryView view) {
            return new LedgerEntryResponse(
                    view.entryId(),
                    view.entryType(),
                    view.entryTypeLabel(),
                    view.amountHours().doubleValue(),
                    view.sourceType(),
                    view.businessDate(),
                    view.effectiveFrom(),
                    view.expiresOn(),
                    view.occurredAt());
        }
    }
}
