package com.szsemicon.hr.evidenceingestion.port;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import java.time.Instant;
import java.util.List;

public interface DeliPunchSourcePort {

    DeliPage fetchPage(String sourceId, String committedCursor);

    record DeliPage(
            List<DeliPunchRecord> records,
            String inputCursor,
            String nextCursor,
            String pageDigest) {

        public DeliPage {
            records = List.copyOf(records);
        }
    }

    record DeliPunchRecord(
            String sourceRecordId,
            String sourceVersion,
            String externalPersonRef,
            String employeeNumber,
            Instant punchInstant,
            String originalTimeText,
            String sourceTimeZone,
            Direction direction,
            String verificationMethod,
            String deviceRef,
            String locationSummary,
            String coordinateSystemTag,
            boolean forbiddenPayloadDropped) {
    }
}
