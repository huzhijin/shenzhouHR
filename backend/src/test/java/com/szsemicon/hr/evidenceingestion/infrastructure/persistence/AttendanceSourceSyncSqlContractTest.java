package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AttendanceSourceSyncSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceSourceSyncMapper.xml");

    @Test
    void runAndReadAreCapabilityAndLegalEntityScopedWithPageLocks()
            throws Exception {
        String sql = Files.readString(MAPPER);
        String upper = sql.toUpperCase(Locale.ROOT);

        assertThat(sql)
                .contains("principal.status = 'ACTIVE'")
                .contains(
                        "granted_capability.capability_code ="
                                + " #{capability}")
                .contains("data_scope.scope_type = 'LEGAL_ENTITY'")
                .contains(
                        "data_scope.legal_entity_id ="
                                + " source.legal_entity_id")
                .contains("source.source_type = 'DELI_CLOUD'")
                .contains("source.status = 'ACTIVE'")
                .contains("job.status = 'RUNNING'")
                .contains("row_version = #{expectedVersion}");
        assertThat(upper).contains("FOR UPDATE");
        assertThat(sql)
                .doesNotContain("${")
                .doesNotContain("check_data")
                .doesNotContain("app_secret")
                .doesNotContain("app_key");
    }

    @Test
    void watermarkOnlyAdvancesWithTheCommittedPageTransaction()
            throws Exception {
        String sql = Files.readString(MAPPER);

        assertThat(sql)
                .contains("<insert id=\"insertCommittedPage\">")
                .contains("<update id=\"advanceWatermark\">")
                .contains("committed_page_digest = #{pageDigest}")
                .contains("page_count = page_count + 1")
                .contains(
                        "status IN ('QUEUED', 'RUNNING')");
    }

    @Test
    void staleJobsRecoverAndRetryIsScopedVersionedAndIdempotent()
            throws Exception {
        String sql = Files.readString(MAPPER);

        assertThat(sql)
                .contains("<update id=\"expireStaleActiveJobs\">")
                .contains("'DELI_SYNC_LEASE_EXPIRED'")
                .contains("SELECT MAX(page.committed_at)")
                .contains("<select id=\"lockAuthorizedDeliRetryCandidate\"")
                .contains("data_scope.scope_type = 'LEGAL_ENTITY'")
                .contains("<select id=\"findRetryIdempotency\"")
                .contains("'DELI_SOURCE_RETRY'")
                .contains("<insert id=\"insertRetryIdempotency\">")
                .contains("<update id=\"completeRetryIdempotency\">")
                .contains("<update id=\"touchRetriedJob\">")
                .contains("row_version = #{expectedRowVersion}")
                .contains(
                        "status IN ('FAILED',"
                                + " 'PARTIALLY_QUARANTINED', 'CANCELLED')");
    }

    @Test
    void sourceRegistrationIsScopedIdempotentAndStoresOnlyAReference()
            throws Exception {
        String sql = Files.readString(MAPPER);

        assertThat(sql)
                .contains("<select id=\"lockAuthorizedLegalEntity\"")
                .contains("entity.status = 'ACTIVE'")
                .contains("'ATTENDANCE_SOURCE'")
                .contains("'DELI_SOURCE_REGISTER'")
                .contains("<insert id=\"insertRegistrationIdempotency\"")
                .contains("<update id=\"completeRegistrationIdempotency\"")
                .contains("#{command.secretReferenceName}")
                .contains(
                        "'personReferencePolicy',"
                                + " 'CONFIRMED_DELI_BINDING'")
                .contains(
                        "'forbiddenPayloadPolicy', 'DROP'")
                .contains(
                        "other_configuration.secret_reference_name ="
                                + "\n                    configuration.secret_reference_name");
        assertThat(sql.toLowerCase(Locale.ROOT))
                .doesNotContain("app-secret")
                .doesNotContain("app_secret")
                .doesNotContain("app-key")
                .doesNotContain("app_key")
                .doesNotContain("check_data");
    }
}
