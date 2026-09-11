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
    void dropsInactiveRowsButPreservesTripOutingAndExemption() {
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
                        EvidenceKind.TRIP,
                        EvidenceKind.OUTING,
                        EvidenceKind.EXEMPT_PUNCH);
        assertThat(evidence)
                .extracting(value -> value.evidenceId())
                .doesNotContain("oa:outing-inactive")
                .contains("oa:trip-1");
    }

    @Test
    void revocationReplacesOriginalLeaveWithActualInterval() {
        Instant requestedStart = Instant.parse("2026-08-15T00:00:00Z");
        Instant requestedEnd = Instant.parse("2026-08-20T00:00:00Z");
        Instant actualStart = Instant.parse("2026-08-15T00:00:00Z");
        Instant actualEnd = Instant.parse("2026-08-17T00:00:00Z");
        var originalLeave = new OaDocumentRow(
                "leave-1", "LEAVE", null, LeaveType.SICK, "E001",
                requestedStart, requestedEnd, "Asia/Shanghai", requestedStart,
                true, "L-100", null);
        var leaveRevocation = new OaDocumentRow(
                "leave-revocation-1", "LEAVE_REVOCATION", null, null, "E001",
                actualStart, actualEnd, "Asia/Shanghai", actualStart,
                true, null, "L-100");

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(
                originalLeave, leaveRevocation));

        assertThat(evidence).singleElement().satisfies(value -> {
            assertThat(value.kind()).isEqualTo(EvidenceKind.LEAVE);
            assertThat(value.leaveType()).isEqualTo(LeaveType.SICK);
            assertThat(value.interval().start()).isEqualTo(actualStart);
            assertThat(value.interval().end()).isEqualTo(actualEnd);
        });
    }

    @Test
    void multipleRevocationsUnionOverlappingActualIntervals() {
        Instant requestedStart = Instant.parse("2026-08-01T00:00:00Z");
        Instant requestedEnd = Instant.parse("2026-08-31T00:00:00Z");
        var originalLeave = new OaDocumentRow(
                "leave-1", "LEAVE", null, LeaveType.ANNUAL, "E001",
                requestedStart, requestedEnd, "Asia/Shanghai", requestedStart,
                true, "L-200", null);
        var first = new OaDocumentRow(
                "rev-1", "LEAVE_REVOCATION", null, null, "E001",
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-12T00:00:00Z"),
                "Asia/Shanghai", requestedStart, true, null, "L-200");
        var overlap = new OaDocumentRow(
                "rev-2", "LEAVE_REVOCATION", null, null, "E001",
                Instant.parse("2026-08-11T00:00:00Z"),
                Instant.parse("2026-08-13T00:00:00Z"),
                "Asia/Shanghai", requestedStart, true, null, "L-200");
        var separate = new OaDocumentRow(
                "rev-3", "LEAVE_REVOCATION", null, null, "E001",
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"),
                "Asia/Shanghai", requestedStart, true, null, "L-200");

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(
                originalLeave, first, overlap, separate));

        assertThat(evidence)
                .extracting(value -> value.interval().start().toString()
                        + "/" + value.interval().end())
                .containsExactly(
                        "2026-08-10T00:00:00Z/2026-08-13T00:00:00Z",
                        "2026-08-20T00:00:00Z/2026-08-21T00:00:00Z");
        assertThat(evidence)
                .allMatch(value -> value.kind() == EvidenceKind.LEAVE);
    }

    @Test
    void matchesRevocationByEmployeeNumberWhenSerialsMissing() {
        Instant requestedStart = Instant.parse("2026-08-15T00:00:00Z");
        Instant requestedEnd = Instant.parse("2026-08-20T00:00:00Z");
        Instant actualStart = Instant.parse("2026-08-15T00:00:00Z");
        Instant actualEnd = Instant.parse("2026-08-16T00:00:00Z");
        var originalLeave = new OaDocumentRow(
                "leave-1", "LEAVE", null, LeaveType.PERSONAL, "E001",
                requestedStart, requestedEnd, "Asia/Shanghai", requestedStart,
                true, null, null);
        var leaveRevocation = new OaDocumentRow(
                "leave-revocation-1", "LEAVE_REVOCATION", null, null, "E001",
                actualStart, actualEnd, "Asia/Shanghai", actualStart,
                true, null, null);

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(
                originalLeave, leaveRevocation));

        assertThat(evidence).singleElement().satisfies(value -> {
            assertThat(value.kind()).isEqualTo(EvidenceKind.LEAVE);
            assertThat(value.interval().start()).isEqualTo(actualStart);
            assertThat(value.interval().end()).isEqualTo(actualEnd);
        });
    }

    @Test
    void prefersSerialMatchOverSameEmployeeNumber() {
        Instant requestedStart = Instant.parse("2026-08-01T00:00:00Z");
        Instant requestedEnd = Instant.parse("2026-08-10T00:00:00Z");
        var leave = new OaDocumentRow(
                "leave-1", "LEAVE", null, LeaveType.ANNUAL, "E001",
                requestedStart, requestedEnd, "Asia/Shanghai", requestedStart,
                true, "L-300", null);
        var matching = new OaDocumentRow(
                "rev-match", "LEAVE_REVOCATION", null, null, "E001",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z"),
                "Asia/Shanghai", requestedStart, true, null, "L-300");
        var otherEmployeeSerial = new OaDocumentRow(
                "rev-other", "LEAVE_REVOCATION", null, null, "E001",
                Instant.parse("2026-08-05T00:00:00Z"),
                Instant.parse("2026-08-06T00:00:00Z"),
                "Asia/Shanghai", requestedStart, true, null, "L-OTHER");

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(
                leave, matching, otherEmployeeSerial));

        assertThat(evidence).singleElement().satisfies(value -> {
            assertThat(value.interval().start())
                    .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
            assertThat(value.interval().end())
                    .isEqualTo(Instant.parse("2026-08-03T00:00:00Z"));
        });
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
