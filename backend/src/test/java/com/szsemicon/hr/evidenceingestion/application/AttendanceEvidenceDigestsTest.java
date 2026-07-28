package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AttendanceEvidenceDigestsTest {

    @Test
    void varargsDigestTreatsNullFieldsAsEmptyWithoutFailing() {
        String varargs = AttendanceEvidenceDigests.sha256(
                "DELI_RAW_FACT_V1", null, "remaining-field");
        String list = AttendanceEvidenceDigests.sha256(Arrays.asList(
                "DELI_RAW_FACT_V1", "", "remaining-field"));

        assertThat(varargs)
                .matches("[0-9a-f]{64}")
                .isEqualTo(list);
    }
}
