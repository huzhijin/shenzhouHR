package com.szsemicon.hr.identityaccess.interfaces.rest;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.identityaccess.application.AccountAccessService;
import com.szsemicon.hr.identityaccess.application.AccountAccessService.AccountDetail;
import com.szsemicon.hr.identityaccess.application.AccountAccessService.AccountPage;
import com.szsemicon.hr.identityaccess.application.AccountAccessService.CreateAccountCommand;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleRecord;
import com.szsemicon.hr.identityaccess.interfaces.rest.AuthenticationController.AcceptedOperation;
import com.szsemicon.hr.identityaccess.interfaces.rest.AuthenticationController.ReasonRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/access")
public class AccountController {

    private final AccountAccessService accountService;
    private final AuditService auditService;

    public AccountController(
            AccountAccessService accountService,
            AuditService auditService) {
        this.accountService = accountService;
        this.auditService = auditService;
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('ACCOUNT:READ')")
    AccountPage listAccounts(
            @RequestParam(defaultValue = "") @Size(max = 100) String query,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return accountService.listAccounts(query, status, page, size);
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAuthority('ACCOUNT:CREATE')")
    ResponseEntity<AccountDetail> createAccount(
            @Valid @RequestBody AccountCreateRequest request) {
        AccountDetail detail = accountService.createAccount(new CreateAccountCommand(
                request.username(),
                request.displayName(),
                request.temporaryPassword(),
                request.roleAssignments().stream().map(RoleAssignmentRequest::toInput).toList()));
        return ResponseEntity.created(URI.create("/api/v1/access/accounts/" + detail.accountId()))
                .body(detail);
    }

    @GetMapping("/accounts/{accountId}")
    @PreAuthorize("hasAuthority('ACCOUNT:READ')")
    AccountDetail getAccount(@PathVariable String accountId) {
        return accountService.getAccount(accountId);
    }

    @PatchMapping("/accounts/{accountId}/status")
    @PreAuthorize("hasAuthority('ACCOUNT:EDIT')")
    AccountDetail updateStatus(
            @PathVariable String accountId,
            @Valid @RequestBody AccountStatusUpdateRequest request) {
        return accountService.updateStatus(
                accountId,
                request.status(),
                request.reason(),
                request.expectedVersion());
    }

    @PostMapping("/accounts/{accountId}/lock")
    @PreAuthorize("hasAuthority('ACCOUNT:LOCK')")
    ResponseEntity<Void> lock(
            @PathVariable String accountId,
            @Valid @RequestBody ReasonRequest request) {
        accountService.lock(accountId, true, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accounts/{accountId}/unlock")
    @PreAuthorize("hasAuthority('ACCOUNT:UNLOCK')")
    ResponseEntity<Void> unlock(
            @PathVariable String accountId,
            @Valid @RequestBody ReasonRequest request) {
        accountService.lock(accountId, false, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accounts/{accountId}/password-reset-grants")
    @PreAuthorize("hasAuthority('ACCOUNT:RESET_PASSWORD')")
    ResponseEntity<AcceptedOperation> issuePasswordResetGrant(
            @PathVariable String accountId,
            @Valid @RequestBody ReasonRequest request) {
        accountService.issueResetGrant(accountId, request.reason());
        return ResponseEntity.accepted()
                .body(new AcceptedOperation(true, auditService.currentCorrelationId()));
    }

    @PutMapping("/accounts/{accountId}/role-assignments")
    @PreAuthorize("hasAuthority('ROLE:ASSIGN')")
    AccountDetail replaceRoleAssignments(
            @PathVariable String accountId,
            @Valid @RequestBody RoleAssignmentsUpdateRequest request) {
        return accountService.replaceRoleAssignments(
                accountId,
                request.assignments().stream().map(RoleAssignmentRequest::toInput).toList(),
                request.reason(),
                request.expectedVersion());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE:READ')")
    List<RoleRecord> listRoles() {
        return accountService.listRoles();
    }

    public record AccountCreateRequest(
            @NotBlank @Size(min = 3, max = 128) String username,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(min = 12, max = 256) String temporaryPassword,
            @NotEmpty List<@Valid RoleAssignmentRequest> roleAssignments) {
    }

    public record AccountStatusUpdateRequest(
            @NotBlank String status,
            @NotBlank @Size(min = 2, max = 500) String reason,
            @Min(0) long expectedVersion) {
    }

    public record RoleAssignmentsUpdateRequest(
            @NotEmpty List<@Valid RoleAssignmentRequest> assignments,
            @NotBlank @Size(min = 2, max = 500) String reason,
            @Min(0) long expectedVersion) {
    }

    public record RoleAssignmentRequest(
            @NotBlank String roleId,
            @NotBlank String scopeType,
            String scopeResourceId,
            @NotNull Instant validFrom,
            Instant validTo) {

        RoleAssignmentInput toInput() {
            return new RoleAssignmentInput(
                    roleId,
                    scopeType,
                    scopeResourceId,
                    validFrom,
                    validTo);
        }
    }
}
