package com.szsemicon.hr.companydimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CompanyDimensionMigrationContractTest {

    private static final Path MIGRATIONS =
            Path.of("src/main/resources/db/migration");
    private static final Path LATEST_TEST_SCHEMA =
            Path.of("src/test/resources/db/test-schema.sql");
    private static final Path WAVE3_MYSQL_HARNESS =
            Path.of("../deploy/mysql/wave3-local-mysql.sh");

    private static final List<String> COMPANY_BOUNDARY_TABLES = List.of(
            "company",
            "employee",
            "organization_identity",
            "auth_data_scope",
            "people_import_batch",
            "people_import_publication",
            "location",
            "shift_template",
            "work_calendar",
            "attendance_group",
            "attendance_policy_scope",
            "attendance_source",
            "source_device",
            "device_person_binding",
            "attendance_evidence_subject_lock",
            "raw_attendance_fact",
            "effective_attendance_event",
            "duplicate_review_group",
            "evidence_interval_slice",
            "attendance_recalculation_intent",
            "punch_mapping_profile",
            "punch_import_batch",
            "punch_import_file",
            "attendance_report_projection",
            "attendance_report_daily_fact",
            "attendance_report_oa_fact",
            "attendance_report_exception_fact",
            "attendance_report_time_account_fact",
            "attendance_report_export_job");

    @Test
    void v11RenamesEveryCompanyBoundaryWithoutReplacingStableIds() {
        String migration = migration("V11__unify_company_dimension.sql");

        assertThat(migration)
                .contains("RENAME TABLE legal_entity TO company;")
                .doesNotContain(
                        "CREATE TABLE company",
                        "DROP TABLE",
                        "DELETE FROM");

        Matcher matcher = Pattern.compile(
                        "(?is)ALTER\\s+TABLE\\s+([a-z0-9_]+)\\s+"
                                + "RENAME\\s+COLUMN\\s+legal_entity_id\\s+"
                                + "TO\\s+company_id\\s*,\\s*"
                                + "ALGORITHM\\s*=\\s*INPLACE\\s*;")
                .matcher(migration);
        var renamedTables = new java.util.ArrayList<String>();
        while (matcher.find()) {
            renamedTables.add(matcher.group(1));
        }
        assertThat(renamedTables)
                .hasSize(29)
                .containsExactlyElementsOf(COMPANY_BOUNDARY_TABLES);
        assertThat(count(migration, "ALGORITHM = INPLACE")).isEqualTo(29);
        assertThat(migration).doesNotContain("ALGORITHM = COPY");
        assertThat(count(migration, " NOT ENFORCED")).isEqualTo(44);
        assertThat(count(migration, " ENFORCED")).isEqualTo(88);

        int saveForeignKeys = migration.indexOf(
                "@@SESSION.FOREIGN_KEY_CHECKS");
        int disableForeignKeys = migration.indexOf(
                "SET SESSION FOREIGN_KEY_CHECKS = 0");
        int dropTargetCheck = migration.indexOf(
                "DROP CHECK ck_auth_scope_target");
        int disableChecks = migration.indexOf(" NOT ENFORCED");
        int renameRoot = migration.indexOf(
                "RENAME TABLE legal_entity TO company");
        int firstRename = migration.indexOf(
                "RENAME COLUMN legal_entity_id TO company_id");
        int lastRename = migration.lastIndexOf("ALGORITHM = INPLACE;");
        int restoreForeignKeys = migration.indexOf(
                "SET SESSION FOREIGN_KEY_CHECKS =",
                disableForeignKeys + 1);
        int restoreChecks = migration.indexOf(
                "ALTER CHECK ck_auth_scope_period ENFORCED");
        int renameIndexes = migration.indexOf(
                "RENAME INDEX uq_legal_entity_code TO uq_company_code");
        int restoreTargetCheck = migration.indexOf(
                "ADD CONSTRAINT ck_auth_scope_target");
        assertThat(dropTargetCheck).isNotNegative();
        assertThat(disableChecks).isGreaterThan(dropTargetCheck);
        assertThat(saveForeignKeys).isNotNegative();
        assertThat(saveForeignKeys).isGreaterThan(disableChecks);
        assertThat(disableForeignKeys).isGreaterThan(saveForeignKeys);
        assertThat(renameRoot).isGreaterThan(disableForeignKeys);
        assertThat(firstRename).isGreaterThan(renameRoot);
        assertThat(restoreForeignKeys).isGreaterThan(lastRename);
        assertThat(restoreChecks).isGreaterThan(restoreForeignKeys);
        assertThat(renameIndexes).isGreaterThan(restoreChecks);
        assertThat(restoreTargetCheck).isGreaterThan(renameIndexes);
    }

    @Test
    void v11MigratesTheOnlySupportedAuthorizationScopeSetFailClosed() {
        String migration = migration("V11__unify_company_dimension.sql");

        assertThat(migration).contains(
                "DROP CHECK ck_auth_scope_target",
                "SET scope_type = 'COMPANY'",
                "WHERE scope_type = 'LEGAL_ENTITY'",
                "scope_type = 'COMPANY' AND company_id IS NOT NULL "
                        + "AND organization_id IS NULL",
                "scope_type = 'ORGANIZATION' AND company_id IS NULL "
                        + "AND organization_id IS NOT NULL",
                "scope_type = 'SELF' AND company_id IS NULL "
                        + "AND organization_id IS NULL");
        assertThat(count(migration, "ADD CONSTRAINT ck_auth_scope_target"))
                .isOne();
    }

    @Test
    void v11RenamesEveryBoundaryIndexThatExposedLegacyTerminology() {
        assertThat(migration("V11__unify_company_dimension.sql")).contains(
                "RENAME INDEX uq_legal_entity_code TO uq_company_code",
                "RENAME INDEX ix_employee_legal_entity_status"
                        + System.lineSeparator()
                        + "    TO ix_employee_company_status",
                "RENAME INDEX ix_organization_identity_legal_entity"
                        + System.lineSeparator()
                        + "    TO ix_organization_identity_company",
                "RENAME INDEX ix_attendance_policy_scope_legal_entity"
                        + System.lineSeparator()
                        + "    TO ix_attendance_policy_scope_company",
                "RENAME INDEX uq_att_source_id_entity"
                        + System.lineSeparator()
                        + "    TO uq_att_source_id_company");
    }

    @Test
    void latestH2SchemaExposesOnlyTheCompanyScopeShape() {
        String schema = read(LATEST_TEST_SCHEMA);

        assertThat(schema).contains(
                "CREATE TABLE company (",
                "company_id VARCHAR(36) PRIMARY KEY",
                "scope_type = 'COMPANY' AND company_id IS NOT NULL",
                "scope_type = 'ORGANIZATION' AND company_id IS NULL",
                "scope_type = 'SELF' AND company_id IS NULL");
        assertThat(schema)
                .doesNotContain(
                        "CREATE TABLE legal_entity",
                        "legal_entity_id",
                        "'LEGAL_ENTITY'");
    }

    @Test
    void populatedV10UpgradeFailsClosedBeforeTheFirstV11Ddl() {
        String harness = read(WAVE3_MYSQL_HARNESS);
        int upgrade = harness.indexOf(
                "upgrade_v6_target7_latest_test() {");
        int target10 = harness.indexOf(
                "run_flyway \"$TEST_DATABASE\" \"-target=10\" migrate",
                upgrade);
        int populatedFixture = harness.indexOf(
                "bootstrap_populated_company_fixture_at_v10",
                target10);
        int preflight = harness.indexOf(
                "company_dimension_preflight_v10 "
                        + "\"$defaults_file\" \"$TEST_DATABASE\"",
                populatedFixture);
        int target11 = harness.indexOf(
                "run_flyway \"$TEST_DATABASE\" \"-target=11\" migrate",
                preflight);
        int validate = harness.indexOf(
                "run_flyway \"$TEST_DATABASE\" validate",
                target11);

        assertThat(upgrade).isNotNegative();
        assertThat(target10).isGreaterThan(upgrade);
        assertThat(populatedFixture).isGreaterThan(target10);
        assertThat(preflight).isGreaterThan(populatedFixture);
        assertThat(target11).isGreaterThan(preflight);
        assertThat(validate).isGreaterThan(target11);
        assertThat(harness).contains(
                "exact_v10_required",
                "partial_v11_history",
                "non_exact_v10_shape",
                "scope_check_contract",
                "auxiliary_check_contract",
                "boundary_index_contract",
                "invalid_scope_shape",
                "orphan_company_reference",
                "auth_scope_check_contract_matches",
                "company_boundary_index_contract_matches",
                "company_boundary_relationship_contract_matches",
                "company_boundary_auxiliary_check_contract_matches",
                "information_schema.CHECK_CONSTRAINTS",
                "tc.ENFORCED = 'YES'",
                "28|29|29|29",
                "REFERENCED_TABLE_NAME = 'attendance_source'",
                "REFERENCED_TABLE_NAME = 'attendance_report_projection'",
                "WHERE state = 'STARTED'",
                "WHERE status = 'STARTED'",
                "verify_company_preflight_negative_probes",
                "cleanup_company_preflight_negative_probes",
                "company_index_probe_shape",
                "uq_legal_entity_code_probe",
                "missing_scope_check=REJECTED",
                "unknown_scope=REJECTED",
                "mutated_literal=REJECTED",
                "missing_term=REJECTED",
                "extra_term=REJECTED",
                "non_enforced_auxiliary_check=REJECTED",
                "missing_auxiliary_check=REJECTED",
                "extra_auxiliary_check=REJECTED",
                "missing_source_index=REJECTED",
                "occupied_target_index=REJECTED",
                "orphan=REJECTED",
                "setup_started=REJECTED",
                "ingestion_started=REJECTED",
                "restored=true");
    }

    @Test
    void v10AndV11HarnessCompareAllBoundaryRelationshipsAndRepeatNoop() {
        String harness = read(WAVE3_MYSQL_HARNESS);

        assertThat(harness).contains(
                "W3_COMPANY_V10_POPULATED_FIXTURE=PASS",
                "companies=2",
                "nonempty_boundary_tables=11",
                "capture_company_boundary_snapshot",
                "Company-boundary snapshot must contain exactly 29 tables",
                "COALESCE(\\`${boundary_column}\\`, '<NULL>')",
                "V11 changed a company-bound row count or stable ID "
                        + "and company relationship digest",
                "cmp -s \"$v10_company_snapshot\" "
                        + "\"$latest_company_snapshot\"",
                "cmp -s \"$latest_company_snapshot\" "
                        + "\"$repeat_company_snapshot\"",
                "verify_company_dimension_latest",
                "relationship_tables=28",
                "relationship_edges=29",
                "company_indexes=5",
                "legacy_scopes=0",
                "repeat_noop=PASS");
    }

    @Test
    void registryFingerprintClassifiesOnlyMysqlImplicitForeignKeyIndexes() {
        String harness = read(WAVE3_MYSQL_HARNESS);

        assertThat(harness).contains(
                "INDEX_EXPLICIT",
                "INDEX_IMPLICIT_FK",
                "constraint_row.CONSTRAINT_NAME = index_row.INDEX_NAME",
                "registry fingerprint requires exactly 84 review-owned "
                        + "explicit indexes",
                "registry fingerprint requires exactly 29 classified "
                        + "implicit FK indexes",
                "if not row.startswith(\"INDEX_IMPLICIT_FK|\")",
                "non-implicit-index registry metadata changed",
                "semantic registry fingerprints differ",
                "negative_result = \"rejected\"",
                "negative_non_index_delta={negative_result}",
                "W3_REGISTRY_FINGERPRINT_DELTA=PASS",
                "\"implicit_fk_index_only\" if delta else \"none\"");
        assertThat(harness).contains(
                "verify_registry_database_contract",
                "verify_constraint_and_index_names",
                "company_boundary_relationship_contract_matches",
                "company_boundary_auxiliary_check_contract_matches");
    }

    @Test
    void developmentUpgradeBranchesByCurrentCheckpointAndIsRepeatSafe() {
        String harness = read(WAVE3_MYSQL_HARNESS);

        assertThat(harness).contains(
                "upgrade_development_company_dimension",
                "history_table_count",
                "if ((10#$checkpoint <= 6))",
                "if ((10#$checkpoint >= 7 && 10#$checkpoint <= 9))",
                "verify_fixed_legal_entity_before_v11",
                "if [[ \"$checkpoint\" == \"10\" ]]",
                "Development schema is newer than the supported company migration",
                "repeat_safe=true");
        int runAll = harness.indexOf("run_all() {");
        int adaptiveUpgrade = harness.indexOf(
                "upgrade_development_company_dimension", runAll);
        int verifyContract = harness.indexOf("verify_contract", adaptiveUpgrade);
        assertThat(adaptiveUpgrade).isGreaterThan(runAll);
        assertThat(verifyContract).isGreaterThan(adaptiveUpgrade);
    }

    @Test
    void v1ThroughV10ChecksumsAreExplicitlyPinned() {
        pinnedMigrations().forEach((file, expected) ->
                assertThat(sha256(MIGRATIONS.resolve(file)))
                        .as(file)
                        .isEqualTo(expected));
    }

    private static String migration(String file) {
        return read(MIGRATIONS.resolve(file));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(path + " must be readable", exception);
        }
    }

    private static String sha256(Path path) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path)));
        } catch (Exception exception) {
            throw new IllegalStateException(path + " must be hashable", exception);
        }
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static Map<String, String> pinnedMigrations() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(
                "V1__identity_organization_authorization_audit.sql",
                "5f5cdd3367ef7ab128974b7fcad89a44bcfc5631008bd066806f77d33f85c440");
        result.put(
                "V2__baseline_authorization_catalog.sql",
                "28934279faafa2154faccd470976aa6ab2c4a5058f7d1b08a989c271da5241b4");
        result.put(
                "V3__local_identity_session.sql",
                "75120a31c594f2a974c031ed400d028028f83fb915df1b457902df27a971f9f9");
        result.put(
                "V4__versioned_policy_foundation.sql",
                "22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8");
        result.put(
                "V5__people_initial_import_and_versioning.sql",
                "6fd13a0e31a37d27fb7d9f71f8117acf7d5fb0bd38d4d117303b2b8582b6b589");
        result.put(
                "V6__system_admin_people_read_prerequisite.sql",
                "11762c79e34bea2ab6aa790a6b32c6466658eec03fd7a97b23fe540cf985c003");
        result.put(
                "V7__attendance_setup_and_base_policies.sql",
                "3d37209aa462b49b71d4a51baf504f7eefab180b59a97d18cf64501e15ff57f2");
        result.put(
                "V8__attendance_source_and_evidence.sql",
                "084a66ed8a4b5b941aac6a2b6802f7119fa8ab6f55131b4cd2fa8c41d0f8d621");
        result.put(
                "V9__attendance_punch_import.sql",
                "56a0476ef24a6fd6ae8559f11b8ace4fdec274f6e1ce681c5525e8335dbd9150");
        result.put(
                "V10__formal_attendance_reporting.sql",
                "2306129fd60b3ef57642916e2f32774a71a5a9be891fe855673ff3a637b0be84");
        return Map.copyOf(result);
    }
}
