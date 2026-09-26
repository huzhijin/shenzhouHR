package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SystemPrincipalMigrationContractTest {

    private static final Path V46 = Path.of(
            "src/main/resources/db/migration/V46__create_system_principal.sql");

    @Test
    void systemPrincipalUsesTheActualAuthPrincipalSchema() throws Exception {
        String migration = Files.readString(V46)
                .replaceAll("--.*", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertThat(migration)
                .contains(
                        "insert into auth_principal",
                        "principal_id, employee_id, status, row_version, created_at",
                        "'system', null, 'active', 0, current_timestamp(6)")
                .doesNotContain("principal_kind", "display_name", "updated_at");
    }
}
