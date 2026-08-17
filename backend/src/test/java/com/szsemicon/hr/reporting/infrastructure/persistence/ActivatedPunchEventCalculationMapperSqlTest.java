package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivatedPunchEventCalculationMapperSqlTest {

    private static final Pattern ACTIVATED_PUNCH_QUERY = Pattern.compile(
            "<select id=\"findActivatedPunchEvents\"[^>]*>(.*?)</select>",
            Pattern.DOTALL);
    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");

    private Connection connection;

    @BeforeEach
    void createSchema() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:activated-punch-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        execute("""
                CREATE TABLE effective_attendance_event (
                    effective_attendance_event_id VARCHAR(36) PRIMARY KEY,
                    company_id VARCHAR(36) NOT NULL,
                    employee_id VARCHAR(36) NOT NULL,
                    event_kind VARCHAR(32) NOT NULL,
                    point_instant TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """);
        execute("""
                CREATE TABLE effective_event_lifecycle_fact (
                    effective_event_lifecycle_fact_id VARCHAR(36) PRIMARY KEY,
                    effective_attendance_event_id VARCHAR(36) NOT NULL,
                    lifecycle_type VARCHAR(32) NOT NULL,
                    knowledge_at TIMESTAMP NOT NULL
                )
                """);
    }

    @AfterEach
    void closeConnection() throws Exception {
        connection.close();
    }

    @Test
    void lifecycleLearnedAfterCutoffDoesNotRewriteHistoricalResult()
            throws Exception {
        insertEvent(
                "event-1",
                "employee-1",
                "2026-08-15 09:00:00",
                "2026-08-15 09:01:00");
        insertLifecycle(
                "lifecycle-activate",
                "event-1",
                "ACTIVATED",
                "2026-08-15 09:02:00");
        insertLifecycle(
                "lifecycle-retract",
                "event-1",
                "RETRACTED",
                "2026-08-18 09:00:00");

        assertThat(employeeIdsAt("2026-08-17 10:00:00"))
                .containsExactly("employee-1");
        assertThat(employeeIdsAt("2026-08-19 10:00:00")).isEmpty();
    }

    @Test
    void punchWindowRemainsHalfOpen() throws Exception {
        insertActivatedEvent(
                "event-start",
                "employee-start",
                "2026-08-01 00:00:00");
        insertActivatedEvent(
                "event-end",
                "employee-end",
                "2026-09-01 00:00:00");

        assertThat(employeeIdsAt("2026-09-02 00:00:00"))
                .containsExactly("employee-start");
    }

    @Test
    void eventCreatedAfterCutoffIsExcluded() throws Exception {
        insertEvent(
                "event-future",
                "employee-future",
                "2026-08-15 09:00:00",
                "2026-08-18 09:00:00");
        insertLifecycle(
                "lifecycle-future-activate",
                "event-future",
                "ACTIVATED",
                "2026-08-16 09:00:00");

        assertThat(employeeIdsAt("2026-08-17 10:00:00")).isEmpty();
    }

    private List<String> employeeIdsAt(String dataAsOf) throws Exception {
        Timestamp cutoff = Timestamp.valueOf(dataAsOf);
        try (PreparedStatement statement = connection.prepareStatement(
                activatedPunchSql())) {
            statement.setString(1, "company-1");
            statement.setTimestamp(2, cutoff);
            statement.setTimestamp(3, Timestamp.valueOf("2026-08-01 00:00:00"));
            statement.setTimestamp(4, Timestamp.valueOf("2026-09-01 00:00:00"));
            statement.setTimestamp(5, cutoff);
            try (ResultSet result = statement.executeQuery()) {
                List<String> employeeIds = new ArrayList<>();
                while (result.next()) {
                    employeeIds.add(result.getString("employee_id"));
                }
                return employeeIds;
            }
        }
    }

    private void insertActivatedEvent(
            String eventId, String employeeId, String pointInstant)
            throws Exception {
        insertEvent(eventId, employeeId, pointInstant, "2026-07-31 00:00:00");
        insertLifecycle(
                "activate-" + eventId,
                eventId,
                "ACTIVATED",
                "2026-07-31 00:01:00");
    }

    private void insertEvent(
            String eventId,
            String employeeId,
            String pointInstant,
            String createdAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO effective_attendance_event (
                    effective_attendance_event_id, company_id, employee_id,
                    event_kind, point_instant, created_at
                ) VALUES (?, 'company-1', ?, 'PUNCH_POINT', ?, ?)
                """)) {
            statement.setString(1, eventId);
            statement.setString(2, employeeId);
            statement.setTimestamp(3, Timestamp.valueOf(pointInstant));
            statement.setTimestamp(4, Timestamp.valueOf(createdAt));
            statement.executeUpdate();
        }
    }

    private void insertLifecycle(
            String lifecycleId,
            String eventId,
            String lifecycleType,
            String knowledgeAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO effective_event_lifecycle_fact (
                    effective_event_lifecycle_fact_id,
                    effective_attendance_event_id,
                    lifecycle_type,
                    knowledge_at
                ) VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, lifecycleId);
            statement.setString(2, eventId);
            statement.setString(3, lifecycleType);
            statement.setTimestamp(4, Timestamp.valueOf(knowledgeAt));
            statement.executeUpdate();
        }
    }

    private static String activatedPunchSql() throws Exception {
        String mapper = Files.readString(CALCULATION_MAPPER);
        var matcher = ACTIVATED_PUNCH_QUERY.matcher(mapper);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1)
                .replace("&lt;=", "<=")
                .replace("&gt;=", ">=")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("#{companyId}", "?")
                .replace("#{dataAsOf}", "?")
                .replace("#{windowStart}", "?")
                .replace("#{windowEndExclusive}", "?");
    }

    private void execute(String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
