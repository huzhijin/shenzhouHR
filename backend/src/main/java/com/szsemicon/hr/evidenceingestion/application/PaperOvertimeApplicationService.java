package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.paper.OcrPort;
import com.szsemicon.hr.evidenceingestion.application.paper.PaperOvertimeCandidateRanker;
import com.szsemicon.hr.evidenceingestion.application.paper.PaperOvertimeFormParser;
import com.szsemicon.hr.evidenceingestion.application.paper.PaperOvertimeOverlap;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceResolutionPolicy;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeMapper;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeRows;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.reporting.application.RealtimeAttendanceReportSnapshotService;
import com.szsemicon.hr.reporting.application.RecalcWindow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PaperOvertimeApplicationService {

    private static final Logger log =
            LoggerFactory.getLogger(PaperOvertimeApplicationService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final PaperOvertimeMapper mapper;
    private final OcrPort ocr;
    private final EmployeeEmploymentResolverPort employees;
    private final AttendanceEvidenceRepository evidence;
    private final RealtimeAttendanceReportSnapshotService snapshots;
    private final Clock clock;

    public PaperOvertimeApplicationService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            PaperOvertimeMapper mapper,
            OcrPort ocr,
            EmployeeEmploymentResolverPort employees,
            AttendanceEvidenceRepository evidence,
            RealtimeAttendanceReportSnapshotService snapshots,
            Clock clock) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.mapper = mapper;
        this.ocr = ocr;
        this.employees = employees;
        this.evidence = evidence;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public RecognizeResult recognize(String companyId, List<MultipartFile> files) {
        capabilities.require(CapabilityCodes.PAPER_OVERTIME_MANAGE);
        requireCompany(companyId);
        Instant now = clock.instant();
        String batchId = UUID.randomUUID().toString();
        mapper.insertBatch(
                batchId, companyId, principals.currentPrincipalId(), now);
        LocalDate today = now.atZone(ZONE).toLocalDate();
        List<DraftLine> lines = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                List<BufferedImage> images = imagesOf(file);
                if (images.isEmpty()) {
                    throw new ApiProblemException(
                            HttpStatus.BAD_REQUEST,
                            "PAPER_OVERTIME_IMAGE_UNREADABLE",
                            "无法读取上传的照片，请改用 JPG 或 PNG 后再识别，或直接手填。",
                            true);
                }
                for (BufferedImage image : images) {
                    String text = ocr.recognize(image);
                    if (text == null || text.isBlank()) {
                        throw new ApiProblemException(
                                HttpStatus.BAD_REQUEST,
                                "PAPER_OVERTIME_OCR_EMPTY",
                                "没有识别出文字。请换更清晰的照片后再试，或直接在下方手填。",
                                true);
                    }
                    PaperOvertimeFormParser.Draft parsed =
                            PaperOvertimeFormParser.parse(text, today);
                    String attachmentId = UUID.randomUUID().toString();
                    mapper.insertAttachment(new PaperOvertimeRows.AttachmentRow(
                            attachmentId,
                            batchId,
                            file.getOriginalFilename() == null
                                    ? "upload"
                                    : file.getOriginalFilename(),
                            file.getContentType() == null
                                    ? "application/octet-stream"
                                    : file.getContentType(),
                            bytes(file),
                            text,
                            now));
                    List<PaperOvertimeRows.EmployeeCandidateRow> candidates =
                            PaperOvertimeCandidateRanker.rank(
                                    mapper.suggestEmployees(
                                            companyId,
                                            parsed.name(),
                                            parsed.department(),
                                            parsed.overtimeDate() == null
                                                    ? today
                                                    : parsed.overtimeDate()),
                                    parsed.name(),
                                    parsed.department());
                    lines.add(new DraftLine(
                            UUID.randomUUID().toString(),
                            parsed.name(),
                            parsed.department(),
                            parsed.overtimeDate(),
                            parsed.start(),
                            parsed.end(),
                            parsed.overtimeType() == null
                                    ? null
                                    : parsed.overtimeType().name(),
                            parsed.reason(),
                            candidates.stream().map(this::toCandidate).toList()));
                }
            }
        }
        return new RecognizeResult(batchId, lines);
    }

    public List<EmployeeCandidate> suggest(
            String companyId, String name, String department, LocalDate asOf) {
        capabilities.require(CapabilityCodes.PAPER_OVERTIME_MANAGE);
        requireCompany(companyId);
        LocalDate date = asOf == null
                ? clock.instant().atZone(ZONE).toLocalDate()
                : asOf;
        return PaperOvertimeCandidateRanker.rank(
                        mapper.suggestEmployees(companyId, name, department, date),
                        name,
                        department)
                .stream()
                .map(this::toCandidate)
                .toList();
    }

    public List<SavedLine> list(
            String companyId, LocalDate fromDate, LocalDate toDate) {
        capabilities.require(CapabilityCodes.PAPER_OVERTIME_MANAGE);
        requireCompany(companyId);
        LocalDate from = fromDate == null
                ? clock.instant().atZone(ZONE).toLocalDate().withDayOfMonth(1)
                : fromDate;
        LocalDate to = toDate == null ? from.plusMonths(1).minusDays(1) : toDate;
        if (to.isBefore(from)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PAPER_OVERTIME_INTERVAL_REQUIRED",
                    "结束日期不能早于开始日期");
        }
        return mapper.listSavedLines(companyId, from, to).stream()
                .map(row -> new SavedLine(
                        row.lineId(),
                        row.batchId(),
                        row.employeeId(),
                        row.employeeNumber(),
                        row.employeeName(),
                        row.departmentName(),
                        row.overtimeDate(),
                        row.startAt(),
                        row.endAt(),
                        row.overtimeType(),
                        row.reason(),
                        row.documentId()))
                .toList();
    }

    @Transactional
    public SaveResult save(SaveCommand command) {
        capabilities.require(CapabilityCodes.PAPER_OVERTIME_MANAGE);
        requireCompany(command.companyId());
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PAPER_OVERTIME_LINES_REQUIRED",
                    "请至少录入一行加班明细");
        }
        Instant now = clock.instant();
        String batchId = command.batchId() == null || command.batchId().isBlank()
                ? UUID.randomUUID().toString()
                : command.batchId();
        if (command.batchId() == null || command.batchId().isBlank()) {
            mapper.insertBatch(
                    batchId,
                    command.companyId(),
                    principals.currentPrincipalId(),
                    now);
        }
        Map<String, Set<LocalDate>> batchDates = new HashMap<>();
        List<ValidatedLine> validated = new ArrayList<>();
        for (SaveLine line : command.lines()) {
            ValidatedLine ready = validateLine(command.companyId(), line);
            Set<LocalDate> days = PaperOvertimeOverlap.calendarDays(
                    ready.start(), ready.end());
            Set<LocalDate> previous = batchDates.get(ready.employeeId());
            if (previous != null
                    && PaperOvertimeOverlap.datesOverlap(previous, days)) {
                throw overlap(
                        ready.employeeNumber(),
                        overlappingDate(days, previous),
                        "同批明细");
            }
            batchDates.computeIfAbsent(ready.employeeId(), key -> new HashSet<>())
                    .addAll(days);
            for (PaperOvertimeRows.ExistingOvertimeRow existing :
                    mapper.listApprovedOvertime(
                            command.companyId(), ready.employeeId())) {
                Set<LocalDate> existingDays = PaperOvertimeOverlap.calendarDays(
                        existing.intervalStart(),
                        existing.intervalEnd());
                if (PaperOvertimeOverlap.datesOverlap(days, existingDays)) {
                    String origin = existing.sourceBusinessKey() != null
                            && existing.sourceBusinessKey().startsWith("PAPER:")
                            ? "纸质"
                            : "OA";
                    throw overlap(
                            ready.employeeNumber(),
                            overlappingDate(days, existingDays),
                            origin + " " + existing.sourceBusinessKey());
                }
            }
            validated.add(ready);
        }
        String sourceId = ensureSource(command.companyId(), now);
        int saved = 0;
        for (ValidatedLine line : validated) {
            String lineId = UUID.randomUUID().toString();
            mapper.insertLine(new PaperOvertimeRows.LineRow(
                    lineId,
                    batchId,
                    line.employeeId(),
                    line.employeeName(),
                    line.department(),
                    line.overtimeDate(),
                    line.start(),
                    line.end(),
                    line.overtimeType().name(),
                    line.reason(),
                    now));
            String documentId = ingest(command.companyId(), sourceId, line, now);
            mapper.updateLineDocument(lineId, documentId);
            saved++;
        }
        mapper.markSaved(batchId, now);
        rebuildOvertimeReports(command.companyId(), validated, now);
        return new SaveResult(batchId, saved);
    }

    private void rebuildOvertimeReports(
            String companyId,
            List<ValidatedLine> lines,
            Instant now) {
        if (snapshots == null || lines.isEmpty()) {
            return;
        }
        Set<YearMonth> months = new HashSet<>();
        for (ValidatedLine line : lines) {
            months.add(YearMonth.from(line.overtimeDate()));
        }
        Thread worker = new Thread(() -> {
            for (YearMonth month : months) {
                try {
                    snapshots.materializeCompanyMonthWindow(
                            companyId, month, RecalcWindow.MONTH, now);
                } catch (RuntimeException exception) {
                    log.error(
                            "Paper overtime report rebuild failed company={} month={}",
                            companyId,
                            month,
                            exception);
                }
            }
        }, "paper-overtime-recalc");
        worker.setDaemon(true);
        worker.start();
    }

    private ValidatedLine validateLine(String companyId, SaveLine line) {
        if (line.employeeId() == null || line.employeeId().isBlank()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PAPER_OVERTIME_PERSON_REQUIRED",
                    "请确认加班人员");
        }
        if (line.overtimeDate() == null
                || line.start() == null
                || line.end() == null) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PAPER_OVERTIME_INTERVAL_REQUIRED",
                    "请填写加班日期和起止时间");
        }
        OvertimeType type = parseType(line.overtimeType());
        if (type == null) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PAPER_OVERTIME_TYPE_REQUIRED",
                    "请选择加班类型");
        }
        PaperOvertimeRows.EmployeeCandidateRow employee = mapper.findEmployee(
                companyId, line.employeeId(), line.overtimeDate());
        if (employee == null) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PAPER_OVERTIME_PERSON_REQUIRED",
                    "未找到所选人员");
        }
        Instant start = LocalDateTime.of(line.overtimeDate(), line.start())
                .atZone(ZONE)
                .toInstant();
        Instant end = LocalDateTime.of(line.overtimeDate(), line.end())
                .atZone(ZONE)
                .toInstant();
        if (!end.isAfter(start)) {
            end = LocalDateTime.of(line.overtimeDate().plusDays(1), line.end())
                    .atZone(ZONE)
                    .toInstant();
        }
        return new ValidatedLine(
                employee.employeeId(),
                employee.employeeNumber(),
                employee.displayName(),
                employee.departmentName(),
                line.overtimeDate(),
                start,
                end,
                type,
                line.reason());
    }

    private String ingest(
            String companyId,
            String sourceId,
            ValidatedLine line,
            Instant now) {
        EvidenceResolutionPolicy.MatchDecision decision =
                EvidenceResolutionPolicy.resolve(
                        employees,
                        sourceId,
                        companyId,
                        line.employeeNumber(),
                        null,
                        null,
                        null,
                        null,
                        line.start());
        if (decision.status() != EvidenceResolutionPolicy.MatchStatus.MATCHED) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PAPER_OVERTIME_PERSON_REQUIRED",
                    "未能匹配工号 " + line.employeeNumber());
        }
        String rawId = UUID.randomUUID().toString();
        String normalizedId = UUID.randomUUID().toString();
        String matchId = UUID.randomUUID().toString();
        String documentId = UUID.randomUUID().toString();
        String key = "PAPER:" + rawId;
        String digest = AttendanceEvidenceDigests.sha256(
                "PAPER_OT_V1",
                sourceId,
                companyId,
                key,
                line.employeeNumber(),
                line.start().toString(),
                line.end().toString(),
                line.overtimeType().name());
        evidence.insertRawFact(new EvidenceRows.RawFactRow(
                rawId,
                sourceId,
                companyId,
                "OA_DOCUMENT",
                key,
                "1",
                null,
                line.start().toString(),
                ZONE.getId(),
                null,
                line.start(),
                line.end(),
                digest,
                null,
                principals.currentPrincipalId(),
                now,
                principals.currentPrincipalId()));
        evidence.insertNormalizedRecord(new EvidenceRows.NormalizedRecordRow(
                normalizedId,
                rawId,
                1,
                "OA_DOCUMENT_V1",
                "OA_INTERVAL",
                null,
                null,
                line.start(),
                line.end(),
                "VALID",
                null,
                digest,
                null,
                now));
        evidence.insertMatchDecision(new EvidenceRows.MatchDecisionRow(
                matchId,
                normalizedId,
                decision.status().name(),
                decision.reason(),
                decision.employeeId(),
                decision.employmentPeriodId(),
                null,
                decision.resolverSnapshotDigest(),
                now));
        evidence.insertOaAttendanceDocument(new EvidenceRows.OaDocumentRow(
                documentId,
                sourceId,
                key,
                "1",
                "OVERTIME",
                line.overtimeType(),
                null,
                "APPROVED",
                normalizedId,
                now.toEpochMilli(),
                null,
                now,
                now,
                null,
                null,
                now,
                null,
                null));
        return documentId;
    }

    private String ensureSource(String companyId, Instant now) {
        String existing = mapper.findPaperSourceId(companyId);
        if (existing != null) {
            return existing;
        }
        String sourceId = UUID.randomUUID().toString();
        mapper.insertPaperSource(sourceId, companyId, now);
        return sourceId;
    }

    private EmployeeCandidate toCandidate(
            PaperOvertimeRows.EmployeeCandidateRow row) {
        return new EmployeeCandidate(
                row.employeeId(),
                row.employeeNumber(),
                row.displayName(),
                row.organizationId(),
                row.departmentName(),
                row.employmentStatus());
    }

    private static OvertimeType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OvertimeType.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void requireCompany(String companyId) {
        if (companyId == null || companyId.isBlank() || companyId.length() > 36) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PAPER_OVERTIME_COMPANY_REQUIRED",
                    "请选择公司");
        }
    }

    private static LocalDate overlappingDate(
            Set<LocalDate> left, Set<LocalDate> right) {
        for (LocalDate date : left) {
            if (right.contains(date)) {
                return date;
            }
        }
        return left.iterator().next();
    }

    private static ApiProblemException overlap(
            String employeeNumber, LocalDate date, String source) {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "PAPER_OVERTIME_DATE_OVERLAP",
                "工号 " + employeeNumber + " 在 " + date
                        + " 已有加班单（" + source + "），不能保存");
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception exception) {
            return new byte[0];
        }
    }

    private static List<BufferedImage> imagesOf(MultipartFile file) {
        String name = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename().toLowerCase();
        String type = file.getContentType() == null ? "" : file.getContentType();
        try {
            if (type.contains("pdf") || name.endsWith(".pdf")) {
                try (PDDocument document = PDDocument.load(file.getInputStream())) {
                    PDFRenderer renderer = new PDFRenderer(document);
                    List<BufferedImage> pages = new ArrayList<>();
                    for (int page = 0; page < document.getNumberOfPages(); page++) {
                        pages.add(renderer.renderImageWithDPI(
                                page, 200, ImageType.RGB));
                    }
                    return pages;
                }
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
            return image == null ? List.of() : List.of(image);
        } catch (Exception exception) {
            return List.of();
        }
    }

    public record EmployeeCandidate(
            String employeeId,
            String employeeNumber,
            String displayName,
            String organizationId,
            String departmentName,
            String employmentStatus) {
    }

    public record DraftLine(
            String lineId,
            String ocrName,
            String ocrDepartment,
            LocalDate overtimeDate,
            LocalTime start,
            LocalTime end,
            String overtimeType,
            String reason,
            List<EmployeeCandidate> candidates) {
    }

    public record RecognizeResult(String batchId, List<DraftLine> lines) {
    }

    public record SaveLine(
            String employeeId,
            LocalDate overtimeDate,
            LocalTime start,
            LocalTime end,
            String overtimeType,
            String reason) {
    }

    public record SaveCommand(
            String companyId, String batchId, List<SaveLine> lines) {
    }

    public record SaveResult(String batchId, int savedCount) {
    }

    public record SavedLine(
            String lineId,
            String batchId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String departmentName,
            LocalDate overtimeDate,
            Instant startAt,
            Instant endAt,
            String overtimeType,
            String reason,
            String documentId) {
    }

    private record ValidatedLine(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String department,
            LocalDate overtimeDate,
            Instant start,
            Instant end,
            OvertimeType overtimeType,
            String reason) {
    }
}
