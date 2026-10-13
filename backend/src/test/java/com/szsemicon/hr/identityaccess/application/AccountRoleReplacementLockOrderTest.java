package com.szsemicon.hr.identityaccess.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResolvedRoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentInput;
import com.szsemicon.hr.shared.security.PasswordCodec;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class AccountRoleReplacementLockOrderTest {

    private static final String ACTOR_ID =
            "a0000000-0000-0000-0000-000000000001";
    private static final String ACCOUNT_ID =
            "b0000000-0000-0000-0000-000000000001";
    private static final String TARGET_PRINCIPAL_ID =
            "p0000000-0000-0000-0000-000000000001";
    private static final String ROLE_ID =
            "r0000000-0000-0000-0000-000000000001";
    private static final String SCOPE_ID =
            "s0000000-0000-0000-0000-000000000001";
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
    private static final String TEST_PEPPER = "A".repeat(43);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void roleReplacementLocksOnlyLocalAccountBeforeTargetCompany() {
        AccountPersistence persistence = mock(AccountPersistence.class);
        AuthenticationPersistence authentication = mock(AuthenticationPersistence.class);
        AuditService audit = mock(AuditService.class);
        AccountAccessService service = new AccountAccessService(
                persistence,
                authentication,
                audit,
                new SecurityTokenService(),
                new PasswordCodec(),
                new ObjectMapper(),
                TEST_PEPPER,
                "v1",
                Duration.ofHours(1),
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTOR_ID, "n/a"));

        AccountRecord account = new AccountRecord(
                ACCOUNT_ID,
                TARGET_PRINCIPAL_ID,
                "E-0001",
                "e-0001",
                "员工甲",
                "ACTIVE",
                true,
                null,
                null,
                0,
                7,
                null);
        RoleAssignmentInput requested = new RoleAssignmentInput(
                ROLE_ID, "SELF", null, NOW, null);
        ResolvedRoleAssignmentInput resolved = new ResolvedRoleAssignmentInput(
                ROLE_ID, SCOPE_ID, NOW, null);

        when(persistence.lockProvisionedAccount(ACCOUNT_ID))
                .thenReturn(Optional.of(account));
        when(persistence.canAccessAllAccountRoleScopes(
                ACTOR_ID, ACCOUNT_ID, "ROLE:ASSIGN", NOW))
                .thenReturn(true);
        when(persistence.lockCurrentCapabilityAuthority(
                ACTOR_ID, "ROLE:ASSIGN", NOW))
                .thenReturn(true);
        when(persistence.findRoleCodeForUpdate(ROLE_ID))
                .thenReturn(Optional.of("EMPLOYEE_SELF"));
        when(persistence.resolveAuthorizedRoleAssignmentScopes(
                ACTOR_ID,
                TARGET_PRINCIPAL_ID,
                null,
                List.of(requested),
                NOW))
                .thenReturn(List.of(resolved));
        when(authentication.findAccountById(ACCOUNT_ID))
                .thenReturn(Optional.of(account));
        when(persistence.findVisibleRoleAssignments(
                ACTOR_ID, TARGET_PRINCIPAL_ID, "ROLE:ASSIGN", NOW))
                .thenReturn(List.of());
        when(persistence.findSessions(ACCOUNT_ID)).thenReturn(List.of());

        AccountAccessService.AccountDetail result = service.replaceRoleAssignments(
                ACCOUNT_ID, List.of(requested), "调整授权", 7);

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        verify(persistence, never()).lockAccountForScopeAuthorization(ACCOUNT_ID);

        InOrder order = inOrder(persistence);
        order.verify(persistence).lockProvisionedAccount(ACCOUNT_ID);
        order.verify(persistence).canAccessAllAccountRoleScopes(
                ACTOR_ID, ACCOUNT_ID, "ROLE:ASSIGN", NOW);
        order.verify(persistence).lockRoleGrantTargetCompanies(
                TARGET_PRINCIPAL_ID, null, List.of(requested), NOW);
        order.verify(persistence).lockCurrentCapabilityAuthority(
                ACTOR_ID, "ROLE:ASSIGN", NOW);
        order.verify(persistence).lockTargetRoleAssignments(
                TARGET_PRINCIPAL_ID, NOW);
        order.verify(persistence).canAccessAllAccountRoleScopes(
                ACTOR_ID, ACCOUNT_ID, "ROLE:ASSIGN", NOW);
        order.verify(persistence).findRoleCodeForUpdate(ROLE_ID);
        order.verify(persistence).resolveAuthorizedRoleAssignmentScopes(
                ACTOR_ID,
                TARGET_PRINCIPAL_ID,
                null,
                List.of(requested),
                NOW);
        order.verify(persistence).replaceRoleAssignments(
                ACCOUNT_ID,
                7,
                List.of(resolved),
                ACTOR_ID,
                "调整授权",
                NOW);
    }

    @Test
    void accountCreationRechecksAccountCreateBeforeAnyAccountRowIsInserted() {
        AccountPersistence persistence = mock(AccountPersistence.class);
        AuthenticationPersistence authentication = mock(AuthenticationPersistence.class);
        AccountAccessService service = new AccountAccessService(
                persistence,
                authentication,
                mock(AuditService.class),
                new SecurityTokenService(),
                new PasswordCodec(),
                new ObjectMapper(),
                TEST_PEPPER,
                "v1",
                Duration.ofHours(1),
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTOR_ID, "n/a"));
        RoleAssignmentInput requested = new RoleAssignmentInput(
                ROLE_ID,
                "COMPANY",
                "c0000000-0000-0000-0000-000000000001",
                NOW,
                null);
        when(persistence.lockCurrentCapabilityAuthority(
                ACTOR_ID, "ACCOUNT:CREATE", NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.createAccount(
                new AccountAccessService.CreateAccountCommand(
                        "new.account",
                        "新账号",
                        "Strong#Password123",
                        null,
                        List.of(requested))))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("ROLE_ASSIGNMENT_SCOPE_DENIED");

        InOrder order = inOrder(persistence);
        order.verify(persistence).lockRoleGrantTargetCompanies(
                null, null, List.of(requested), NOW);
        order.verify(persistence).lockCurrentCapabilityAuthority(
                ACTOR_ID, "ACCOUNT:CREATE", NOW);
        verify(persistence, never()).createAccount(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
