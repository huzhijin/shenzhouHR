package com.szsemicon.hr.identityaccess.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.CredentialRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.FailureRecord;
import com.szsemicon.hr.shared.security.PasswordCodec;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AuthenticationReauthenticationContractTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");
    private static final String SECRET = "Current#Password123";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void failureAccountingCommitsOutsideCallingBusinessTransaction()
            throws Exception {
        Transactional transaction = AuthenticationService.class
                .getMethod(
                        "reauthenticateCurrentAccount",
                        String.class,
                        String.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(Arrays.asList(transaction.noRollbackFor()))
                .contains(ApiProblemException.class);
    }

    @Test
    void successfulReauthenticationClearsFailuresWithoutAuditingSecret() {
        Fixture fixture = new Fixture(true);

        fixture.service.reauthenticateCurrentAccount(
                SECRET, "ATTENDANCE_REPORT_EXPORT_DOWNLOAD");

        verify(fixture.repository)
                .clearLoginFailures("account-1");
        verify(fixture.audit).record(
                "principal-1",
                "ATTENDANCE_REPORT_EXPORT_DOWNLOAD_REAUTHENTICATED",
                "LOCAL_ACCOUNT",
                "account-1",
                "SUCCESS",
                null);
        verifyNoMoreInteractions(fixture.audit);
    }

    @Test
    void failedReauthenticationCountsFailureAndUsesGenericAuditReason() {
        Fixture fixture = new Fixture(false);

        assertThatThrownBy(() ->
                fixture.service.reauthenticateCurrentAccount(
                        SECRET, "ATTENDANCE_REPORT_EXPORT_DOWNLOAD"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("REAUTHENTICATION_FAILED");

        verify(fixture.repository).recordFailedLogin(
                "account-1", 1, NOW, NOW, null);
        verify(fixture.audit).record(
                "principal-1",
                "ATTENDANCE_REPORT_EXPORT_DOWNLOAD_REAUTHENTICATION_FAILED",
                "LOCAL_ACCOUNT",
                "account-1",
                "DENIED",
                "INVALID_CREDENTIALS");
        verifyNoMoreInteractions(fixture.audit);
    }

    private static final class Fixture {

        private final AuthenticationPersistence repository =
                mock(AuthenticationPersistence.class);
        private final AuditService audit = mock(AuditService.class);
        private final SecurityTokenService tokenService =
                new SecurityTokenService();
        private final PasswordCodec passwordCodec =
                new PasswordCodec();
        private final AuthenticationService service;

        private Fixture(boolean passwordMatches) {
            var account = new AccountRecord(
                    "account-1",
                    "principal-1",
                    "user",
                    "user",
                    "用户",
                    "ACTIVE",
                    false,
                    null,
                    null,
                    0,
                    0,
                    "legal-1");
            when(repository.findAccountByPrincipalId("principal-1"))
                    .thenReturn(Optional.of(account));
            when(repository.findCredential("account-1"))
                    .thenReturn(Optional.of(new CredentialRecord(
                            "account-1",
                            passwordCodec.encode(passwordMatches
                                    ? SECRET
                                    : "Different#Password123"),
                            0)));
            when(repository.findFailure("account-1"))
                    .thenReturn(FailureRecord.empty());
            service = new AuthenticationService(
                    repository,
                    audit,
                    tokenService,
                    passwordCodec,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    5,
                    Duration.ofMinutes(15),
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(30),
                    Duration.ofHours(8));
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "principal-1", "session", List.of()));
        }
    }
}
