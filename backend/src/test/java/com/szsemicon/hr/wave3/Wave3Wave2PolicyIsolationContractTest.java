package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.VersionDetail;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.VersionSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Wave3Wave2PolicyIsolationContractTest {

    private static final Set<String> WAVE2_POLICY_VERSION_COLUMNS = Set.of(
            "versionId",
            "templateId",
            "versionNumber",
            "status",
            "parameters",
            "effectiveFrom",
            "effectiveTo",
            "changeReason",
            "validation",
            "snapshotJson",
            "snapshotDigest",
            "rollbackOfVersionId",
            "rowVersion",
            "createdBy",
            "createdAt",
            "publishedAt",
            "updatedBy",
            "updatedAt",
            "scopeBindings");

    @Test
    void wave3NeverAltersTheWave2PolicyVersionTable() {
        String wave2Foundation = readRepositoryFile(
                "backend/src/main/resources/db/migration/"
                        + "V4__versioned_policy_foundation.sql");
        String wave3Migration = readRepositoryFile(
                "backend/src/main/resources/db/migration/"
                        + "V7__attendance_setup_and_base_policies.sql");

        assertThat(wave2Foundation)
                .contains("UNIQUE KEY uq_policy_version_number (template_id, version_number)")
                .doesNotContain("legal_entity_id");
        assertThat(wave3Migration)
                .doesNotContain(
                        "ALTER TABLE policy_version",
                        "DROP INDEX uq_policy_version_number",
                        "fk_policy_version_legal_entity",
                        "MIN(legal_entity_id)");
    }

    @Test
    void wave2DomainAndRestViewsKeepTheirPreWave3Shape() {
        assertThat(recordComponents(PolicyVersion.class))
                .containsExactlyInAnyOrderElementsOf(WAVE2_POLICY_VERSION_COLUMNS);
        assertThat(recordComponents(VersionSummary.class)).doesNotContain("legalEntityId");
        assertThat(recordComponents(VersionDetail.class)).doesNotContain("legalEntityId");
    }

    @Test
    void h2SchemaMirrorsTheWave2V4PolicyVersionBoundary() {
        String schema = readRepositoryFile("backend/src/test/resources/db/test-schema.sql");
        String policyVersion = schema.substring(
                schema.indexOf("CREATE TABLE policy_version"),
                schema.indexOf("CREATE TABLE policy_scope_binding"));

        assertThat(policyVersion)
                .contains("UNIQUE (template_id, version_number)")
                .doesNotContain("legal_entity_id");
    }

    @Test
    void wave2TemplateDetailOpenApiHasOnlyItsRealPathParameter() {
        String openApi = readRepositoryFile("api/openapi.yaml");
        String operation = openApi.substring(
                openApi.indexOf("  /policy-templates/{templateId}:"),
                openApi.indexOf("  /policy-templates/{templateId}/versions:"));

        assertThat(operation)
                .contains(
                        "operationId: getPolicyTemplate",
                        "$ref: '#/components/parameters/TemplateId'")
                .doesNotContain(
                        "AttendanceLegalEntityId",
                        "legalEntityId");
    }

    @Test
    void wave2JavaAndMapperSurfaceContainsNoAttendanceSpecializationOrAddedLock() {
        assertThat(readRepositoryFile(
                "backend/src/main/java/com/szsemicon/hr/policy/application/"
                        + "PolicyRepository.java"))
                .doesNotContain("lockTemplate");
        assertThat(readRepositoryFile(
                "backend/src/main/java/com/szsemicon/hr/policy/infrastructure/"
                        + "persistence/PolicyMapper.java"))
                .doesNotContain("lockTemplate");
        assertThat(readRepositoryFile(
                "backend/src/main/java/com/szsemicon/hr/policy/infrastructure/"
                        + "persistence/MyBatisPolicyRepository.java"))
                .doesNotContain("lockTemplate");
        assertThat(readRepositoryFile(
                "backend/src/main/resources/mappers/PolicyMapper.xml"))
                .doesNotContain("lockTemplate", "FOR UPDATE");
        assertThat(readRepositoryFile(
                "backend/src/main/java/com/szsemicon/hr/policy/application/"
                        + "PolicyDraftService.java"))
                .doesNotContain("lockTemplate");
        assertThat(readRepositoryFile(
                "backend/src/main/java/com/szsemicon/hr/policy/application/"
                        + "PolicyLifecycleService.java"))
                .doesNotContain("lockTemplate");

        String validation = readRepositoryFile(
                "backend/src/main/java/com/szsemicon/hr/policy/application/"
                        + "PolicyValidationService.java");
        assertThat(validation)
                .contains("if (version.scopeBindings().isEmpty())")
                .doesNotContain(
                        "com.szsemicon.hr.attendance",
                        "ATTENDANCE_MEAL_DEDUCTION",
                        "ATTENDANCE_LATE_GRACE",
                        "ATTENDANCE_SINGLE_MISSING_PUNCH",
                        "validateAttendanceTemplate",
                        "validateAttendanceParameters",
                        "isAttendanceTemplate",
                        "maximumLateMinutes",
                        "groupChangeResets");
    }

    private static Set<String> recordComponents(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
    }

    private static String readRepositoryFile(String path) {
        try {
            Path fromBackendDirectory = Path.of("..", path);
            Path fromRepositoryRoot = Path.of(path);
            Path contract = Files.exists(fromBackendDirectory)
                    ? fromBackendDirectory
                    : fromRepositoryRoot;
            return Files.readString(contract, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(path + " must be readable", exception);
        }
    }
}
