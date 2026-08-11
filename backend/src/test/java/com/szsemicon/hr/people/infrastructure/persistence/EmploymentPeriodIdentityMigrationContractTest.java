package com.szsemicon.hr.people.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EmploymentPeriodIdentityMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V39__maintain_employment_period_identity.sql");

    @Test
    void v39BackfillsThePostV38WindowAndFailsClosedOnConflictingLineage()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains(
                        "JOIN employment_period_identity identity",
                        "identity.employee_id <> assignment.employee_id",
                        "identity.company_id <> employee.company_id",
                        "LEFT JOIN employment_period_identity identity",
                        "WHERE identity.employment_period_id IS NULL",
                        "MIN(assignment.created_at)")
                .doesNotContain("INSERT IGNORE", "ON DUPLICATE KEY UPDATE");
        assertThat(occurrences(sql, "INSERT INTO employment_period_identity"))
                .isEqualTo(2);
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
