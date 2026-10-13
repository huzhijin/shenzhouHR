package com.szsemicon.hr.wave7;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BusinessRulesAlignmentSchemaVerifierContractTest {

    private static final Path VERIFIER = Path.of(
            "../deploy/mysql/sql/verify-business-rules-alignment-schema.sql");

    @Test
    void verifierIsReadOnlyAndCoversEveryV48SchemaArea() throws Exception {
        String sql = Files.readString(VERIFIER);
        String executable = sql.lines()
                .map(line -> line.replaceFirst("--.*$", "").trim().toUpperCase())
                .filter(line -> !line.isEmpty())
                .reduce("", (left, right) -> left + right + "\n");

        assertThat(sql).contains(
                "flyway_v48_success",
                "daily_fact_business_columns",
                "business_rule_tables",
                "oa_overtime_context_column",
                "business_rule_check_constraints",
                "confirmed_overtime_enum_mappings",
                "system_automation_principal",
                "overall_status");
        assertThat(executable).doesNotContainPattern(
                "(?m)^(UPDATE|DELETE|INSERT|TRUNCATE|ALTER|DROP|CREATE|REPLACE|CALL|LOCK|SET)\\b");
    }
}
