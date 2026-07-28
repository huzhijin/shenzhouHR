package com.szsemicon.hr.evidenceingestion.infrastructure.synthetic;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "test"})
public class SyntheticDeliPunchAdapter implements DeliPunchSourcePort {

    @Override
    public DeliPage fetchPage(String sourceId, String committedCursor) {
        if ("cursor-1".equals(committedCursor)) {
            return new DeliPage(List.of(), "cursor-1", null, digestMarker("empty"));
        }
        DeliPunchRecord record = new DeliPunchRecord(
                "922337203685477580812345",
                "1",
                "SYNTHETIC-PERSON-001",
                ConfirmedBindingKind.DELI_EXT_ID,
                null,
                Instant.parse("2026-07-28T01:00:00Z"),
                "2026-07-28 09:00:00",
                "Asia/Shanghai",
                Direction.IN,
                "CARD",
                "SYNTHETIC-DEVICE-001",
                "SYNTHETIC-LOCATION",
                "UNKNOWN",
                true);
        return new DeliPage(
                List.of(record),
                committedCursor,
                "cursor-1",
                digestMarker(sourceId + ":cursor-1"));
    }

    private static String digestMarker(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
