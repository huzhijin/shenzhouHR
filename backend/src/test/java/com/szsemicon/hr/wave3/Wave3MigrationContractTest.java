package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Wave3MigrationContractTest {

    private static final Map<String, String> IMMUTABLE_MIGRATIONS = immutableMigrations();

    @Test
    void preservesEveryWave1AndWave2MigrationChecksum() {
        IMMUTABLE_MIGRATIONS.forEach((file, expected) ->
                assertThat(sha256(readRepositoryBytes(
                                "backend/src/main/resources/db/migration/" + file)))
                        .as(file)
                        .isEqualTo(expected));
    }

    @Test
    void createsStableIdentitiesAndImmutableRevisionsWithoutDestructiveSql() {
        String migration = migration();

        assertThat(migration).contains(
                "CREATE TABLE location (",
                "CREATE TABLE location_revision (",
                "CREATE TABLE location_timeline (",
                "CREATE TABLE attendance_group (",
                "CREATE TABLE attendance_group_revision (",
                "CREATE TABLE attendance_group_timeline (",
                "CREATE TABLE attendance_group_assignment (",
                "CREATE TABLE attendance_assignment_timeline (",
                "CREATE TABLE shift_template (",
                "CREATE TABLE shift_version (",
                "CREATE TABLE shift_publication_timeline (",
                "CREATE TABLE work_calendar (",
                "CREATE TABLE work_calendar_version (",
                "CREATE TABLE calendar_publication_timeline (",
                "CREATE TABLE work_calendar_day (",
                "CREATE TABLE attendance_policy_template (",
                "CREATE TABLE attendance_policy_scope (",
                "CREATE TABLE attendance_policy_scoped_version (",
                "CREATE TABLE attendance_policy_lifecycle_event (",
                "CREATE TABLE attendance_policy_binding_family (",
                "CREATE TABLE attendance_policy_binding_revision (",
                "CREATE TABLE attendance_setup_idempotency (");
        assertThat(migration.toUpperCase())
                .doesNotContain(
                        "DROP TABLE",
                        "TRUNCATE TABLE",
                        "DELETE FROM",
                        "ALTER TABLE POLICY_VERSION");
    }

    @Test
    void v31CreatesOnlyTheSharedLocationSchemaAndDefersBusinessBackfill() {
        String migration = new String(readRepositoryBytes(
                "backend/src/main/resources/db/migration/"
                        + "V31__shared_physical_location_catalog.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration).contains(
                "CREATE TABLE shared_location (",
                "CREATE TABLE shared_location_revision (",
                "CREATE TABLE company_location_availability (",
                "uq_shared_location_code",
                "uq_shared_location_identity_code",
                "uq_company_location_shared",
                "uq_location_company_projection",
                "uq_location_company_projection_code",
                "fk_company_location_shared_code",
                "fk_company_location_projection_company");
        assertThat(migration.toUpperCase()).doesNotContain(
                "INSERT INTO", "UPDATE ", "DELETE FROM", "DROP TABLE");
    }

    @Test
    void sharedLocationDeploymentScriptsEnforceV31AndPreserveSchemaOwnership() {
        String finalizer = repositoryText(
                "deploy/mysql/four-company-finalization-post-v30.sql");
        String convergence = repositoryText(
                "deploy/mysql/shared-location-convergence-post-v30.sql");
        String verifier = repositoryText(
                "deploy/mysql/verify-four-company-finalization.sql");

        assertThat(finalizer)
                .contains(
                        "DATABASE() IS NULL",
                        "version = '31' AND success = 1",
                        "shared_location_revision",
                        "company_location_availability")
                .doesNotContain("DATABASE() <> 'shenzhou_hr_dev'");
        assertThat(convergence).contains(
                "version = '31' AND success = 1",
                "fk_company_location_projection_company",
                "shared_location_id,location_code",
                "company_id,location_id,location_code",
                "target company location availability period drifted",
                "shared latest revision drifted from company projections",
                "four-company location target must be 7 shared and 28 availability rows");
        assertThat(convergence.toUpperCase())
                .doesNotContain("CREATE TABLE", "ALTER TABLE");
        assertThat(verifier).contains(
                "shared_location_chain_metrics",
                "configuration.shared_location_head_count",
                "integrity.shared_location_revision_chain_mismatch",
                "configuration.company_location_availability_cardinality");
    }

    @Test
    void locksPeriodVersionHistoryIdempotencyAndReferenceContracts() {
        assertThat(migration()).contains(
                "CHECK (effective_to IS NULL OR effective_to > effective_from)",
                "row_version BIGINT UNSIGNED NOT NULL DEFAULT 0",
                "location_revision_id",
                "attendance_group_revision_id",
                "work_calendar_version_id",
                "shift_version_override_id",
                "shift_template_id",
                "work_calendar_id",
                "time_zone_snapshot",
                "snapshot_digest CHAR(64)",
                "uq_location_revision_number",
                "uq_attendance_group_revision_number",
                "uq_shift_version_number",
                "uq_work_calendar_version_number",
                "uq_work_calendar_day_date",
                "uq_attendance_policy_binding_family",
                "uq_attendance_policy_binding_revision_number",
                "uq_attendance_setup_idempotency",
                "fk_attendance_assignment_employee",
                "fk_attendance_policy_binding_version",
                "request_digest CHAR(64)",
                "CHECK (state IN ('STARTED', 'COMPLETED_SUCCESS'))",
                "response_status SMALLINT UNSIGNED",
                "response_headers_json JSON",
                "response_body_json JSON");
    }

    @Test
    void groupAndCalendarReferenceStableFamiliesRatherThanConcreteVersions() {
        String migration = migration();

        String groupRevision = section(
                migration, "CREATE TABLE attendance_group_revision (",
                "CREATE TABLE attendance_group_assignment (");
        assertThat(groupRevision)
                .contains(
                        "location_revision_id",
                        "shift_template_id",
                        "work_calendar_id")
                .doesNotContain("default_shift_version_id", "work_calendar_version_id");

        String calendarDay = section(
                migration, "CREATE TABLE work_calendar_day (",
                "CREATE TABLE attendance_group (");
        assertThat(calendarDay)
                .contains("work_calendar_version_id", "shift_version_override_id")
                .doesNotContain("shift_version_id VARCHAR");
    }

    @Test
    void scopedPolicyVersionsAreIndependentAndSeedsUseTheFixedOracle() {
        String migration = migration();

        assertThat(migration).contains(
                "CREATE TABLE attendance_policy_scope (",
                "CREATE TABLE attendance_policy_scoped_version (",
                "'25000000-0000-0000-0000-000000000001'",
                "'25000000-0000-0000-0000-000000000002'",
                "'25000000-0000-0000-0000-000000000003'",
                "'25200000-0000-0000-0000-000000000001'",
                "'25200000-0000-0000-0000-000000000002'",
                "'25200000-0000-0000-0000-000000000003'",
                "'mealWindowStart'",
                "'mealWindowEnd'",
                "'applicableDayTypes'",
                "'valueType', 'ENUM_LIST'",
                "'WORKDAY', 'SPECIAL_WORKDAY'",
                "'WEEKEND', 'PUBLIC_HOLIDAY'",
                "'graceMinutes', 15",
                "'b0a533500852c464c7065812fd56519f0c571ebbf453834a8b189388e29f1a51'",
                "DATE('1970-01-01')");
        assertThat(section(
                migration,
                "CREATE TABLE attendance_policy_scoped_version (",
                "CREATE TABLE attendance_policy_lifecycle_event ("))
                .doesNotContain(" status ");
        assertThat(migration)
                .doesNotContain(
                        "maximumLateMinutes",
                        "minimumLateMinutes",
                        "'key', 'mealWindow'",
                        "'key', 'applicableDayType'",
                        "'key', 'inheritanceSource'",
                        "643f6bff05feddc128e0f0aaf6fe52da47058f7e629a63ca50ce40ce91925310",
                        "INSERT INTO policy_template (",
                        "INSERT INTO policy_version (");
    }

    @Test
    void seedsTheSixCapabilitiesAndReadOnlyAuditorGrant() {
        assertThat(migration()).contains(
                "ATTENDANCE_SETUP:READ",
                "ATTENDANCE_SETUP:MANAGE_GROUP",
                "ATTENDANCE_SETUP:ASSIGN",
                "ATTENDANCE_SETUP:MANAGE_SHIFT",
                "ATTENDANCE_SETUP:MANAGE_CALENDAR",
                "ATTENDANCE_SETUP:MANAGE_POLICY",
                "role.role_code IN ('HR_ADMIN', 'SYSTEM_ADMIN')",
                "role.role_code = 'AUDITOR'");
    }

    private static int count(String value, String needle) {
        int result = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            result++;
            index += needle.length();
        }
        return result;
    }

    private static String section(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        assertThat(from).as("section start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(to).as("section end %s", end).isGreaterThan(from);
        return value.substring(from, to);
    }

    private static String migration() {
        return new String(readRepositoryBytes(
                "backend/src/main/resources/db/migration/"
                        + "V7__attendance_setup_and_base_policies.sql"), StandardCharsets.UTF_8);
    }

    private static String repositoryText(String path) {
        return new String(readRepositoryBytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] readRepositoryBytes(String path) {
        try {
            Path fromBackendDirectory = Path.of("..", path);
            Path fromRepositoryRoot = Path.of(path);
            Path file = Files.exists(fromBackendDirectory)
                    ? fromBackendDirectory
                    : fromRepositoryRoot;
            return Files.readAllBytes(file);
        } catch (Exception exception) {
            throw new IllegalStateException(path + " must be readable", exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static Map<String, String> immutableMigrations() {
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put(
                "V1__identity_organization_authorization_audit.sql",
                "5f5cdd3367ef7ab128974b7fcad89a44bcfc5631008bd066806f77d33f85c440");
        checksums.put(
                "V2__baseline_authorization_catalog.sql",
                "28934279faafa2154faccd470976aa6ab2c4a5058f7d1b08a989c271da5241b4");
        checksums.put(
                "V3__local_identity_session.sql",
                "75120a31c594f2a974c031ed400d028028f83fb915df1b457902df27a971f9f9");
        checksums.put(
                "V4__versioned_policy_foundation.sql",
                "22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8");
        checksums.put(
                "V5__people_initial_import_and_versioning.sql",
                "6fd13a0e31a37d27fb7d9f71f8117acf7d5fb0bd38d4d117303b2b8582b6b589");
        checksums.put(
                "V6__system_admin_people_read_prerequisite.sql",
                "11762c79e34bea2ab6aa790a6b32c6466658eec03fd7a97b23fe540cf985c003");
        return Map.copyOf(checksums);
    }
}
