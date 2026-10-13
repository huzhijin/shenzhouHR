package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.DeliveryMode;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportJob;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportStatus;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class MyBatisAttendanceReportExportStoreTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void synchronousInsertSeparatesAndDefensivelyCopiesArtifact() {
        AttendanceReportExportMapper mapper =
                mock(AttendanceReportExportMapper.class);
        var store = new MyBatisAttendanceReportExportStore(
                mapper, new ObjectMapper());
        byte[] content = "formal-report".getBytes(StandardCharsets.UTF_8);
        ExportJob job = readyJob(content);
        ArgumentCaptor<byte[]> storedContent =
                ArgumentCaptor.forClass(byte[].class);

        store.insert(job, content);
        content[0] = 0;

        verify(mapper).insertJob(
                org.mockito.ArgumentMatchers.argThat(row ->
                        row.exportId().equals(job.exportId())
                                && row.exportFieldsJson().equals(
                                        "[\"EMPLOYEE_NAME\"]")));
        verify(mapper).insertArtifact(
                org.mockito.ArgumentMatchers.eq(job.exportId()),
                storedContent.capture(),
                org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(storedContent.getValue())
                .isEqualTo(
                        "formal-report".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void queuedClaimUsesCompareAndSetAndReturnsBuildingState() {
        AttendanceReportExportMapper mapper =
                mock(AttendanceReportExportMapper.class);
        var store = new MyBatisAttendanceReportExportStore(
                mapper, new ObjectMapper());
        when(mapper.findNextQueuedForUpdate(NOW))
                .thenReturn(queuedRow());
        when(mapper.markBuilding("export-1", NOW)).thenReturn(1);

        var claimed = store.claimNextQueued(NOW);

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().status())
                .isEqualTo(ExportStatus.BUILDING);
        verify(mapper).markBuilding("export-1", NOW);
    }

    @Test
    void queuedExportCannotPersistPrematureArtifactBytes() {
        AttendanceReportExportMapper mapper =
                mock(AttendanceReportExportMapper.class);
        var store = new MyBatisAttendanceReportExportStore(
                mapper, new ObjectMapper());
        ExportJob queued = queuedRow().toJob(
                List.of(ReportField.EMPLOYEE_NAME), null);

        assertThatThrownBy(() -> store.insert(
                queued,
                "sensitive-content".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot contain");

        verifyNoInteractions(mapper);
    }

    @Test
    void readyArtifactReadIsAtomicallyBoundToStoredDigestsAndExpiry() {
        AttendanceReportExportMapper mapper =
                mock(AttendanceReportExportMapper.class);
        var store = new MyBatisAttendanceReportExportStore(
                mapper, new ObjectMapper());
        byte[] content =
                "formal-report".getBytes(StandardCharsets.UTF_8);
        when(mapper.findOwnedReadyArtifact(
                "export-1",
                "principal-1",
                DIGEST,
                DIGEST,
                NOW)).thenReturn(readyRow(content));

        var result = store.findOwnedReady(
                "export-1",
                "principal-1",
                DIGEST,
                DIGEST,
                NOW);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content())
                .isEqualTo(content);
        verify(mapper).findOwnedReadyArtifact(
                "export-1",
                "principal-1",
                DIGEST,
                DIGEST,
                NOW);
    }

    @Test
    void readyTransitionRejectsMismatchedContentBeforeWriting() {
        AttendanceReportExportMapper mapper =
                mock(AttendanceReportExportMapper.class);
        var store = new MyBatisAttendanceReportExportStore(
                mapper, new ObjectMapper());

        assertThatThrownBy(() -> store.markReady(
                "export-1",
                "content".getBytes(StandardCharsets.UTF_8),
                "application/octet-stream",
                "xlsx",
                DIGEST,
                DIGEST,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verifyNoInteractions(mapper);
    }

    @Test
    void purposeIsTrimmedAndBoundedBeforeItCanReachPersistence() {
        assertThat(ExportJob.normalizePurpose("  月度薪资核对  "))
                .isEqualTo("月度薪资核对");
        assertThatThrownBy(() -> ExportJob.normalizePurpose("x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                ExportJob.normalizePurpose("x".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExportJob readyJob(byte[] content) {
        return new ExportJob(
                "export-1",
                "principal-1",
                ReportType.ATTENDANCE_DETAIL,
                new ReportFilter(
                        YearMonth.of(2026, 7),
                        "legal-1",
                        null,
                        null,
                        null),
                "月度薪资核对",
                "projection-1",
                DIGEST,
                DIGEST,
                DIGEST,
                "formula-1",
                List.of(ReportField.EMPLOYEE_NAME),
                1,
                DeliveryMode.SYNC,
                ExportStatus.READY,
                "application/octet-stream",
                "xlsx",
                sha256(content),
                content.length,
                null,
                NOW.plusSeconds(3_600),
                NOW,
                NOW);
    }

    private static AttendanceReportExportRows.ExportReadRow queuedRow() {
        return new AttendanceReportExportRows.ExportReadRow(
                "export-1",
                "principal-1",
                ReportType.ATTENDANCE_DETAIL.name(),
                LocalDate.of(2026, 7, 1),
                "legal-1",
                null,
                null,
                null,
                "月度薪资核对",
                "projection-1",
                DIGEST,
                DIGEST,
                DIGEST,
                "formula-1",
                "[\"EMPLOYEE_NAME\"]",
                50_001,
                DeliveryMode.ASYNC.name(),
                ExportStatus.QUEUED.name(),
                null,
                null,
                null,
                0,
                null,
                NOW.plusSeconds(3_600),
                NOW,
                null,
                null);
    }

    private static AttendanceReportExportRows.ExportReadRow readyRow(
            byte[] content) {
        return new AttendanceReportExportRows.ExportReadRow(
                "export-1",
                "principal-1",
                ReportType.ATTENDANCE_DETAIL.name(),
                LocalDate.of(2026, 7, 1),
                "legal-1",
                null,
                null,
                null,
                "月度薪资核对",
                "projection-1",
                DIGEST,
                DIGEST,
                DIGEST,
                "formula-1",
                "[\"EMPLOYEE_NAME\"]",
                1,
                DeliveryMode.SYNC.name(),
                ExportStatus.READY.name(),
                "application/octet-stream",
                "xlsx",
                sha256(content),
                content.length,
                null,
                NOW.plusSeconds(3_600),
                NOW,
                NOW,
                content);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
