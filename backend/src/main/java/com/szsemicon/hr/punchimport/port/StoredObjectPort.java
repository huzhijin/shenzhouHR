package com.szsemicon.hr.punchimport.port;

import java.io.InputStream;

public interface StoredObjectPort {

    StoredObject store(InputStream content, long size, String contentType);

    InputStream open(String opaqueObjectReference);

    record StoredObject(
            String opaqueObjectReference,
            String sha256,
            long size,
            String contentType) {
    }
}
