package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OaLeaveRevocationMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V40__oa_leave_revocation_form_contract.sql");

    @Test
    void v40ExpandsTheOaProbeAndRuntimeAllowlistsWithoutChangingV19() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains(
                        "expected_table_count = 11",
                        "'formmain_0370'",
                        "'LEAVE_REVOCATION'",
                        "DROP CHECK ck_oa_probe_counts",
                        "DROP CHECK ck_oa_probe_table_name",
                        "DROP CHECK ck_oa_contract_form_kind")
                .doesNotContain(
                        "UPDATE oa_metadata_probe_run",
                        "UPDATE oa_runtime_table_contract",
                        "contract_status = 'PUBLISHED'");
    }
}
