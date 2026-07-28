package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class Wave1AuthenticationSecurityIntegrationTest extends Wave1IntegrationTestSupport {

    @Test
    void unknownUsernameAndWrongPasswordUseTheSameNonEnumeratingResponse() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"username":"%s","password":"%s"}
                                 """.formatted(ADMIN_USERNAME, newTestSecret())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        MvcResult unknownUsername = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"username":"wave1_missing_synthetic","password":"%s"}
                                 """.formatted(newTestSecret())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        assertThat(JsonPath.<String>read(wrongPassword.getResponse().getContentAsString(), "$.message"))
                .isEqualTo(JsonPath.read(
                        unknownUsername.getResponse().getContentAsString(),
                        "$.message"));
        assertThat(wrongPassword.getResponse().getContentAsString().toLowerCase())
                .doesNotContain(
                        ADMIN_USERNAME,
                        "\"password\":",
                        "\"passwordhash\":",
                        "\"sessiontoken\":",
                        "\"resettoken\":");
        assertThat(unknownUsername.getResponse().getContentAsString().toLowerCase())
                .doesNotContain(
                        "wave1_missing_synthetic",
                        "\"password\":",
                        "\"passwordhash\":",
                        "\"sessiontoken\":",
                        "\"resettoken\":");
    }

    @Test
    void loginLocksAfterConfiguredFailuresAndPersistsTheSecurityOutcome() throws Exception {
        long auditBefore = auditCountForResource(ADMIN_ACCOUNT);

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                     {"username":"%s","password":"%s"}
                                     """.formatted(ADMIN_USERNAME, newTestSecret())))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"username":"%s","password":"%s"}
                                 """.formatted(ADMIN_USERNAME, adminPassword)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        Integer failures = jdbc.queryForObject(
                "SELECT failure_count FROM login_failure_window WHERE account_id = ?",
                Integer.class,
                ADMIN_ACCOUNT);
        Timestamp lockedUntil = jdbc.queryForObject(
                "SELECT locked_until FROM login_failure_window WHERE account_id = ?",
                Timestamp.class,
                ADMIN_ACCOUNT);
        assertThat(failures).isGreaterThanOrEqualTo(5);
        assertThat(lockedUntil).isAfter(Timestamp.from(Instant.now()));
        assertThat(auditCountForResource(ADMIN_ACCOUNT)).isGreaterThan(auditBefore);
    }

    @Test
    void successfulLoginUsesOnlySecureCookieAndReturnsCsrfOutsideTheBody() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"username":"%s","password":"%s"}
                                 """.formatted(ADMIN_USERNAME, adminPassword)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("SHENZHOUHR_SESSION=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().exists("X-CSRF-TOKEN"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.firstPasswordChangeRequired").value(false))
                .andReturn();

        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain(
                        adminPassword.toLowerCase(),
                        "passwordhash",
                        "sessiontoken",
                        "resettoken",
                        "csrf");
        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                ADMIN_ACCOUNT);
        assertThat(storedHash).isNotEqualTo(adminPassword);
    }

    @Test
    void firstPasswordChangeClearsTheFlagAndInvalidatesTheBootstrapSession() throws Exception {
        AuthenticatedSession bootstrap = login(FIRST_CHANGE_USERNAME, firstChangePassword);
        String newPassword = newTestSecret();

        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), bootstrap))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstPasswordChangeRequired").value(true))
                .andExpect(jsonPath("$.capabilities").isEmpty())
                .andExpect(jsonPath("$.menu").isEmpty());
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/me/capabilities"), bootstrap))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(withSession(
                        post("/api/v1/auth/password/change")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"currentPassword":"%s","newPassword":"%s"}
                                         """.formatted(firstChangePassword, newPassword)),
                        bootstrap))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(withSession(
                        post("/api/v1/auth/password/first-change")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"currentPassword":"%s","newPassword":"%s"}
                                         """.formatted(firstChangePassword, newPassword)),
                        bootstrap))
                .andExpect(status().isNoContent());

        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), bootstrap))
                .andExpect(status().isUnauthorized());
        AuthenticatedSession replacement = login(FIRST_CHANGE_USERNAME, newPassword);
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstPasswordChangeRequired").value(false));

        Boolean required = jdbc.queryForObject(
                """
                SELECT first_password_change_required
                FROM local_account WHERE account_id = ?
                """,
                Boolean.class,
                FIRST_CHANGE_ACCOUNT);
        assertThat(required).isFalse();
        assertThat(auditCountForResource(FIRST_CHANGE_ACCOUNT)).isPositive();
    }

    @Test
    void passwordChangeRevokesOtherSessionsAndRejectsTheOldPassword() throws Exception {
        AuthenticatedSession first = login(STANDARD_USERNAME, standardPassword);
        AuthenticatedSession second = login(STANDARD_USERNAME, standardPassword);
        String newPassword = newTestSecret();

        mockMvc.perform(withSession(
                        post("/api/v1/auth/password/change")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"currentPassword":"%s","newPassword":"%s"}
                                         """.formatted(standardPassword, newPassword)),
                        first))
                .andExpect(status().isNoContent());

        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), second))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"username":"%s","password":"%s"}
                                 """.formatted(STANDARD_USERNAME, standardPassword)))
                .andExpect(status().isUnauthorized());
        login(STANDARD_USERNAME, newPassword);

        Long revoked = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM user_session
                WHERE account_id = ? AND status = 'REVOKED'
                """,
                Long.class,
                STANDARD_ACCOUNT);
        assertThat(revoked).isPositive();
    }

    @Test
    void passwordResetGrantIsSingleUseAndRevokesExistingSessions() throws Exception {
        AuthenticatedSession oldSession = login(STANDARD_USERNAME, standardPassword);
        String rawGrant = UUIDHolder.nextGrant();
        String newPassword = newTestSecret();
        jdbc.update(
                """
                INSERT INTO password_reset_grant (
                    grant_id, account_id, token_digest, expires_at, used_at,
                    issued_by, request_id, created_at, row_version
                ) VALUES (?, ?, ?, ?, NULL, ?, ?, CURRENT_TIMESTAMP, 0)
                """,
                "86000000-0000-0000-0000-000000000001",
                STANDARD_ACCOUNT,
                sha256(rawGrant),
                Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)),
                ADMIN_PRINCIPAL,
                "wave1-reset-request");

        String request = """
                         {"grant":"%s","newPassword":"%s"}
                         """.formatted(rawGrant, newPassword);
        mockMvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_GRANT"));

        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), oldSession))
                .andExpect(status().isUnauthorized());
        login(STANDARD_USERNAME, newPassword);

        Timestamp usedAt = jdbc.queryForObject(
                "SELECT used_at FROM password_reset_grant WHERE grant_id = ?",
                Timestamp.class,
                "86000000-0000-0000-0000-000000000001");
        assertThat(usedAt).isNotNull();
    }

    @Test
    void expiredAndExplicitlyRevokedSessionsCannotBeReused() throws Exception {
        AuthenticatedSession expired = login(STANDARD_USERNAME, standardPassword);
        jdbc.update(
                """
                UPDATE user_session
                SET idle_expires_at = ?, row_version = row_version + 1
                WHERE session_id = ?
                """,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)),
                expired.sessionId());
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), expired))
                .andExpect(status().isUnauthorized());

        AuthenticatedSession revoked = login(STANDARD_USERNAME, standardPassword);
        mockMvc.perform(withSession(
                        post("/api/v1/auth/sessions/{sessionId}/revoke", revoked.sessionId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"WAVE-1 合成会话撤销\"}"),
                        revoked))
                .andExpect(status().isNoContent());
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), revoked))
                .andExpect(status().isUnauthorized());

        Long revocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_revocation WHERE account_id = ?",
                Long.class,
                STANDARD_ACCOUNT);
        assertThat(revocations).isPositive();
    }

    @Test
    void stateChangingRequestsRequireCsrfAndDoNotRevokeOnFailure() throws Exception {
        AuthenticatedSession session = login(STANDARD_USERNAME, standardPassword);

        mockMvc.perform(withSessionWithoutCsrf(post("/api/v1/auth/logout"), session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), session))
                .andExpect(status().isOk());

        mockMvc.perform(withSession(post("/api/v1/auth/logout"), session))
                .andExpect(status().isNoContent());
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedProtectedMutationReachesAuthenticationEntryPointBeforeCsrf()
            throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void everyResponseCarriesTheRequiredBrowserSecurityHeaders() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        containsString("default-src 'self'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", not(containsString("*"))))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    private static final class UUIDHolder {

        private UUIDHolder() {
        }

        static String nextGrant() {
            return java.util.UUID.randomUUID().toString()
                    + java.util.UUID.randomUUID();
        }
    }
}
