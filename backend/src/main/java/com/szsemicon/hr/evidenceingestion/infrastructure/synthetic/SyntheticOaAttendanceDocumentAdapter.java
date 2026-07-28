package com.szsemicon.hr.evidenceingestion.infrastructure.synthetic;

import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "test"})
public class SyntheticOaAttendanceDocumentAdapter
        implements OaAttendanceDocumentSourcePort {

    @Override
    public OaPage fetchPage(String sourceId, String committedCursor) {
        if ("oa-cursor-1".equals(committedCursor)) {
            return new OaPage(List.of(), committedCursor, null, digest("empty"));
        }
        List<OaDocumentRecord> records = new ArrayList<>();
        int version = 1;
        for (SourceStatus status : SourceStatus.values()) {
            boolean effective = status == SourceStatus.APPROVED
                    || status == SourceStatus.MODIFIED
                    || status == SourceStatus.SUPPLEMENTED;
            records.add(new OaDocumentRecord(
                    "OA-SYNTHETIC-9223372036854775808",
                    Integer.toString(version++),
                    "OA-SYNTHETIC-PERSON-001",
                    "SYNTHETIC-E001",
                    status == SourceStatus.REVOKED
                            ? DocumentType.LEAVE_REVOCATION
                            : DocumentType.LEAVE,
                    status,
                    Instant.parse("2026-07-28T01:00:00Z"),
                    Instant.parse("2026-07-28T04:00:00Z"),
                    "Asia/Shanghai",
                    Instant.parse("2026-07-27T08:00:00Z"),
                    effective ? Instant.parse("2026-07-27T09:00:00Z") : null,
                    status == SourceStatus.MODIFIED
                            ? Instant.parse("2026-07-27T10:00:00Z") : null,
                    status == SourceStatus.REVOKED
                            ? Instant.parse("2026-07-27T11:00:00Z") : null,
                    "OA-SYNTHETIC-BATCH-001",
                    effective));
        }
        return new OaPage(
                records,
                committedCursor,
                "oa-cursor-1",
                digest(sourceId + ":oa-cursor-1"));
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
