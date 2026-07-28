package com.szsemicon.hr.evidenceingestion.port;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

public interface DeliPunchSourcePort {

    /**
     * A source adapter must classify failures before the application service
     * decides whether another vendor call is safe. Protocol, authentication,
     * mapping and validation failures are never retried blindly.
     */
    class FetchException extends RuntimeException {

        private final String safeCode;
        private final boolean retryable;

        public FetchException(
                String safeCode,
                String safeMessage,
                boolean retryable) {
            super(safeMessage);
            this.safeCode = safeCode;
            this.retryable = retryable;
        }

        public String safeCode() {
            return safeCode;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /**
     * Formal synchronization must never silently fall back to a development
     * or contract-test source.
     */
    default boolean productionIntegration() {
        return false;
    }

    default String credentialReferenceName() {
        return null;
    }

    DeliPage fetchPage(String sourceId, String committedCursor);

    default DeliPage fetchPage(
            String sourceId,
            String committedCursor,
            FetchSettings settings) {
        return fetchPage(sourceId, committedCursor);
    }

    record FetchSettings(int pageSize, ZoneId sourceTimeZone) {

        public FetchSettings {
            if (pageSize < 1 || pageSize > 500) {
                throw new IllegalArgumentException(
                        "Deli page size must be between 1 and 500");
            }
            if (sourceTimeZone == null) {
                throw new IllegalArgumentException(
                        "Deli source time zone is required");
            }
        }
    }

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
            EmployeeEmploymentResolverPort.ConfirmedBindingKind
                    externalPersonRefKind,
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
