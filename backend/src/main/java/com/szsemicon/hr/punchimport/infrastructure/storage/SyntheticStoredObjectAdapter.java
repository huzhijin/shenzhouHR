package com.szsemicon.hr.punchimport.infrastructure.storage;

import com.szsemicon.hr.punchimport.port.StoredObjectPort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "test"})
public class SyntheticStoredObjectAdapter implements StoredObjectPort {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public StoredObject store(InputStream content, long size, String contentType) {
        if (content == null || size < 1 || size > 20L * 1024L * 1024L) {
            throw new IllegalArgumentException("stored object size is outside policy");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            content.transferTo(output);
            byte[] bytes = output.toByteArray();
            if (bytes.length != size) {
                throw new IllegalArgumentException("stored object size does not match");
            }
            String reference = "synthetic-object:" + UUID.randomUUID();
            objects.put(reference, bytes.clone());
            return new StoredObject(
                    reference,
                    HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(bytes)),
                    bytes.length,
                    contentType);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("unable to store synthetic object", exception);
        }
    }

    @Override
    public InputStream open(String opaqueObjectReference) {
        byte[] bytes = objects.get(opaqueObjectReference);
        if (bytes == null) {
            throw new IllegalArgumentException("stored object is not available");
        }
        return new ByteArrayInputStream(bytes.clone());
    }
}
