package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class Wave4MigrationContractTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path MIGRATIONS =
            ROOT.resolve("backend/src/main/resources/db/migration");
    private static final Path REGISTRY = ROOT.resolve(
            "openspec/changes/wave4-attendance-sources-and-punch-imports/"
                    + "specs/wave4-verification/oracles/w4-schema-registry-v1.json");

    @Test
    void retainedV1ThroughV7ChecksumsRemainFrozen() throws Exception {
        Map<String, String> expected = registry()
                .retainedMigrationSha256();
        assertThat(expected).hasSize(7);
        expected.forEach((file, digest) -> assertThat(sha256(MIGRATIONS.resolve(file)))
                .as(file)
                .isEqualTo(digest));
    }

    @Test
    void migrationVersionsWereAllocatedAfterInspectingTheCurrentDirectory() throws IOException {
        assertThat(Files.exists(MIGRATIONS.resolve(
                "V8__attendance_source_and_evidence.sql"))).isTrue();
        assertThat(Files.exists(MIGRATIONS.resolve(
                "V9__attendance_punch_import.sql"))).isTrue();
        assertThat(Files.list(MIGRATIONS)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.matches("V(8|9)__.*\\.sql"))
                .sorted()
                .toList())
                .containsExactly(
                        "V8__attendance_source_and_evidence.sql",
                        "V9__attendance_punch_import.sql");
    }

    @Test
    void independentRegistryMatchesMigrationTablesAndOrderedColumns() throws Exception {
        Registry oracle = registry();
        String sql = Files.readString(
                MIGRATIONS.resolve("V8__attendance_source_and_evidence.sql"))
                + "\n"
                + Files.readString(
                        MIGRATIONS.resolve("V9__attendance_punch_import.sql"));
        Map<String, java.util.List<String>> actual = tableColumns(sql);

        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(
                oracle.tables().keySet());
        oracle.tables().forEach((table, columns) ->
                assertThat(actual.get(table)).as(table).containsExactlyElementsOf(columns));
    }

    @Test
    void appendOnlyTablesHaveNoBusinessUpdateOrDeleteStatement() throws Exception {
        Registry oracle = registry();
        String production = Files.readString(
                MIGRATIONS.resolve("V8__attendance_source_and_evidence.sql"))
                + Files.readString(
                        MIGRATIONS.resolve("V9__attendance_punch_import.sql"));
        oracle.appendOnlyTables().forEach(table -> {
            assertThat(production).doesNotContainIgnoringCase("UPDATE " + table);
            assertThat(production).doesNotContainIgnoringCase("DELETE FROM " + table);
        });
    }

    @Test
    void mysqlReservedImportRowIdentifierIsQuotedEverywhere() throws Exception {
        String migration = Files.readString(
                MIGRATIONS.resolve("V9__attendance_punch_import.sql"));

        assertThat(migration).contains(
                "    `row_number` INT UNSIGNED NOT NULL,",
                "(punch_import_batch_id, punch_import_file_id, `row_number`)",
                "(punch_import_batch_id, `row_number`, punch_import_row_id)",
                "CHECK (`row_number` BETWEEN 1 AND 50000)");
        assertThat(migration).doesNotContain(
                "    row_number INT UNSIGNED NOT NULL,",
                "CHECK (row_number BETWEEN 1 AND 50000)");
    }

    private static Map<String, java.util.List<String>> tableColumns(String sql) {
        Pattern tablePattern = Pattern.compile(
                "(?is)CREATE\\s+TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\)\\s*ENGINE=");
        Pattern columnPattern = Pattern.compile(
                "(?m)^\\s{4}`?([a-z][a-z0-9_]*)`?\\s+"
                        + "(?:VARCHAR|CHAR|BIGINT|INT|SMALLINT|DATETIME|DATE|JSON)");
        Map<String, java.util.List<String>> result = new LinkedHashMap<>();
        Matcher tableMatcher = tablePattern.matcher(sql);
        while (tableMatcher.find()) {
            Matcher columnMatcher = columnPattern.matcher(tableMatcher.group(2));
            java.util.List<String> columns = new java.util.ArrayList<>();
            while (columnMatcher.find()) {
                columns.add(columnMatcher.group(1));
            }
            result.put(tableMatcher.group(1), columns);
        }
        return result;
    }

    private static Registry registry() throws IOException {
        return new ObjectMapper().readValue(
                Files.readString(REGISTRY),
                new TypeReference<Registry>() {});
    }

    private static String sha256(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record Registry(
            String registryVersion,
            String independentSource,
            java.util.List<Integer> migrationVersions,
            Map<String, String> retainedMigrationSha256,
            Map<String, java.util.List<String>> tables,
            java.util.List<String> appendOnlyTables) {
    }
}
