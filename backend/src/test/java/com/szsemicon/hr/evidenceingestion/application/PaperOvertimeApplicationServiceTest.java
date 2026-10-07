package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.evidenceingestion.application.PaperOvertimeApplicationService.SaveCommand;
import com.szsemicon.hr.evidenceingestion.application.PaperOvertimeApplicationService.SaveLine;
import com.szsemicon.hr.evidenceingestion.application.paper.OcrPort;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeMapper;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeRows.EmployeeCandidateRow;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeRows.ExistingOvertimeRow;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.reporting.application.RealtimeAttendanceReportSnapshotService;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class PaperOvertimeApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T02:00:00Z");
    private static final String COMPANY = "company-1";
    private static final EmployeeCandidateRow EMP_10012 = new EmployeeCandidateRow(
            "emp-10012", "10012", "陈士庆", "org-eq", "设备工程部", "ACTIVE");
    private static final EmployeeCandidateRow EMP_10087 = new EmployeeCandidateRow(
            "emp-10087", "10087", "陈士庆", "org-fin", "财务部", "ACTIVE");

    @Mock
    private CurrentCapabilityService capabilities;
    @Mock
    private CurrentPrincipalProvider principals;
    @Mock
    private PaperOvertimeMapper mapper;
    @Mock
    private OcrPort ocr;
    @Mock
    private EmployeeEmploymentResolverPort employees;
    @Mock
    private AttendanceEvidenceRepository evidence;
    @Mock
    private RealtimeAttendanceReportSnapshotService snapshots;

    private PaperOvertimeApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PaperOvertimeApplicationService(
                capabilities,
                principals,
                mapper,
                ocr,
                employees,
                evidence,
                snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient()
                .when(principals.currentPrincipalId())
                .thenReturn("hr-1");
    }

    @Test
    void threePagePdfProducesThreeDraftRows() throws Exception {
        when(ocr.recognize(any(BufferedImage.class))).thenReturn(
                slip("陈士庆", "8.18"),
                slip("周晴", "8.19"),
                slip("张伟", "8.20"));
        when(mapper.suggestEmployees(eq(COMPANY), any(), any(), any()))
                .thenReturn(List.of(EMP_10012));

        var result = service.recognize(COMPANY, List.of(pdf(3)));

        assertThat(result.lines()).hasSize(3);
        assertThat(result.lines())
                .extracting(line -> line.ocrName())
                .containsExactly("陈士庆", "周晴", "张伟");
        verify(ocr, org.mockito.Mockito.times(3)).recognize(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
    }

    @Test
    void blankOcrTextFailsClosedWithHandFillHint() throws Exception {
        when(ocr.recognize(any(BufferedImage.class))).thenReturn("  ");

        assertThatThrownBy(() -> service.recognize(COMPANY, List.of(png("slip.png"))))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("没有识别出文字");
    }

    @Test
    void twoImagesAreNotAutoMerged() throws Exception {
        when(ocr.recognize(any(BufferedImage.class)))
                .thenReturn(slip("陈士庆", "8.18"), slip("陈士庆", "8.18"));
        when(mapper.suggestEmployees(eq(COMPANY), any(), any(), any()))
                .thenReturn(List.of(EMP_10012));

        var result = service.recognize(
                COMPANY,
                List.of(png("front.png"), png("back.png")));

        assertThat(result.lines()).hasSize(2);
    }

    @Test
    void sameDaySecondSlipFails() {
        when(mapper.findEmployee(COMPANY, "emp-10012", LocalDate.of(2026, 8, 18)))
                .thenReturn(EMP_10012);
        when(mapper.listApprovedOvertime(COMPANY, "emp-10012"))
                .thenReturn(List.of(existing(
                        "PAPER:old",
                        Instant.parse("2026-08-18T09:40:00Z"),
                        Instant.parse("2026-08-18T14:00:00Z"))));

        assertThatThrownBy(() -> service.save(command(line(
                        "emp-10012",
                        LocalDate.of(2026, 8, 18),
                        LocalTime.of(18, 0),
                        LocalTime.of(21, 0)))))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("10012")
                .hasMessageContaining("2026-08-18");
        verify(evidence, never()).insertRawFact(any());
    }

    @Test
    void overnightOccupiesTheNextCalendarDay() {
        when(mapper.findEmployee(COMPANY, "emp-10012", LocalDate.of(2026, 8, 19)))
                .thenReturn(EMP_10012);
        when(mapper.listApprovedOvertime(COMPANY, "emp-10012"))
                .thenReturn(List.of(existing(
                        "OA:ot-1",
                        Instant.parse("2026-08-18T14:00:00Z"),
                        Instant.parse("2026-08-18T22:00:00Z"))));

        assertThatThrownBy(() -> service.save(command(line(
                        "emp-10012",
                        LocalDate.of(2026, 8, 19),
                        LocalTime.of(8, 0),
                        LocalTime.of(12, 0)))))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("2026-08-19");
        verify(evidence, never()).insertRawFact(any());
    }

    @Test
    void sameNameDifferentEmployeeNumbersDoNotConflict() {
        when(mapper.findEmployee(COMPANY, "emp-10087", LocalDate.of(2026, 8, 18)))
                .thenReturn(EMP_10087);
        when(mapper.listApprovedOvertime(COMPANY, "emp-10087"))
                .thenReturn(List.of());
        when(mapper.findPaperSourceId(COMPANY)).thenReturn("source-paper");
        when(employees.resolveByEmployeeNumber(
                        eq(COMPANY), eq("10087"), any(Instant.class)))
                .thenReturn(List.of(new EmployeeEmploymentResolverPort.Resolution(
                        "emp-10087",
                        "period-10087",
                        "digest")));

        var result = service.save(command(line(
                "emp-10087",
                LocalDate.of(2026, 8, 18),
                LocalTime.of(17, 40),
                LocalTime.of(22, 0))));

        assertThat(result.savedCount()).isEqualTo(1);
        verify(evidence).insertOaAttendanceDocument(any());
    }

    private static SaveCommand command(SaveLine line) {
        return new SaveCommand(COMPANY, null, List.of(line));
    }

    private static SaveLine line(
            String employeeId, LocalDate date, LocalTime start, LocalTime end) {
        return new SaveLine(
                employeeId, date, start, end, OvertimeType.COMPENSATORY.name(), "写SOP");
    }

    private static ExistingOvertimeRow existing(
            String key, Instant start, Instant end) {
        return new ExistingOvertimeRow("doc-1", key, start, end);
    }

    private static String slip(String name, String date) {
        return """
                加班人员：%s
                加班日期：%s
                加班时间：17:40 至 22:00
                加班事由：写SOP文件
                ☑调休  加班费  义务加班
                """.formatted(name, date);
    }

    private static MultipartFile pdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return new MockMultipartFile(
                    "files",
                    "slips.pdf",
                    "application/pdf",
                    output.toByteArray());
        }
    }

    private static MultipartFile png(String name) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile("files", name, "image/png", output.toByteArray());
    }
}
