package com.szsemicon.hr.leavetimeaccount.interfaces.rest;

import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.LeaveAccountView;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/leave-accounts")
public class SelfLeaveAccountController {

    private final AnnualLeaveManagementService service;

    public SelfLeaveAccountController(AnnualLeaveManagementService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('LEAVE_SELF:READ')")
    ResponseEntity<SelfLeaveAccountsResponse> ownAccounts(
            @RequestParam(required = false) Integer year) {
        int resolvedYear = year == null ? LocalDate.now().getYear() : year;
        LeaveAccountView annual = service.getOwnAccount(resolvedYear, "ANNUAL_LEAVE");
        LeaveAccountView timeOff = service.getOwnAccount(resolvedYear, "TIME_OFF");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SelfLeaveAccountsResponse(
                        resolvedYear,
                        List.of(annual, timeOff)));
    }

    record SelfLeaveAccountsResponse(
            int year,
            List<LeaveAccountView> accounts) {
    }
}
