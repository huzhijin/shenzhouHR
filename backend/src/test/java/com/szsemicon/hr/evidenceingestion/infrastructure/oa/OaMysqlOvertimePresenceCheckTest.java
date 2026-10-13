package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OvertimeDocumentPresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OaMysqlOvertimePresenceCheckTest {

    private String url;
    private OaMysqlAttendanceDocumentAdapter adapter;
    private static final String SHARED_SOURCE_ID = "e05866a7-97b0-11f1-8924-00155d09fd0e";

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:oa-presence-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        adapter = new OaMysqlAttendanceDocumentAdapter(() -> DriverManager.getConnection(url), new OaMysqlProperties());

        try (Connection conn = DriverManager.getConnection(url)) {
            conn.createStatement().execute("CREATE TABLE formson_0172 (id BIGINT PRIMARY KEY)");
        }
    }

    @Test
    void checkOvertimeDocumentPresence_returnsAbsentWhenRowDoesNotExist() {
        long nonExistentId = 9999999999999L;

        OvertimeDocumentPresence result = adapter.checkOvertimeDocumentPresence(
                SHARED_SOURCE_ID, nonExistentId);

        assertThat(result).isEqualTo(OvertimeDocumentPresence.ABSENT);
    }

    @Test
    void checkOvertimeDocumentPresence_returnsPresentWhenRowExists() throws SQLException {
        long testDetailId = 7777777777777L;

        try (Connection conn = DriverManager.getConnection(url)) {
            conn.createStatement().execute("INSERT INTO formson_0172 (id) VALUES (" + testDetailId + ")");
        }

        OvertimeDocumentPresence result = adapter.checkOvertimeDocumentPresence(
                SHARED_SOURCE_ID, testDetailId);

        assertThat(result).isEqualTo(OvertimeDocumentPresence.PRESENT);
    }

    @Test
    void checkOvertimeDocumentPresence_returnsUnsupportedForWrongSource() {
        OvertimeDocumentPresence result = adapter.checkOvertimeDocumentPresence(
                "wrong-source-id", 123456L);

        assertThat(result).isEqualTo(OvertimeDocumentPresence.UNSUPPORTED);
    }

    @Test
    void checkOvertimeDocumentPresence_returnsErrorOnConnectionFailure() {
        OaMysqlAttendanceDocumentAdapter brokenAdapter = new OaMysqlAttendanceDocumentAdapter(
                () -> { throw new SQLException("Connection failed"); },
                new OaMysqlProperties());

        OvertimeDocumentPresence result = brokenAdapter.checkOvertimeDocumentPresence(
                SHARED_SOURCE_ID, 123456L);

        assertThat(result).isEqualTo(OvertimeDocumentPresence.ERROR);
    }
}
