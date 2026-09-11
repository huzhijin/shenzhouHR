package com.szsemicon.hr.evidenceingestion.port;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

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

    /**
     * Returns a snapshot of userId-to-employeeNumber mappings obtained from the
     * Deli E+ employee directory before the paging loop begins. An empty map is
     * valid only when a complete vendor response contains no usable mappings.
     * Request, protocol and validation failures must be propagated as a
     * classified {@link FetchException}; callers must stop before fetching
     * check-in pages.
     */
    default Map<String, String> fetchEmployeeDirectory(String sourceId) {
        return Map.of();
    }

    /**
     * Directory rows with snowflake user id, empno and display name.
     * Default derives from {@link #fetchEmployeeDirectory} without names.
     */
    default List<EmployeeDirectoryPerson> fetchEmployeeDirectoryPeople(
            String sourceId) {
        return fetchEmployeeDirectory(sourceId).entrySet().stream()
                .map(entry -> new EmployeeDirectoryPerson(
                        entry.getKey(), entry.getValue(), null))
                .toList();
    }

    record EmployeeDirectoryPerson(
            String userId, String employeeNum, String displayName) {
    }

    /**
     * Returns the Deli department directory used to confirm the live org
     * tree. Empty is valid only when the vendor returned no usable rows.
     * Failures must be classified {@link FetchException}s.
     */
    default List<DeliEplusDepartment> fetchDepartmentDirectory(String sourceId) {
        return List.of();
    }

    record DeliEplusDepartment(
            String departmentId, String name, String parentId) {
    }

    DeliPage fetchPage(String sourceId, String committedCursor);

    default DeliPage fetchPage(
            String sourceId,
            String committedCursor,
            FetchSettings settings) {
        return fetchPage(sourceId, committedCursor);
    }

    record FetchSettings(
            int pageSize,
            ZoneId sourceTimeZone,
            Map<String, String> employeeDirectory,
            String apiModule,
            boolean skipUnreadableRecords) {

        public static final String MODULE_CHECKIN = "CHECKIN";
        public static final String MODULE_KQ = "KQ";

        public FetchSettings {
            if (pageSize < 1 || pageSize > 500) {
                throw new IllegalArgumentException(
                        "Deli page size must be between 1 and 500");
            }
            if (sourceTimeZone == null) {
                throw new IllegalArgumentException(
                        "Deli source time zone is required");
            }
            employeeDirectory = (employeeDirectory != null)
                    ? Map.copyOf(employeeDirectory)
                    : Map.of();
            if (apiModule == null || apiModule.isBlank()) {
                apiModule = MODULE_CHECKIN;
            }
            apiModule = apiModule.trim();
            if (!MODULE_CHECKIN.equals(apiModule)
                    && !MODULE_KQ.equals(apiModule)) {
                throw new IllegalArgumentException(
                        "Deli api module must be CHECKIN or KQ");
            }
        }

        /** Convenience constructor for callers that carry no directory. */
        public FetchSettings(int pageSize, ZoneId sourceTimeZone) {
            this(pageSize, sourceTimeZone, Map.of(), MODULE_CHECKIN, false);
        }

        public FetchSettings(
                int pageSize,
                ZoneId sourceTimeZone,
                Map<String, String> employeeDirectory) {
            this(pageSize, sourceTimeZone, employeeDirectory, MODULE_CHECKIN, false);
        }

        public FetchSettings(
                int pageSize,
                ZoneId sourceTimeZone,
                Map<String, String> employeeDirectory,
                String apiModule) {
            this(pageSize, sourceTimeZone, employeeDirectory, apiModule, false);
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
            String memberName,
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
