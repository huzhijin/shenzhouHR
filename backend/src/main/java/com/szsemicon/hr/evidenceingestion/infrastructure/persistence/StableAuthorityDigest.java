package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class StableAuthorityDigest {

    private StableAuthorityDigest() {
    }

    static String sha256(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = (field == null ? "" : field)
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(value.length)
                        .array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "required digest algorithm is unavailable", exception);
        }
    }
}
