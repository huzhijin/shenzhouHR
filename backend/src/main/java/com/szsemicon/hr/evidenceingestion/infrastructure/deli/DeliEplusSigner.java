package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class DeliEplusSigner {

    public String sign(
            String path,
            String timestamp,
            String appKey,
            String appSecret) {
        if (path == null || path.isBlank()
                || !path.startsWith("/")
                || path.contains("?")
                || path.contains("#")) {
            throw new IllegalArgumentException("Deli E+ signature path is invalid");
        }
        if (timestamp == null || !timestamp.matches("[0-9]{13}")) {
            throw new IllegalArgumentException(
                    "Deli E+ signature timestamp must contain 13 digits");
        }
        if (appKey == null || appKey.isBlank()
                || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("Deli E+ signing credentials are required");
        }
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(
                    (path + timestamp + appKey + appSecret)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required Deli E+ signing algorithm is unavailable");
        }
    }
}
