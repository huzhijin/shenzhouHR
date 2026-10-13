package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DeliEvidenceChainSyntheticFixtureContractTest {

    private static final Path FIXTURE = Path.of(
            "src/test/resources/fixtures/deli-eplus/evidence-chain.synthetic.sql");

    @Test
    void fixtureContainsTheCompleteSyntheticEvidenceChain() throws Exception {
        String sql = Files.readString(FIXTURE);
        String normalized = sql.toLowerCase(Locale.ROOT);

        assertThat(sql).contains("SYNTHETIC_TEST_ONLY_NOT_VENDOR_DATA");
        assertThat(normalized).contains(
                "insert into attendance_sync_job (",
                "insert into attendance_sync_job_page (",
                "insert into attendance_sync_watermark (",
                "insert into attendance_evidence_subject_lock (",
                "insert into raw_attendance_fact (",
                "insert into normalized_attendance_record (",
                "insert into employee_match_decision (",
                "insert into effective_attendance_event (",
                "insert into effective_event_lifecycle_fact (",
                "insert into evidence_link (",
                "insert into attendance_recalculation_intent (");

        assertThat(occurrences(sql,
                "d3110000-0000-0000-0000-000000000006"))
                .as("one fixed synthetic attendance source is reused")
                .isGreaterThanOrEqualTo(4);
        assertThat(occurrences(sql, "SYNTHETIC_TEST_ONLY_REQUEST_001"))
                .as("page, raw fact, lifecycle and recalculation share a request")
                .isGreaterThanOrEqualTo(5);
        assertThat(occurrences(sql,
                "d3110000-0000-0000-0000-000000000009"))
                .as("raw fact is referenced by normalization and evidence link")
                .isGreaterThanOrEqualTo(3);
        assertThat(occurrences(sql,
                "d3110000-0000-0000-0000-000000000012"))
                .as("effective event is referenced by lifecycle, link and intent")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void fixtureIsInsertOnlyAndCannotCarryRealAccessMaterial() throws Exception {
        String sql = Files.readString(FIXTURE);
        Pattern forbiddenStatement = Pattern.compile(
                "(?im)^\\s*(update|delete|replace|truncate|drop|alter|call|"
                        + "create\\s+(?:table|database|schema|view|procedure|function|"
                        + "trigger|user)|grant|revoke|load\\s+data)\\b");

        assertThat(forbiddenStatement.matcher(sql).find()).isFalse();
        assertThat(sql.toLowerCase(Locale.ROOT)).doesNotContain(
                "into outfile",
                "local infile",
                "app_secret",
                "access_key",
                "password=",
                "jdbc:mysql:",
                "source /");
        assertThat(sql).doesNotContain("FOREIGN_KEY_CHECKS");
        assertThat(sql).contains("START TRANSACTION;", "COMMIT;");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
