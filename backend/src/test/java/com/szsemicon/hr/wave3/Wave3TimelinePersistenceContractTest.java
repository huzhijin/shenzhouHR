package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class Wave3TimelinePersistenceContractTest {

    @Test
    void retainedMysqlAndLatestH2ColumnsMatchTheWave3Registry() {
        String migration = read(
                "src/main/resources/db/migration/"
                        + "V7__attendance_setup_and_base_policies.sql");
        String schema = read("src/test/resources/db/test-schema.sql");
        JsonNode tables = readRegistry().get("tables");

        for (JsonNode table : tables) {
            String tableName = table.get("table").asString();
            List<String> expected = new ArrayList<>();
            for (JsonNode column : table.get("columns")) {
                expected.add(column.asString().split(":", 2)[0]);
            }
            assertThat(tableColumnNames(migration, tableName))
                    .as("MySQL %s", tableName)
                    .containsExactlyElementsOf(expected);
            List<String> latestExpected = expected.stream()
                    .map(column -> column.equals("legal_entity_id")
                            ? "company_id" : column)
                    .toList();
            assertThat(tableColumnNames(schema, tableName))
                    .as("H2 %s", tableName)
                    .containsExactlyElementsOf(latestExpected);
        }
    }

    @Test
    void fixedRegistryContentRowsDoNotCarryMutableLifecycleColumns() {
        String migration = read(
                "src/main/resources/db/migration/"
                        + "V7__attendance_setup_and_base_policies.sql");

        assertImmutableSection(
                migration, "location_revision", "location_timeline",
                List.of(
                        "supersedes_location_revision_id",
                        "snapshot_digest",
                        "effective_from"),
                List.of(" status ", "effective_to", "updated_at", "updated_by"));
        assertImmutableSection(
                migration, "attendance_group_revision",
                "attendance_group_assignment",
                List.of(
                        "supersedes_attendance_group_revision_id",
                        "location_revision_id",
                        "work_calendar_id",
                        "shift_template_id"),
                List.of(" status ", "effective_to", "updated_at", "updated_by"));
        assertImmutableSection(
                migration, "shift_version", "work_calendar",
                List.of("supersedes_shift_version_id", "segments_json"),
                List.of(" status ", "effective_to", "published_at", "updated_at"));
        assertImmutableSection(
                migration, "work_calendar_version", "work_calendar_day",
                List.of("supersedes_work_calendar_version_id", "effective_to"),
                List.of(" status ", "published_at", "updated_at", "updated_by"));
        assertImmutableSection(
                migration, "work_calendar_day", "attendance_group",
                List.of("snapshot_digest"),
                List.of("row_version", "updated_at", "updated_by"));
        assertImmutableSection(
                migration, "attendance_group_assignment", "location_timeline",
                List.of(
                        "attendance_group_revision_id",
                        "supersedes_assignment_id",
                        "snapshot_digest"),
                List.of("attendance_group_id", "effective_to", "updated_at", "updated_by"));
    }

    @Test
    void h2MirrorsEveryTimelineWithForeignKeysChecksAndResolutionIndexes() {
        String schema = read("src/test/resources/db/test-schema.sql");

        for (String timeline : List.of(
                "location_timeline",
                "attendance_group_timeline",
                "attendance_assignment_timeline",
                "shift_publication_timeline",
                "calendar_publication_timeline")) {
            String section = tableSection(schema, timeline);
            assertThat(section)
                    .as(timeline)
                    .contains(
                            "event_sequence",
                            "business_effective_from",
                            "recorded_at",
                            "predecessor_timeline_id",
                            "FOREIGN KEY",
                            "CHECK");
        }
        assertThat(schema).contains(
                "ix_test_location_timeline_resolution",
                "ix_test_attendance_group_timeline_resolution",
                "ix_test_attendance_assignment_timeline_resolution",
                "ix_test_shift_publication_resolution",
                "ix_test_calendar_publication_resolution");
    }

    @Test
    void mappersAppendFactsAndNeverUpdateImmutableBusinessContent() {
        String group = read("src/main/resources/mappers/AttendanceGroupMapper.xml");
        String shift = read("src/main/resources/mappers/ShiftMapper.xml");
        String calendar = read("src/main/resources/mappers/CalendarMapper.xml");
        String policy = read("src/main/resources/mappers/AttendancePolicyMapper.xml");

        assertThat(group).contains(
                "INSERT INTO location_timeline",
                "INSERT INTO attendance_group_timeline",
                "INSERT INTO attendance_assignment_timeline",
                "<select id=\"findAssignmentSuccessor\"",
                "assignment.supersedes_assignment_id = #{predecessorAssignmentId}",
                "recorded_at &lt;= #{knowledgeAsOf}");
        assertThat(group).contains(
                "<select id=\"lockLocationRevision\"",
                "<select id=\"findGroupIdsReferencingLocationRevision\"",
                "<select id=\"lockGroupRevision\"",
                "<select id=\"findAssignmentsCrossingBoundary\"",
                "<select id=\"lockAssignmentTimeline\"",
                "<insert id=\"insertAssignmentRolloverSuccessor\"");
        assertThat(mapperSelect(
                group, "findGroupIdsReferencingLocationRevision"))
                .contains("ORDER BY scoped.attendance_group_id")
                .doesNotContain("LIMIT");
        assertThat(mapperSelect(group, "findAssignmentsCrossingBoundary"))
                .contains(
                        "assignment.attendance_group_revision_id",
                        "ORDER BY assignment.employee_id",
                        "assignment.attendance_group_assignment_id")
                .doesNotContain("LIMIT");
        assertThat(policy).contains(
                "<select id=\"findBindingFamilyHeads\"",
                "<select id=\"lockBindingFamily\"");
        assertThat(mapperSelect(policy, "findBindingFamilyHeads"))
                .contains(
                        "family.attendance_group_id = #{groupId}",
                        "family.policy_kind = #{policyKind}")
                .doesNotContain("LIMIT");
        assertThat(shift).contains(
                "INSERT INTO shift_publication_timeline",
                "recorded_at &lt;= #{knowledgeAsOf}");
        assertThat(calendar).contains(
                "INSERT INTO calendar_publication_timeline",
                "recorded_at &lt;= #{knowledgeAsOf}");

        assertThat(normalize(group)).doesNotContain(
                "update location_revision",
                "update attendance_group_revision",
                "update attendance_group_assignment");
        assertThat(normalize(shift)).doesNotContain(
                "update shift_version",
                "update shift_template");
        assertThat(normalize(calendar)).doesNotContain(
                "update work_calendar_version",
                "update work_calendar_day",
                "update work_calendar ");
    }

    @Test
    void everyAttendanceSetupListIsBoundedCountedAndDeterministicallyOrdered() {
        String group = read("src/main/resources/mappers/AttendanceGroupMapper.xml");
        String shift = read("src/main/resources/mappers/ShiftMapper.xml");
        String calendar = read("src/main/resources/mappers/CalendarMapper.xml");

        assertBoundedList(
                group,
                "listLocationRevisions",
                "revision.revision_number DESC",
                "revision.location_revision_id");
        assertBoundedList(
                group,
                "listGroupRevisions",
                "revision.revision_number DESC",
                "revision.attendance_group_revision_id");
        assertBoundedList(
                group,
                "listAssignments",
                "assignment.effective_from",
                "assignment.attendance_group_assignment_id");
        assertThat(group).contains(
                "<select id=\"countLocationRevisions\"",
                "<select id=\"countGroupRevisions\"",
                "<select id=\"countAssignments\"");

        assertBoundedList(
                shift,
                "listTemplates",
                "scoped.template_code",
                "scoped.shift_template_id");
        assertBoundedList(
                shift,
                "listVersions",
                "version.version_number DESC",
                "version.shift_version_id");
        assertThat(shift).contains(
                "<select id=\"countTemplates\"",
                "<select id=\"countVersions\"");

        assertBoundedList(
                calendar,
                "listCalendars",
                "version.calendar_year DESC",
                "scoped.work_calendar_id");
        assertBoundedList(
                calendar,
                "listVersions",
                "version.version_number DESC",
                "version.work_calendar_version_id");
        assertBoundedList(
                calendar,
                "listDays",
                "calendar_day.business_date",
                "calendar_day.work_calendar_day_id");
        assertThat(calendar).contains(
                "<select id=\"countCalendars\"",
                "<select id=\"countVersions\"",
                "<select id=\"countDays\"");
    }

    private static void assertBoundedList(
            String mapper, String id, String primaryOrder, String immutableTieBreaker) {
        String select = mapperSelect(mapper, id);
        assertThat(select)
                .contains(
                        "ORDER BY",
                        primaryOrder,
                        immutableTieBreaker,
                        "LIMIT #{limit} OFFSET #{offset}");
    }

    private static String mapperSelect(String mapper, String id) {
        String start = "<select id=\"" + id + "\"";
        return section(mapper, start, "</select>");
    }

    private static void assertImmutableSection(
            String sql,
            String table,
            String nextTable,
            List<String> required,
            List<String> forbidden) {
        String section = tableSection(sql, table);
        assertThat(section).contains(required.toArray(String[]::new));
        assertThat(normalize(section)).doesNotContain(
                forbidden.stream().map(Wave3TimelinePersistenceContractTest::normalize)
                        .toArray(String[]::new));
    }

    private static String tableSection(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE " + table + " (");
        int end = sql.indexOf(";", start);
        assertThat(start).as(table + " start").isGreaterThanOrEqualTo(0);
        assertThat(end).as(table + " end").isGreaterThan(start);
        return sql.substring(start, end);
    }

    private static String section(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        assertThat(from).as("section start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(to).as("section end %s", end).isGreaterThan(from);
        return value.substring(from, to);
    }

    private static String normalize(String value) {
        return value.toLowerCase().replaceAll("\\s+", " ");
    }

    private static List<String> tableColumnNames(String sql, String table) {
        List<String> columns = new ArrayList<>();
        for (String line : tableSection(sql, table).lines().toList()) {
            if (!line.startsWith("    ") || line.startsWith("        ")) {
                continue;
            }
            String token = line.strip().split("\\s+", 2)[0];
            if (!Set.of(
                    "PRIMARY",
                    "UNIQUE",
                    "KEY",
                    "CONSTRAINT",
                    "CHECK",
                    "),").contains(token)) {
                columns.add(token);
            }
        }
        return columns;
    }

    private static JsonNode readRegistry() {
        try {
            Path path = Path.of(
                    "..",
                    "openspec/changes/wave3-attendance-setup-and-policies/"
                            + "specs/wave3-verification/oracles/"
                            + "w3-retained-registry-v1.json");
            if (!Files.exists(path)) {
                path = Path.of(
                        "openspec/changes/wave3-attendance-setup-and-policies/"
                                + "specs/wave3-verification/oracles/"
                                + "w3-retained-registry-v1.json");
            }
            return new ObjectMapper().readTree(Files.readString(path));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "fixed W3 retained registry must be readable", exception);
        }
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative));
        } catch (Exception exception) {
            throw new IllegalStateException(relative + " must be readable", exception);
        }
    }
}
