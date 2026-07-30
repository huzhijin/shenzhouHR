package com.szsemicon.hr.evidenceingestion.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

public final class AttendanceSourceModels {

    private AttendanceSourceModels() {
    }

    public enum SourceStatus {
        ACTIVE,
        INACTIVE
    }

    public enum JobStatus {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        PARTIALLY_QUARANTINED,
        FAILED,
        CANCELLED
    }

    public record AttendanceSource(
            String sourceId,
            String companyId,
            String code,
            EvidenceLedger.SourceType sourceType,
            String displayName,
            SourceStatus status,
            long rowVersion) {

        public AttendanceSource {
            requireText(sourceId, "sourceId");
            requireText(companyId, "companyId");
            requireText(code, "code");
            Objects.requireNonNull(sourceType, "sourceType");
            requireText(displayName, "displayName");
            Objects.requireNonNull(status, "status");
            if (rowVersion < 0) {
                throw new IllegalArgumentException("rowVersion cannot be negative");
            }
        }
    }

    public record SourceConfigurationRevision(
            String configurationRevisionId,
            String sourceId,
            int revisionNumber,
            String endpointKind,
            ZoneId sourceTimeZone,
            int pageSize,
            int rateLimitPerMinute,
            int backoffSeconds,
            String secretReferenceName,
            Map<String, String> adapterSettings,
            Instant effectiveFrom,
            String snapshotDigest) {

        public SourceConfigurationRevision {
            requireText(configurationRevisionId, "configurationRevisionId");
            requireText(sourceId, "sourceId");
            requireText(endpointKind, "endpointKind");
            Objects.requireNonNull(sourceTimeZone, "sourceTimeZone");
            adapterSettings = Map.copyOf(adapterSettings);
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            requireText(snapshotDigest, "snapshotDigest");
            if (revisionNumber < 1 || pageSize < 1 || pageSize > 1000
                    || rateLimitPerMinute < 1 || backoffSeconds < 0) {
                throw new IllegalArgumentException("invalid source configuration limits");
            }
            if (secretReferenceName != null
                    && !secretReferenceName.matches("[A-Z][A-Z0-9_]{2,127}")) {
                throw new IllegalArgumentException(
                        "secret reference must be a repository-external key name");
            }
            if (adapterSettings.keySet().stream().anyMatch(key ->
                    key.toLowerCase(java.util.Locale.ROOT).contains("password")
                            || key.toLowerCase(java.util.Locale.ROOT).contains("secret")
                            || key.toLowerCase(java.util.Locale.ROOT).contains("token")
                            || key.toLowerCase(java.util.Locale.ROOT).contains("credential"))) {
                throw new IllegalArgumentException(
                        "adapter settings cannot contain credential values");
            }
        }
    }

    public record SourceJob(
            String jobId,
            String sourceId,
            JobStatus status,
            int pageCount,
            int acceptedCount,
            int quarantinedCount,
            String committedWatermark,
            String safeErrorCode,
            String correlationId,
            long rowVersion) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
