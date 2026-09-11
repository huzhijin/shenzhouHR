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
        String componentNames = Arrays.stream(
                        VerifiedOaDocumentFact.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + " " + right);

        assertThat(componentNames)
                .doesNotContain(
                        "reason",
                        "location",
                        "coordinate",
                        "longitude",
                        "latitude",
                        "device",
                        "raw",
                        "payload",
                        "destination");
    }
}
