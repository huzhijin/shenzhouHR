package com.szsemicon.hr.wave2;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Wave2OpenApiContractTest {

    private final String openapi = readOpenApi();

    @Test
    void locksWave2PeoplePathsWhileAllowingForwardCompatibleWaveExtensions() {
        List<String> requiredPaths = List.of(
                "/auth/login:",
                "/policy-templates:",
                "/people-imports/templates:",
                "/people-imports/templates/{templateType}/versions/{templateVersion}:",
                "/people-imports:",
                "/people-imports/{batchId}:",
                "/people-imports/{batchId}/file:",
                "/people-imports/{batchId}/mapping:",
                "/people-imports/{batchId}/precheck:",
                "/people-imports/{batchId}/diff:",
                "/people-imports/{batchId}/errors:",
                "/people-imports/{batchId}/error-report:",
                "/people-imports/{batchId}/publish:",
                "/people-imports/{batchId}/void:",
                "/people-imports/{batchId}/rollback:",
                "/organization-units:",
                "/organization-units/{organizationId}:",
                "/organization-units/{organizationId}/versions:",
                "/employees:",
                "/employees/{employeeId}:",
                "/employees/{employeeId}/versions:",
                "/employees/{employeeId}/employment-periods:",
                "/employees/{employeeId}/employment-periods/{employmentPeriodId}:",
                "/employees/{employeeId}/prior-service-records:",
                "/employees/{employeeId}/prior-service-adjustments:",
                "/employees/{employeeId}/prior-service/recalculate:");

        assertThat(openapi)
                .containsPattern("version: 1\\.9\\.0-wave[2-9][0-9]*")
                .contains("  - url: /api/v1");
        requiredPaths.forEach(path -> assertThat(openapi).contains("  " + path));
    }

    @Test
    void locksWriteSecurityConcurrencyAndFileContracts() {
        assertThat(openapi)
                .contains("name: Idempotency-Key")
                .contains("name: If-Match")
                .contains("multipart/form-data")
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .contains("x-data-scope:")
                .contains("PEOPLE_IMPORT:PUBLISH")
                .contains("ORGANIZATION:EDIT")
                .contains("EMPLOYEE:EDIT")
                .contains("EMPLOYMENT:CREATE")
                .contains("PRIOR_SERVICE:ADJUST");
    }

    @Test
    void locksPeopleStateAndConflictCodesAndExcludesSync() {
        assertThat(openapi)
                .contains("AWAITING_CONFIRMATION")
                .contains("PEOPLE_IMPORT_PRECHECK_REQUIRED")
                .contains("PEOPLE_IMPORT_BLOCKING_ERRORS")
                .contains("EMPLOYMENT_PERIOD_OVERLAP")
                .contains("PEOPLE_IMPORT_ROLLBACK_HAS_REFERENCES")
                .contains("PEOPLE_IMPORT_ROLLBACK_NOT_LATEST")
                .contains("PRIOR_SERVICE_BUSINESS_DATE_OUT_OF_ORDER")
                .contains("VERSION_CONFLICT")
                .contains("DATA_CONFLICT")
                .doesNotContain(
                        "  /organization-sync",
                        "  /organization-sync-jobs",
                        "  /organization-units/sync",
                        "operationId: syncOrganization");
    }

    @Test
    void locksAllRuntimePrecheckIssueCodesInTheOpenApiSchema() {
        String issueSchema = openapi.substring(
                openapi.indexOf("    PeopleImportIssueView:"),
                openapi.indexOf("    PeopleImportIssuePage:"));

        assertThat(issueSchema)
                .contains(
                        "REQUIRED_FIELD_MISSING",
                        "INVALID_VALUE",
                        "DUPLICATE_BUSINESS_KEY",
                        "EMPLOYEE_MATCH_KEY_REQUIRED",
                        "EMPLOYEE_MATCH_AMBIGUOUS",
                        "EMPLOYMENT_PERIOD_OVERLAP",
                        "ORGANIZATION_PARENT_MISSING",
                        "VERSION_EFFECTIVE_DATE_INVALID",
                        "ORGANIZATION_PARENT_CYCLE",
                        "ORGANIZATION_INACTIVE",
                        "PRIOR_SERVICE_BUSINESS_DATE_OUT_OF_ORDER",
                        "PRIOR_SERVICE_TOTAL_NEGATIVE");
    }

    @Test
    void retiresTheLegacyOrganizationSyncCapabilityInTheFirstWave2Migration() {
        String migration = readRepositoryFile(
                "backend/src/main/resources/db/migration/"
                        + "V5__people_initial_import_and_versioning.sql");
        String capabilityCodes = readRepositoryFile(
                "backend/src/main/java/com/szsemicon/hr/authorization/domain/CapabilityCodes.java");

        assertThat(migration)
                .contains("DELETE FROM auth_capability")
                .contains("MASTER_DATA:SYNC_PREVIEW");
        assertThat(capabilityCodes).doesNotContain("MASTER_DATA_SYNC_PREVIEW");
    }

    @Test
    void keepsMysqlReservedRowNumberQuotedAcrossMigrationAndRuntimeSql() {
        String migration = readRepositoryFile(
                "backend/src/main/resources/db/migration/"
                        + "V5__people_initial_import_and_versioning.sql");
        String mapper = readRepositoryFile(
                "backend/src/main/resources/mappers/PeopleMapper.xml");
        String flywayWrapper = readRepositoryFile("deploy/mysql/flyway-maven.sh");
        String mysqlVerification = readRepositoryFile("deploy/mysql/wave2-local-mysql.sh");

        assertThat(migration)
                .contains("`row_number` INT UNSIGNED NOT NULL")
                .doesNotContain("    row_number INT UNSIGNED NOT NULL");
        assertThat(mapper)
                .contains("`row_number`")
                .doesNotContain("diff_id, batch_id, row_number, entity_type");
        assertThat(flywayWrapper).contains("migrate | validate | info | repair");
        assertThat(mysqlVerification).contains("repair_failed_wave2_migration");
    }

    @Test
    void grantsSystemAdminTheWave1ReadCapabilityRequiredByWave2ListRoutes() {
        String forwardFix = readRepositoryFile(
                "backend/src/main/resources/db/migration/"
                        + "V6__system_admin_people_read_prerequisite.sql");

        assertThat(forwardFix)
                .contains("SYSTEM_ADMIN")
                .contains("MASTER_DATA:READ")
                .contains("NOT EXISTS")
                .doesNotContain("MASTER_DATA:SYNC_PREVIEW");
    }

    @Test
    void avoidsMysqlReservedWindowAliasesInTheEmployeeReadMapper() {
        String mapper = readRepositoryFile(
                "backend/src/main/resources/mappers/EmployeeReadMapper.xml");

        assertThat(mapper)
                .contains("assignment_row_number")
                .doesNotContain("AS row_number", "ranked.row_number");
    }

    @Test
    void documentsTheBackwardCompatibleEmployeeCompanyFilter() {
        String employeeList = openapi.substring(
                openapi.indexOf("  /employees:"),
                openapi.indexOf("    post:", openapi.indexOf("  /employees:")));

        assertThat(employeeList)
                .contains("operationId: listEmployees", "name: companyId")
                .contains("MASTER_DATA:READ");
    }

    private static String readOpenApi() {
        return readRepositoryFile("api/openapi.yaml");
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
