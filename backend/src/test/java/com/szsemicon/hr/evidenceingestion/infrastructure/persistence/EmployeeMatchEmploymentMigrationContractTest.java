package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class EmployeeMatchEmploymentMigrationContractTest {

    private static final Path V45 = Path.of(
            "src/main/resources/db/migration/"
                    + "V45__fix_employee_match_employment_fk.sql");

    @Test
    void legacyPeriodColumnStillReferencesCanonicalAssignmentIdentity()
            throws Exception {
        String migration = Files.readString(V45)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertThat(migration)
                .contains(
                        "foreign key (employment_period_id)",
                        "references employment_assignment (assignment_id)")
                .doesNotContain("references employment (employment_period_id)");
    }
}
