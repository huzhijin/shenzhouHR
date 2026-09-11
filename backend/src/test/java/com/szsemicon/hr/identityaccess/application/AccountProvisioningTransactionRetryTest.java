package com.szsemicon.hr.identityaccess.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.identityaccess.application.AccountAccessService.BulkAccountCreationResult;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.EmployeeAccountCandidateRecord;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.EmployeeProvisioningTarget;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.IdempotencyClaim;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.RecoverableRoleAssignment;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.CredentialRecord;
import com.szsemicon.hr.shared.security.PasswordCodec;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class AccountProvisioningTransactionRetryTest {

    private static final String ACTOR_ID = "a0000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE_ID = "e0000000-0000-0000-0000-000000000001";
    private static final String COMPANY_ID = "c0000000-0000-0000-0000-000000000001";
    private static final String ACCOUNT_ID = "b0000000-0000-0000-0000-000000000001";
    private static final String PRINCIPAL_ID = "p0000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE_NUMBER = "E-0001";
    private static final String IDEMPOTENCY_KEY =
            "account-provisioning-race-retry";
    private static final String RECOVERY_KEY = "A".repeat(43);
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void losingFirstClaimRollsBackThenReplaysWithAccountFirstOrdering() {
        AccountPersistence persistence = mock(AccountPersistence.class);
        AuthenticationPersistence authentication = mock(AuthenticationPersistence.class);
        AuditService audit = mock(AuditService.class);
        PasswordCodec passwords = new PasswordCodec();
        SecurityTokenService tokens = new SecurityTokenService();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:account-provisioning-retry");
        AccountAccessService service = new AccountAccessService(
                persistence,
                authentication,
                audit,
                tokens,
                passwords,
                new ObjectMapper(),
                RECOVERY_KEY,
                "v1",
                java.time.Duration.ofHours(1),
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(NOW, ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTOR_ID, "n/a"));

        EmployeeProvisioningTarget target = new EmployeeProvisioningTarget(
                EMPLOYEE_ID, COMPANY_ID);
        when(persistence.findIdempotency(
                ACTOR_ID, "EMPLOYEE_ACCOUNTS_BULK_CREATE", IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(persistence.findEmployeeProvisioningTargets(List.of(EMPLOYEE_ID)))
                .thenReturn(List.of(target));
        when(persistence.lockCurrentCapabilityAuthority(
                ACTOR_ID, "ACCOUNT:CREATE", NOW)).thenReturn(true);
        when(persistence.lockCurrentCapabilityAuthority(
                ACTOR_ID, "ROLE:ASSIGN", NOW)).thenReturn(true);

        AtomicReference<IdempotencyClaim> storedClaim = new AtomicReference<>();
        when(persistence.claimIdempotency(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any(Instant.class)))
                .thenAnswer(invocation -> {
                    String requestDigest = invocation.getArgument(4);
                    IdempotencyClaim claim = new IdempotencyClaim(
                            "idempotency-record",
                            requestDigest,
                            receiptJson(),
                            NOW.minusSeconds(30),
                            false);
                    storedClaim.set(claim);
                    return claim;
                });
        when(persistence.lockIdempotency(
                ACTOR_ID, "EMPLOYEE_ACCOUNTS_BULK_CREATE", IDEMPOTENCY_KEY))
                .thenAnswer(invocation -> Optional.of(storedClaim.get()));
        when(persistence.lockProvisionedAccount(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountRecord(
                        ACCOUNT_ID,
                        PRINCIPAL_ID,
                        EMPLOYEE_NUMBER,
                        EMPLOYEE_NUMBER.toLowerCase(),
                        "员工甲",
                        "ACTIVE",
                        true,
                        null,
                        null,
                        0,
                        0,
                        null)));
        when(persistence.findEmployeeAccountCandidates(
                ACTOR_ID, List.of(EMPLOYEE_ID), NOW)).thenReturn(List.of(
                        new EmployeeAccountCandidateRecord(
                                EMPLOYEE_ID,
                                COMPANY_ID,
                                EMPLOYEE_NUMBER,
                                "员工甲",
                                "制造一部",
                                "ALREADY_PROVISIONED",
                                ACCOUNT_ID)));
        when(persistence.lockRecoverableRoleAssignments(PRINCIPAL_ID, NOW))
                .thenReturn(List.of(new RecoverableRoleAssignment(
                        "assignment",
                        "EMPLOYEE_SELF",
                        "SELF",
                        null,
                        null,
                        NOW.minusSeconds(60),
                        null,
                        NOW.minusSeconds(60),
                        null)));
        when(authentication.lockCredential(ACCOUNT_ID))
                .thenReturn(Optional.of(new CredentialRecord(
                        ACCOUNT_ID,
                        passwords.encode(expectedPassword(tokens)),
                        0)));

        BulkAccountCreationResult result = service.createEmployeeAccounts(
                List.of(EMPLOYEE_ID), IDEMPOTENCY_KEY, RECOVERY_KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.created()).isOne();
        assertThat(result.credentials()).singleElement()
                .extracting(AccountAccessService.TemporaryCredential::accountId)
                .isEqualTo(ACCOUNT_ID);

        InOrder order = inOrder(persistence);
        order.verify(persistence).findIdempotency(
                ACTOR_ID, "EMPLOYEE_ACCOUNTS_BULK_CREATE", IDEMPOTENCY_KEY);
        order.verify(persistence).findEmployeeProvisioningTargets(List.of(EMPLOYEE_ID));
        order.verify(persistence).lockEmployeeProvisioningTargets(List.of(target));
        order.verify(persistence).findEmployeeProvisioningTargets(List.of(EMPLOYEE_ID));
        order.verify(persistence).lockCurrentCapabilityAuthority(
                ACTOR_ID, "ACCOUNT:CREATE", NOW);
        order.verify(persistence).lockCurrentCapabilityAuthority(
                ACTOR_ID, "ROLE:ASSIGN", NOW);
        order.verify(persistence).findIdempotency(
                ACTOR_ID, "EMPLOYEE_ACCOUNTS_BULK_CREATE", IDEMPOTENCY_KEY);
        order.verify(persistence).claimIdempotency(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any(Instant.class));
        order.verify(persistence).lockIdempotency(
                ACTOR_ID, "EMPLOYEE_ACCOUNTS_BULK_CREATE", IDEMPOTENCY_KEY);
        order.verify(persistence).lockProvisionedAccount(ACCOUNT_ID);
        order.verify(persistence).findEmployeeProvisioningTargets(List.of(EMPLOYEE_ID));
        order.verify(persistence).lockEmployeeProvisioningTargets(List.of(target));
    }

    private static String receiptJson() {
        return """
                {"derivationVersion":"HMAC_SHA256_V1","keyId":"v1",\
                "bindings":[{"employeeId":"%s","accountId":"%s"}]}
                """.formatted(EMPLOYEE_ID, ACCOUNT_ID);
    }

    private static String expectedPassword(SecurityTokenService tokens) {
        String employeeSetDigest = tokens.digest(
                "EMPLOYEE_ACCOUNT_SET_V1\n1\n"
                        + EMPLOYEE_ID.length()
                        + ':'
                        + EMPLOYEE_ID
                        + '\n');
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    Base64.getUrlDecoder().decode(RECOVERY_KEY),
                    "HmacSHA256"));
            updateLengthPrefixed(
                    mac,
                    "HMAC_SHA256_V1",
                    "v1",
                    ACTOR_ID,
                    "EMPLOYEE_ACCOUNTS_BULK_CREATE",
                    IDEMPOTENCY_KEY,
                    "idempotency-record",
                    employeeSetDigest,
                    EMPLOYEE_ID,
                    RECOVERY_KEY);
            String material = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal());
            return "Hr1!" + material.substring(0, 24);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void updateLengthPrefixed(Mac mac, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            mac.update((byte) (bytes.length >>> 24));
            mac.update((byte) (bytes.length >>> 16));
            mac.update((byte) (bytes.length >>> 8));
            mac.update((byte) bytes.length);
            mac.update(bytes);
        }
    }
}
