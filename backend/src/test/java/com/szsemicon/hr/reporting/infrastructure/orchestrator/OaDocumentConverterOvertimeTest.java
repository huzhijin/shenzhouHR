package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OaDocumentConverterOvertimeTest {

    @Test
    void preserves_overtime_type_at_the_calculation_boundary() {
        var row = new OaDocumentRow(
                "oa-overtime-1",
                "OVERTIME",
                OvertimeType.COMPENSATORY,
                "E001",
                Instant.parse("2026-08-15T10:00:00Z"),
                Instant.parse("2026-08-15T12:00:00Z"),
                "Asia/Shanghai",
                Instant.parse("2026-08-15T12:00:00Z"),
                true);

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(row));

        assertThat(evidence).singleElement().satisfies(value -> {
            assertThat(value.kind()).isEqualTo(EvidenceKind.OVERTIME);
            assertThat(value.overtimeType())
                    .isEqualTo(OvertimeType.COMPENSATORY);
        });
    }

    @Test
    void dropsTripAndInactiveRowsButPreservesExemptionAndOuting() {
        Instant start = Instant.parse("2026-08-15T00:00:00Z");
        Instant end = Instant.parse("2026-08-16T00:00:00Z");
        var trip = row("trip-1", "TRIP", start, end, true);
        var inactiveOuting = row(
                "outing-inactive", "OUTING", start, end, false);
        var outing = row("outing-1", "OUTING", start, end, true);
        var exemption = row(
                "exemption-1", "EXEMPT_PUNCH", start, end, true);

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(
                trip, inactiveOuting, outing, exemption));

        assertThat(evidence)
                .extracting(value -> value.kind())
                .containsExactly(
                        EvidenceKind.OUTING,
                        EvidenceKind.EXEMPT_PUNCH);
        assertThat(evidence)
                .extracting(value -> value.evidenceId())
                .doesNotContain(
                        "oa:trip-1",
                        "oa:outing-inactive");
    }

    @Test
    void revocationCannotBeDroppedWhileOriginalLeaveRemainsFullyEffective() {
        Instant start = Instant.parse("2026-08-15T00:00:00Z");
        Instant end = Instant.parse("2026-08-16T00:00:00Z");
        var originalLeave = new OaDocumentRow(
                "leave-1", "LEAVE", null, LeaveType.SICK, "E001",
                start, end, "Asia/Shanghai", start, true);
        var leaveRevocation = row(
                "leave-revocation-1", "LEAVE_REVOCATION", start, end, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OaDocumentConverter.toIntervalEvidence(List.of(
                        originalLeave, leaveRevocation)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        OaDocumentConverter.UNRESOLVED_LEAVE_REVOCATION)
                .hasMessageContaining("resolved leave fragments are required");
    }

    @Test
    void preservesClassifiedLeaveTypeAndRejectsUnclassifiedLeave() {
        Instant start = Instant.parse("2026-08-15T00:00:00Z");
        Instant end = Instant.parse("2026-08-16T00:00:00Z");
        var sickLeave = new OaDocumentRow(
                "leave-1", "LEAVE", null, LeaveType.SICK, "E001",
                start, end, "Asia/Shanghai", start, true);
        var unknownLeave = row("leave-unknown", "LEAVE", start, end, true);

        assertThat(OaDocumentConverter.toIntervalEvidence(List.of(sickLeave)))
                .singleElement()
                .satisfies(value -> assertThat(value.leaveType())
                        .isEqualTo(LeaveType.SICK));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OaDocumentConverter.toIntervalEvidence(List.of(unknownLeave)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no classified leave type");
    }

    private static OaDocumentRow row(
            String key,
            String type,
            Instant start,
            Instant end,
            boolean effective) {
        return new OaDocumentRow(
                key,
                type,
                null,
                "E001",
                start,
                end,
                "Asia/Shanghai",
                start,
                effective);
    }
}
