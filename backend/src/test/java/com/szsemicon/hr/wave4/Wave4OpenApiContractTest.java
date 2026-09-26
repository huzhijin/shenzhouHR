package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Wave4OpenApiContractTest {

    private static final String DOCUMENT = readDocument();

    @Test
    void allRequiredW4OperationFamiliesAreFrozen() {
        List<String> paths = List.of(
                "/attendance-sources:",
                "/attendance-sources/{sourceId}:",
                "/attendance-sources/{sourceId}/watermark:",
                "/attendance-sources/{sourceId}/devices:",
                "/attendance-sources/{sourceId}/device-person-bindings:",
                "/attendance-sources/{sourceId}/documents:",
                "/attendance-sources/{sourceId}/quarantine:",
                "/attendance-source-jobs:",
                "/attendance-source-jobs/{jobId}:",
                "/attendance-source-jobs/{jobId}/retry:",
                "/attendance-punch-mapping-profiles:",
                "/attendance-punch-mapping-profiles/{profileId}/versions:",
                "/attendance-punch-imports/template:",
                "/attendance-punch-imports:",
                "/attendance-punch-imports/{batchId}:",
                "/attendance-punch-imports/{batchId}/mapping:",
                "/attendance-punch-imports/{batchId}/precheck:",
                "/attendance-punch-imports/{batchId}/preview:",
                "/attendance-punch-imports/{batchId}/errors:",
                "/attendance-punch-imports/{batchId}/error-report:",
                "/attendance-punch-imports/{batchId}/file:",
                "/attendance-punch-imports/{batchId}/rows:",
                "/attendance-punch-imports/{batchId}/rows/{rowId}:",
                "/attendance-punch-imports/{batchId}/publish:",
                "/attendance-punch-imports/{batchId}/void-or-reverse:",
                "/attendance-punch-imports/{batchId}/recalculation-intents:",
                "/attendance-events/{eventId}/evidence:",
                "/attendance-duplicate-reviews:",
                "/attendance-duplicate-reviews/{groupId}:",
                "/attendance-duplicate-reviews/{groupId}/resolve:");
        paths.forEach(path -> assertThat(DOCUMENT).as(path)
                .contains("\n  " + path));
    }

    @Test
    void enumsAndMutationHeadersAreClosed() {
        assertThat(DOCUMENT).contains(
                "VALIDATION_FAILED",
                "BLOCKED_BY_FROZEN_PERIOD",
                "PUBLISH_FAILED",
                "PARTIALLY_PUBLISHED",
                "PARTIALLY_QUARANTINED",
                "SAME_FACT",
                "DISTINCT_FACTS",
                "Idempotency-Key",
                "X-Change-Reason",
                "If-Match",
                "X-Correlation-ID",
                "additionalProperties: false");
    }

    private static String readDocument() {
        try {
            return Files.readString(
                    Path.of("../api/openapi.yaml").toAbsolutePath().normalize());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
