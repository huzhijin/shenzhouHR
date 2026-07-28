package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AttendanceReportExportSqlContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V10__formal_attendance_reporting.sql");
    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/"
                    + "AttendanceReportExportMapper.xml");

    @Test
    void artifactIsSeparatedFromMetadataAndPurgedWithItsOwner()
            throws Exception {
        String migration = Files.readString(MIGRATION);
        String jobTable = between(
                migration,
                "CREATE TABLE attendance_report_export_job",
                "CREATE TABLE attendance_report_export_artifact");
        String artifactTable = between(
                migration,
                "CREATE TABLE attendance_report_export_artifact",
                "INSERT INTO auth_role");

        assertThat(jobTable)
                .contains("principal_id VARCHAR(36)")
                .contains("REFERENCES auth_principal (principal_id)")
                .contains("legal_entity_id VARCHAR(36)")
                .contains("CONSTRAINT fk_att_report_export_entity")
                .contains("REFERENCES legal_entity (legal_entity_id)")
                .contains("purpose VARCHAR(200) NOT NULL")
                .contains(
                        "CHAR_LENGTH(TRIM(purpose)) BETWEEN 2 AND 200")
                .contains("authorization_digest CHAR(64)")
                .contains("query_fingerprint CHAR(64)")
                .contains("visible_content_digest CHAR(64)")
                .contains(
                        "visible_content_digest REGEXP '^[0-9a-f]{64}$'")
                .contains("CHECK (status IN ('QUEUED', 'BUILDING',"
                        + " 'READY', 'FAILED'))")
                .contains("status = 'BUILDING'")
                .contains("claimed_at IS NOT NULL")
                .doesNotContain("LONGBLOB")
                .doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("secret");
        assertThat(Files.readString(MAPPER))
                .contains("job.legal_entity_id")
                .contains("#{legalEntityId}");
        assertThat(artifactTable)
                .contains("content LONGBLOB NOT NULL")
                .contains("ON DELETE CASCADE")
                .doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("secret");
    }

    @Test
    void ownershipAndBlobReadsCannotBeConfused() throws Exception {
        String xml = Files.readString(MAPPER);
        String metadata = select(xml, "findOwnedJob");
        String artifact = select(xml, "findOwnedReadyArtifact");

        assertThat(xml)
                .doesNotContain("${")
                .doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("suppliedSecret")
                .doesNotContainIgnoringCase("currentPassword");
        assertThat(metadata)
                .contains(
                        "job.attendance_report_export_id = #{exportId}")
                .contains("job.principal_id = #{principalId}")
                .contains("NULL AS content")
                .doesNotContain(
                        "attendance_report_export_artifact artifact");
        assertThat(artifact)
                .contains(
                        "job.attendance_report_export_id = #{exportId}")
                .contains("job.principal_id = #{principalId}")
                .contains(
                        "JOIN attendance_report_export_artifact artifact")
                .contains("job.status = 'READY'")
                .contains("job.expires_at &gt; #{readAt}")
                .contains(
                        "job.query_fingerprint = #{expectedQueryFingerprint}")
                .contains("job.visible_content_digest =")
                .contains("#{expectedVisibleContentDigest}")
                .doesNotContain("LEFT JOIN");
    }

    @Test
    void asyncClaimAndCompletionAreCompareAndSetTransitions()
            throws Exception {
        String xml = Files.readString(MAPPER);

        assertThat(select(xml, "findNextQueuedForUpdate"))
                .contains("job.status = 'QUEUED'")
                .contains("job.delivery_mode = 'ASYNC'")
                .contains("job.expires_at &gt; #{claimedAt}")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(update(xml, "markBuilding"))
                .contains("SET status = 'BUILDING'")
                .contains("AND status = 'QUEUED'")
                .contains("AND expires_at &gt; #{claimedAt}");
        assertThat(update(xml, "markReady"))
                .contains("SET status = 'READY'")
                .contains("AND status = 'BUILDING'")
                .contains("AND visible_content_digest =")
                .contains("#{expectedVisibleContentDigest}")
                .contains("AND expires_at &gt; #{completedAt}");
        assertThat(update(xml, "markFailed"))
                .contains("SET status = 'FAILED'")
                .contains("AND status = 'BUILDING'");
        assertThat(delete(xml, "purgeExpired"))
                .contains("status IN ('QUEUED', 'READY', 'FAILED')")
                .doesNotContain("'BUILDING', 'READY'");
    }

    private static String select(String xml, String id) {
        return between(xml, "<select id=\"" + id + "\"", "</select>");
    }

    private static String update(String xml, String id) {
        return between(xml, "<update id=\"" + id + "\"", "</update>");
    }

    private static String delete(String xml, String id) {
        return between(xml, "<delete id=\"" + id + "\"", "</delete>");
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex);
        return value.substring(startIndex, endIndex + end.length());
    }
}
