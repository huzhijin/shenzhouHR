package com.szsemicon.hr.punchimport.infrastructure.storage;

import com.szsemicon.hr.punchimport.port.StoredObjectPort;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DatabaseStoredObjectAdapter implements StoredObjectPort {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DatabaseStoredObjectAdapter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public StoredObject store(InputStream content, long size, String contentType) {
        try {
            byte[] bytes = content.readAllBytes();
            if (bytes.length != size) {
                throw new IllegalArgumentException("stored object size does not match");
            }
            String sha = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            String ref = "db-object:" + UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO punch_stored_object (
                        stored_object_ref, content_sha256, content_type,
                        file_size_bytes, content, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    ref, sha, contentType, bytes.length, bytes,
                    java.sql.Timestamp.from(clock.instant()));
            return new StoredObject(ref, sha, bytes.length, contentType);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("unable to store punch object", exception);
        }
    }

    @Override
    public InputStream open(String opaqueObjectReference) {
        byte[] bytes = jdbc.queryForObject(
                "SELECT content FROM punch_stored_object WHERE stored_object_ref = ?",
                byte[].class,
                opaqueObjectReference);
        if (bytes == null) {
            throw new IllegalArgumentException("stored object not found");
        }
        return new ByteArrayInputStream(bytes);
    }
}
