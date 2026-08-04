package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Wave1OpenApiContractTest {

    private final String openapi = readOpenApi();

    @Test
    void declaresEveryWave1AuthenticationAccessAuditAndPolicyPath() {
        List<String> requiredPaths = List.of(
                "/auth/login:",
                "/auth/session:",
                "/auth/logout:",
                "/auth/password/change:",
                "/auth/password/first-change:",
                "/auth/password-reset-requests:",
                "/auth/password-resets:",
                "/auth/sessions/{sessionId}/revoke:",
                "/me/capabilities:",
                "/access/accounts:",
                "/access/accounts/{accountId}:",
                "/access/accounts/{accountId}/status:",
                "/access/accounts/{accountId}/lock:",
                "/access/accounts/{accountId}/unlock:",
                "/access/accounts/{accountId}/password-reset-grants:",
                "/access/accounts/{accountId}/temporary-password-reset:",
                "/access/accounts/{accountId}/role-assignments:",
                "/access/roles:",
                "/access/audit-events:",
                "/access/audit-events/{auditEventId}:",
                "/policy-templates:",
                "/policy-templates/{templateId}:",
                "/policy-templates/{templateId}/versions:",
                "/policy-templates/{templateId}/versions/{versionId}:",
                "/policy-templates/{templateId}/versions/{versionId}/scope-bindings:",
                "/policy-templates/{templateId}/versions/{versionId}/validate:",
                "/policy-templates/{templateId}/versions/{versionId}/conflicts:",
                "/policy-templates/{templateId}/versions/{versionId}/simulate:",
                "/policy-templates/{templateId}/versions/{versionId}/impact-preview:",
                "/policy-templates/{templateId}/versions/{versionId}/publish:",
                "/policy-templates/{templateId}/versions/{versionId}/deactivate:",
                "/policy-templates/{templateId}/versions/{versionId}/rollback:");

        assertThat(openapi).contains("url: /api/v1");
        requiredPaths.forEach(path -> assertThat(openapi).contains("  " + path));
    }

    @Test
    void keepsCredentialsAndOpaqueTokensWriteOnlyAndOutOfResponseSchemas() {
        assertThat(openapi)
                .contains("name: SHENZHOUHR_SESSION")
                .contains("password: { type: string, minLength: 1, maxLength: 256, writeOnly: true }")
                .contains("grant: { type: string, minLength: 32, maxLength: 512, writeOnly: true }")
                .doesNotContain(
                        "passwordHash:",
                        "credentialHash:",
                        "sessionToken:",
                        "resetToken:",
                        "databasePassword:");
    }

    @Test
    void keepsPayrollOutsideTheWave1Contract() {
        assertThat(openapi.toLowerCase()).doesNotContain("  /payroll");
    }

    private static String readOpenApi() {
        try {
            Path fromBackendDirectory = Path.of("..", "api", "openapi.yaml");
            Path fromRepositoryRoot = Path.of("api", "openapi.yaml");
            Path contract = Files.exists(fromBackendDirectory)
                    ? fromBackendDirectory
                    : fromRepositoryRoot;
            return Files.readString(
                    contract,
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("api/openapi.yaml must be readable from backend", exception);
        }
    }
}
