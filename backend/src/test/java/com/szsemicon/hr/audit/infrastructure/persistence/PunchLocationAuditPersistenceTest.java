package com.szsemicon.hr.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AuditRecord;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PunchLocationAuditPersistenceTest {
    @Test
    void retainsFullLocationReasonAndEmployeeWithoutChangingOtherAuditLimits() throws Exception {
        var datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:location-audit;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE TABLE audit_event(event_id VARCHAR, occurred_at TIMESTAMP, actor_id_ref VARCHAR, actor_type VARCHAR, action_code VARCHAR, resource_type VARCHAR, resource_id_ref VARCHAR, result_code VARCHAR, reason_code VARCHAR(64), policy_version VARCHAR, before_digest VARCHAR, after_digest VARCHAR, correlation_id VARCHAR, request_id VARCHAR, event_hash VARCHAR)");
        jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V70__punch_location_audit_reason.sql")));
        var repository = new AuditPersistenceAdapter(jdbc);
        String reason = "employeeId=employee; reason=" + "核".repeat(500);
        repository.appendAudit(event("PUNCH_LOCATION_READ", reason));
        repository.appendAudit(event("OTHER_ACTION", "x".repeat(100)));
        assertThat(jdbc.queryForObject("SELECT reason_code FROM audit_event WHERE action_code='PUNCH_LOCATION_READ'", String.class)).isEqualTo(reason);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM audit_event WHERE action_code='OTHER_ACTION'", String.class)).hasSize(64);
    }

    private AuditRecord event(String action, String reason) {
        return new AuditRecord(UUID.randomUUID().toString(), Instant.now(), "actor", "actor", action,
                "RAW_ATTENDANCE_FACT", "fact", "ALLOWED", reason, "correlation", "request", null, null, "digest");
    }
}
