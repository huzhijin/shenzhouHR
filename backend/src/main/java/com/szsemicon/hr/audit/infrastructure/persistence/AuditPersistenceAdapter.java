package com.szsemicon.hr.audit.infrastructure.persistence;

import com.szsemicon.hr.identityaccess.application.AuditPersistence;
import com.szsemicon.hr.identityaccess.application.AuditPersistence.AuditFilter;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AuditRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditPersistenceAdapter implements AuditPersistence {

    private final JdbcTemplate jdbc;

    public AuditPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void appendAudit(AuditRecord event) {
        appendAudit(event, null);
    }

    @Override
    public void appendVersionedAudit(
            AuditRecord event, String resourceVersion) {
        appendAudit(event, resourceVersion);
    }

    private void appendAudit(
            AuditRecord event, String resourceVersion) {
        jdbc.update(
                """
                INSERT INTO audit_event (
                    event_id, occurred_at, actor_id_ref, actor_type, action_code,
                    resource_type, resource_id_ref, result_code, reason_code,
                    policy_version, before_digest, after_digest,
                    correlation_id, request_id, event_hash
                ) VALUES (?, ?, ?, 'LOCAL_ACCOUNT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.eventId(),
                Timestamp.from(event.occurredAt()),
                event.actorId(),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                event.result(),
                truncate(event.reason(), 64),
                resourceVersion,
                event.beforeDigest(),
                event.afterDigest(),
                event.correlationId(),
                event.requestId(),
                event.eventHash());
    }

    @Override
    public List<AuditRecord> listAudit(AuditFilter filter, int limit, int offset) {
        String order = filter.ascending() ? "ASC" : "DESC";
        return jdbc.query(
                """
                SELECT event.event_id, event.occurred_at, event.actor_id_ref,
                       COALESCE(account.display_name, event.actor_id_ref) actor_display_name,
                       event.action_code, event.resource_type, event.resource_id_ref,
                       event.result_code, event.reason_code, event.correlation_id,
                       event.request_id, event.before_digest, event.after_digest, event.event_hash
                FROM audit_event event
                LEFT JOIN local_account account ON account.principal_id = event.actor_id_ref
                WHERE (? = '' OR LOWER(event.action_code) LIKE ?)
                  AND (? = '' OR event.result_code = ?)
                  AND (? = '' OR event.resource_type = ?)
                  AND (? = '' OR event.resource_id_ref = ?)
                ORDER BY event.occurred_at %s
                LIMIT ? OFFSET ?
                """.formatted(order),
                AuditPersistenceAdapter::mapAudit,
                filter.action().toLowerCase(),
                "%" + filter.action().toLowerCase() + "%",
                filter.result(),
                filter.result(),
                filter.resourceType(),
                filter.resourceType(),
                filter.resourceId(),
                filter.resourceId(),
                limit,
                offset);
    }

    @Override
    public long countAudit(AuditFilter filter) {
        Long value = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE (? = '' OR LOWER(action_code) LIKE ?)
                  AND (? = '' OR result_code = ?)
                  AND (? = '' OR resource_type = ?)
                  AND (? = '' OR resource_id_ref = ?)
                """,
                Long.class,
                filter.action().toLowerCase(),
                "%" + filter.action().toLowerCase() + "%",
                filter.result(),
                filter.result(),
                filter.resourceType(),
                filter.resourceType(),
                filter.resourceId(),
                filter.resourceId());
        return value == null ? 0 : value;
    }

    @Override
    public Optional<AuditRecord> findAudit(String eventId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT event.event_id, event.occurred_at, event.actor_id_ref,
                           COALESCE(account.display_name, event.actor_id_ref) actor_display_name,
                           event.action_code, event.resource_type, event.resource_id_ref,
                           event.result_code, event.reason_code, event.correlation_id,
                           event.request_id, event.before_digest, event.after_digest, event.event_hash
                    FROM audit_event event
                    LEFT JOIN local_account account ON account.principal_id = event.actor_id_ref
                    WHERE event.event_id = ?
                    """,
                    AuditPersistenceAdapter::mapAudit,
                    eventId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static AuditRecord mapAudit(ResultSet result, int row) throws SQLException {
        return new AuditRecord(
                result.getString("event_id"),
                result.getTimestamp("occurred_at").toInstant(),
                result.getString("actor_id_ref"),
                result.getString("actor_display_name"),
                result.getString("action_code"),
                result.getString("resource_type"),
                result.getString("resource_id_ref"),
                result.getString("result_code"),
                result.getString("reason_code"),
                result.getString("correlation_id"),
                result.getString("request_id"),
                result.getString("before_digest"),
                result.getString("after_digest"),
                result.getString("event_hash"));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
