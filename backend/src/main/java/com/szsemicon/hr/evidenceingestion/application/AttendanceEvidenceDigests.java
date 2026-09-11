package com.szsemicon.hr.evidenceingestion.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

final class AttendanceEvidenceDigests {

    private AttendanceEvidenceDigests() {
    }

    static String sha256(String... fields) {
        return sha256(Arrays.asList(fields));
    }

    static String sha256(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                String safe = field == null ? "" : field;
                byte[] value = safe.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(value.length)
                        .array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "required digest algorithm is unavailable");
        }
    }
}
