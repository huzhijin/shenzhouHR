package com.szsemicon.hr.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoveringLeaveDocumentsTest {

    @Test
    void dropsUnknownPersonalHeaderThatUnionsCompensatoryAndPersonal() {
        OaDocumentFact personal = fact(
                "personal",
                "PERSONAL",
                Instant.parse("2026-08-31T03:00:00Z"),
                Instant.parse("2026-08-31T04:00:00Z"),
                60,
                "APPROVED");
        OaDocumentFact timeOff = fact(
                "time-off",
                "TIME_OFF",
                Instant.parse("2026-08-31T00:30:00Z"),
                Instant.parse("2026-08-31T03:00:00Z"),
                150,
                "APPROVED");
        OaDocumentFact covering = fact(
                "covering",
                "PERSONAL",
                Instant.parse("2026-08-31T00:30:00Z"),
                Instant.parse("2026-08-31T04:00:00Z"),
                210,
                "UNKNOWN");

        List<OaDocumentFact> kept = CoveringLeaveDocuments.dropCoveringFacts(
                List.of(personal, timeOff, covering));

        assertThat(kept)
                .extracting(OaDocumentFact::documentId)
                .containsExactlyInAnyOrder("personal", "time-off");
    }

    @Test
    void keepsStandaloneUnknownLeave() {
        OaDocumentFact only = fact(
                "only",
                "PERSONAL",
                Instant.parse("2026-08-31T03:00:00Z"),
                Instant.parse("2026-08-31T04:00:00Z"),
                60,
                "UNKNOWN");

        assertThat(CoveringLeaveDocuments.dropCoveringFacts(List.of(only)))
                .containsExactly(only);
    }

    private static OaDocumentFact fact(
            String id,
            String leaveType,
            Instant start,
            Instant end,
            long minutes,
            String status) {
        return new OaDocumentFact(
                id,
                "employee-1",
                "SZST0548",
                "张晓冬",
                "org-1",
                "工程一部-RPS组",
                "LEAVE",
                leaveType,
                start,
                end,
                minutes,
                status,
                "oa-v1");
    }
}
