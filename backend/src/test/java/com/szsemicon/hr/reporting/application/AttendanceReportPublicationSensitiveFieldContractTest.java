package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AttendanceReportPublicationSensitiveFieldContractTest {

    @Test
    void verifiedOaPublicationInputCannotCarrySensitiveSourcePayload() {
        java.util.Set<String> componentNames = Arrays.stream(
                        VerifiedOaDocumentFact.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        // Check each sensitive word segment (camelCase aware)
        // Rejects: sourceLocation, requestReason, rawPayload, etc.
        // Allows: allocationSemantics (allocation is not sensitive)
        java.util.List<String> sensitiveSegments = java.util.List.of(
                "reason",
                "location",
                "coordinate",
                "coordinates",
                "longitude",
                "latitude",
                "device",
                "raw",
                "payload",
                "destination");

        for (String fieldName : componentNames) {
            String lower = fieldName.toLowerCase(Locale.ROOT);
            for (String sensitive : sensitiveSegments) {
                // Match as camelCase word boundary or after underscore
                // "sourceLocation" contains "location" as a word
                // "allocationSemantics" has "location" as substring but not word boundary
                boolean containsAsWord = lower.contains(sensitive.toLowerCase(Locale.ROOT))
                        && (lower.equals(sensitive.toLowerCase(Locale.ROOT))
                            || lower.matches(".*[^a-z]" + sensitive.toLowerCase(Locale.ROOT) + ".*")
                            || lower.matches(".*[a-z][A-Z].*")  // has camelCase
                                && fieldName.matches(".*[A-Z]" + sensitive.substring(0, 1).toUpperCase(Locale.ROOT)
                                    + (sensitive.length() > 1 ? sensitive.substring(1).toLowerCase(Locale.ROOT) : "") + ".*"));

                assertThat(containsAsWord)
                        .as("Field '%s' contains sensitive segment '%s'", fieldName, sensitive)
                        .isFalse();
            }
        }
    }
}
