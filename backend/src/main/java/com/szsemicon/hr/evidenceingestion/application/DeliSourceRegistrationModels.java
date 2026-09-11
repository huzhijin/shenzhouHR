package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;

public final class DeliSourceRegistrationModels {

    private DeliSourceRegistrationModels() {
    }

    public record Command(
            String companyId,
            String sourceCode,
            String displayName,
            String sourceTimeZone,
            int pageSize,
            int rateLimitPerMinute,
            int backoffSeconds,
            String secretReferenceName,
            String reason) {
    }

    public record SourceView(
            String sourceId,
            String companyId,
            String sourceCode,
            String displayName,
            String sourceType,
            String status,
            String endpointKind,
            String sourceTimeZone,
            int pageSize,
            int rateLimitPerMinute,
            int backoffSeconds,
            String secretReferenceName,
            int configurationRevision,
            Instant effectiveFrom,
            long rowVersion,
            boolean replayed) {

        public SourceView asReplay() {
            return new SourceView(
                    sourceId,
                    companyId,
                    sourceCode,
                    displayName,
                    sourceType,
                    status,
                    endpointKind,
                    sourceTimeZone,
                    pageSize,
                    rateLimitPerMinute,
                    backoffSeconds,
                    secretReferenceName,
                    configurationRevision,
                    effectiveFrom,
                    rowVersion,
                    true);
        }
    }
}
