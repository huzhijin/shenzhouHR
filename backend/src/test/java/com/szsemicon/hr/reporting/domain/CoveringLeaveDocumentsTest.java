package com.szsemicon.hr.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoveringLeaveDocumentsTest {

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "APPROVED"})
    void preservesIndependentLeaveEvenWhenApprovedIntervalsCoverIt(String status) {
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
                status);

        List<OaDocumentFact> kept = CoveringLeaveDocuments.distinctFacts(
                List.of(personal, timeOff, covering, covering));

        assertThat(kept)
                .extracting(OaDocumentFact::documentId)
                .containsExactlyInAnyOrder("personal", "time-off", "covering");
    }

    @Test
    void legacyCoveringFormIsDroppedButNetAllocatedRowsAreKeptEvenWithTheSameDocumentId() {
        Instant morning = Instant.parse("2026-09-08T00:30:00Z");
        Instant noon = Instant.parse("2026-09-08T04:00:00Z");
        Instant evening = Instant.parse("2026-09-08T09:30:00Z");
        OaDocumentFact am = fact("am", "PERSONAL", morning, noon, 210, "APPROVED");
        OaDocumentFact pm = fact("pm", "PERSONAL", Instant.parse("2026-09-08T05:00:00Z"), evening, 270, "APPROVED");
        OaDocumentFact legacyOuter = fact("outer", "PERSONAL", morning, evening, 480, "APPROVED");
        // Legacy rows: the old heuristic still drops the covering form.
        assertThat(CoveringLeaveDocuments.dropCoveringFacts(List.of(am, pm, legacyOuter)))
                .extracting(OaDocumentFact::documentId).containsExactlyInAnyOrder("am", "pm");
        // NET_WORK_V1 rows are already net: an outer form keeps its allocated
        // share (0 here), and a legacy row sharing its ID never removes it.
        OaDocumentFact netOuter = net(fact("outer", "PERSONAL", morning, evening, 0, "APPROVED"));
        OaDocumentFact netAm = net(am);
        OaDocumentFact netPm = net(pm);
        assertThat(CoveringLeaveDocuments.dropCoveringFacts(List.of(netAm, netPm, netOuter)))
                .containsExactlyInAnyOrder(netAm, netPm, netOuter);
        assertThat(CoveringLeaveDocuments.dropCoveringFacts(List.of(am, pm, legacyOuter, netOuter)))
                .containsExactlyInAnyOrder(am, pm, netOuter);
    }

    private static OaDocumentFact net(OaDocumentFact value) {
        return new OaDocumentFact(value.documentId(), value.employeeId(), value.employeeNumber(),
                value.employeeName(), value.organizationId(), value.organizationName(),
                value.documentType(), value.leaveType(), value.start(), value.endExclusive(),
                value.recognizedMinutes(), value.sourceStatus(), value.sourceVersion(),
                value.sourceOrigin(), OaDocumentFact.NET_WORK_V1);
    }

    @Test
    void keepsStandaloneUnknownLeave() {
        OaDocumentFact only = fact(
                "only",
                "PERSONAL",
                Instant.parse("2026-08-31T03:00:00Z"),
                Instant.parse("2026-08-31T04:00:00Z"),
                60,
                "PENDING");

        assertThat(CoveringLeaveDocuments.distinctFacts(List.of(only)))
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
