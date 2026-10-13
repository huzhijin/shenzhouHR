package com.szsemicon.hr.punchimport.application;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceEvidenceRepository;
import com.szsemicon.hr.evidenceingestion.application.EvidenceRows;
import com.szsemicon.hr.punchimport.application.PunchImportExceptions.UnsafeWorkbookException;
import com.szsemicon.hr.punchimport.application.PunchImportReadModels.BatchView;
import com.szsemicon.hr.punchimport.domain.PunchImportStateMachine;
import com.szsemicon.hr.punchimport.domain.PunchImportStateMachine.BatchState;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper.EmployeeMatch;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper.FileRef;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper.LatestPrecheck;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper.StagedRow;
import com.szsemicon.hr.punchimport.port.MalwareScanPort;
import com.szsemicon.hr.punchimport.port.StoredObjectPort;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PunchImportCommandService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PUNCH_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration PRECHECK_TTL = Duration.ofHours(2);

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final PunchImportCommandMapper commands;
    private final PunchImportReadService reads;
    private final PunchWorkbookGateway workbooks;
    private final StoredObjectPort storage;
    private final MalwareScanPort scanner;
    private final AttendanceEvidenceRepository evidence;
    private final ObjectMapper json;
    private final Clock clock;

    public PunchImportCommandService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            PunchImportCommandMapper commands,
            PunchImportReadService reads,
            PunchWorkbookGateway workbooks,
            StoredObjectPort storage,
            MalwareScanPort scanner,
            AttendanceEvidenceRepository evidence,
            ObjectMapper json,
            Clock clock) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.commands = commands;
        this.reads = reads;
        this.workbooks = workbooks;
        this.storage = storage;
        this.scanner = scanner;
        this.evidence = evidence;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public BatchView upload(
            String companyId,
            String sourceId,
            String filename,
            String contentType,
            byte[] content,
            String reason,
            String requestId) {
        capabilities.require(CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_UPLOAD);
        String actor = principals.currentPrincipalId();
        Instant now = clock.instant();
        requireScope(actor, CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_UPLOAD, companyId, now);
        if (commands.findActiveSpreadsheetSource(sourceId, companyId) == null) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ATTENDANCE_SOURCE_INVALID",
                    "请选择当前公司的电子表格来源");
        }
        PunchWorkbookGateway.ParsedWorkbook parsed;
        try {
            parsed = workbooks.parse(content, filename, contentType);
        } catch (UnsafeWorkbookException exception) {
            throw new ApiProblemException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    exception.reasonCode(),
                    exception.getMessage());
        }
        var stored = storage.store(
                new java.io.ByteArrayInputStream(content),
                content.length,
                contentType == null
                        ? PunchWorkbookPolicyType.XLSX
                        : contentType);
        if (scanner.scan(stored.opaqueObjectReference())
                != com.szsemicon.hr.punchimport.port.MalwareScanPort.ScanResult.CLEAN) {
            throw new ApiProblemException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MALWARE_SCAN_REJECTED",
                    "文件未通过安全检查");
        }
        String batchId = UUID.randomUUID().toString();
        String fileId = UUID.randomUUID().toString();
        commands.insertBatch(batchId, sourceId, companyId, actor, now);
        commands.insertFile(
                fileId,
                batchId,
                sourceId,
                companyId,
                stored.sha256(),
                filename == null ? "workbook.xlsx" : filename,
                stored.opaqueObjectReference(),
                stored.size(),
                stored.contentType(),
                parsed.workbookKind(),
                parsed.fieldContractDigest(),
                actor,
                now);
        commands.insertState(
                UUID.randomUUID().toString(),
                batchId,
                1,
                null,
                BatchState.DRAFT.name(),
                "UPLOADED",
                requestId,
                actor,
                now);
        return reads.find(batchId);
    }

    @Transactional
    public BatchView precheck(String batchId, long expectedVersion, String requestId) {
        capabilities.require(CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_PRECHECK);
        String actor = principals.currentPrincipalId();
        Instant now = clock.instant();
        BatchView batch = reads.find(batchId);
        requireScope(
                actor,
                CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_PRECHECK,
                batch.companyId(),
                now);
        try {
            PunchImportStateMachine.requireTransition(
                    BatchState.valueOf(batch.state()),
                    BatchState.VALIDATING,
                    new PunchImportStateMachine.RetryPrerequisites(
                            true, true, true, false, false));
        } catch (IllegalStateException exception) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT, "PUNCH_IMPORT_INVALID_STATE", exception.getMessage());
        }
        bump(batchId, expectedVersion);
        appendState(batchId, batch.state(), BatchState.VALIDATING.name(),
                "VALIDATING", requestId, actor, now);
        FileRef file = commands.findFile(batchId);
        byte[] content;
        try (var input = storage.open(file.objectRef())) {
            content = input.readAllBytes();
        } catch (Exception exception) {
            throw new ApiProblemException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "STORED_FILE_UNAVAILABLE",
                    "无法读取已上传文件");
        }
        PunchWorkbookGateway.ParsedWorkbook parsed;
        try {
            parsed = workbooks.parse(content, file.filename(), PunchWorkbookPolicyType.XLSX);
        } catch (UnsafeWorkbookException exception) {
            failValidation(batchId, actor, requestId, now, exception.reasonCode());
            throw new ApiProblemException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    exception.reasonCode(),
                    exception.getMessage());
        }
        int attemptNumber = commands.nextAttemptNumber(batchId);
        String attemptId = UUID.randomUUID().toString();
        String digest = sha256(batchId, Integer.toString(attemptNumber),
                Integer.toString(parsed.punchRows().size()));
        commands.insertAttempt(
                attemptId,
                batchId,
                attemptNumber,
                parsed.punchRows().isEmpty() ? "INVALID" : "VALID",
                digest,
                now,
                now);
        List<Map<String, String>> punches = parsed.punchRows();
        Set<String> subjects = new LinkedHashSet<>();
        int blockingRows = 0;
        int warning = 0;
        int valid = 0;
        List<String> issueDigests = new ArrayList<>();
        int rowNumber = 0;
        for (Map<String, String> punch : punches) {
            rowNumber++;
            String rowId = UUID.randomUUID().toString();
            Map<String, String> raw = new LinkedHashMap<>(punch);
            List<Issue> issues = evaluate(batch, raw);
            int rowBlocking = 0;
            int rowWarning = 0;
            for (Issue issue : issues) {
                if ("BLOCKING".equals(issue.severity())) {
                    rowBlocking++;
                } else {
                    rowWarning++;
                }
            }
            warning += rowWarning;
            if (rowBlocking == 0) {
                valid++;
                if (raw.get("_employeeId") != null) {
                    subjects.add(raw.get("_employeeId"));
                }
            } else {
                blockingRows++;
            }
            commands.insertRow(
                    rowId,
                    batchId,
                    file.fileId(),
                    rowNumber,
                    writeJson(raw),
                    raw.get("_fingerprint"),
                    rowBlocking,
                    rowWarning,
                    now);
            for (Issue issue : issues) {
                commands.insertIssue(
                        UUID.randomUUID().toString(),
                        attemptId,
                        rowId,
                        issue.code(),
                        issue.severity(),
                        issue.field(),
                        issue.message(),
                        now);
                issueDigests.add(issue.code() + ":" + rowNumber);
            }
        }
        String token = UUID.randomUUID().toString();
        String precheckId = UUID.randomUUID().toString();
        commands.insertPrecheck(
                precheckId,
                batchId,
                attemptId,
                sha256(token),
                now.plus(PRECHECK_TTL),
                punches.size(),
                valid,
                blockingRows,
                warning,
                writeJson(subjects),
                sha256(issueDigests.toString()),
                now);
        String next = punches.isEmpty()
                ? BatchState.VALIDATION_FAILED.name()
                : BatchState.AWAITING_CONFIRMATION.name();
        appendState(batchId, BatchState.VALIDATING.name(), next,
                punches.isEmpty() ? "EMPTY_WORKBOOK" : "PRECHECKED",
                requestId, actor, now);
        BatchView refreshed = reads.find(batchId);
        return withToken(refreshed, token);
    }

    @Transactional
    public BatchView publish(
            String batchId,
            long expectedVersion,
            String precheckToken,
            String mode,
            String confirmation,
            String reason,
            String requestId) {
        boolean partial = "VALID_ROWS_ONLY".equals(mode);
        capabilities.require(partial
                ? CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_PARTIAL_PUBLISH
                : CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_PUBLISH);
        if (!"CONFIRM_PUBLISH".equals(confirmation)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "CONFIRMATION_REQUIRED",
                    "发布前必须确认");
        }
        String actor = principals.currentPrincipalId();
        Instant now = clock.instant();
        BatchView batch = reads.find(batchId);
        requireScope(actor, CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_PUBLISH,
                batch.companyId(), now);
        try {
            PunchImportStateMachine.requireTransition(
                    BatchState.valueOf(batch.state()),
                    BatchState.PUBLISHING);
        } catch (IllegalStateException exception) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT, "PUNCH_IMPORT_INVALID_STATE", exception.getMessage());
        }
        LatestPrecheck precheck = commands.findLatestPrecheck(batchId);
        if (precheck == null
                || precheck.expiresAt().isBefore(now)
                || !sha256(precheckToken).equals(precheck.tokenDigest())) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "PRECHECK_TOKEN_REQUIRED",
                    "请重新预检后再发布");
        }
        if (!partial && precheck.blockingCount() > 0) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "STRICT_PUBLISH_BLOCKED_BY_INVALID_ROWS",
                    "仍有阻断行，请改用仅发布有效行");
        }
        bump(batchId, expectedVersion);
        appendState(batchId, batch.state(), BatchState.PUBLISHING.name(),
                "PUBLISHING", requestId, actor, now);
        int published = 0;
        int retained = 0;
        for (StagedRow row : commands.listStagedRows(batchId)) {
            Map<String, String> raw = readJson(row.rawValuesJson());
            if (row.blockingIssueCount() > 0) {
                retained++;
                continue;
            }
            published += publishRow(batch, row, raw, actor, requestId, now);
        }
        commands.insertPublication(
                UUID.randomUUID().toString(),
                batchId,
                precheck.precheckId(),
                partial ? "VALID_ROWS_ONLY" : "STRICT",
                published,
                retained,
                requestId,
                sha256(batchId, mode, requestId),
                reason,
                actor,
                now);
        String endState = retained > 0
                ? BatchState.PARTIALLY_PUBLISHED.name()
                : BatchState.PUBLISHED.name();
        appendState(batchId, BatchState.PUBLISHING.name(), endState,
                "PUBLISHED", requestId, actor, now);
        return reads.find(batchId);
    }

    private int publishRow(
            BatchView batch,
            StagedRow row,
            Map<String, String> raw,
            String actor,
            String requestId,
            Instant now) {
        String employeeId = raw.get("_employeeId");
        String employmentPeriodId = raw.get("_employmentPeriodId");
        Instant punchInstant = Instant.parse(raw.get("_instant"));
        String direction = raw.get("direction");
        String rawId = UUID.randomUUID().toString();
        String normalizedId = UUID.randomUUID().toString();
        String matchId = UUID.randomUUID().toString();
        evidence.insertRawFact(new EvidenceRows.RawFactRow(
                rawId,
                batch.sourceId(),
                batch.companyId(),
                "PUNCH_POINT",
                row.rowId(),
                "1",
                row.stableFingerprint(),
                raw.get("punchTime"),
                raw.getOrDefault("sourceTimeZone", BUSINESS_ZONE.getId()),
                punchInstant,
                null,
                null,
                sha256("XLSX_RAW", row.rowId(), raw.get("punchTime")),
                null,
                requestId,
                now,
                actor));
        evidence.insertNormalizedRecord(new EvidenceRows.NormalizedRecordRow(
                normalizedId,
                rawId,
                1,
                "VENDOR_MONTHLY_V1",
                "PUNCH_POINT",
                direction,
                punchInstant,
                null,
                null,
                "VALID",
                null,
                sha256("XLSX_NORM", rawId, direction),
                null,
                now));
        evidence.insertMatchDecision(new EvidenceRows.MatchDecisionRow(
                matchId,
                normalizedId,
                "MATCHED",
                "EMPLOYEE_NUMBER",
                employeeId,
                employmentPeriodId,
                null,
                sha256(employeeId, employmentPeriodId),
                now));
        evidence.lockSubject(batch.companyId(), employeeId, now);
        var exact = evidence.findExactEvents(
                batch.companyId(), employeeId, punchInstant, direction);
        String eventId;
        if (exact != null && exact.size() == 1) {
            eventId = exact.getFirst().effectiveAttendanceEventId();
            evidence.insertEvidenceLink(new EvidenceRows.EvidenceLinkRow(
                    UUID.randomUUID().toString(),
                    eventId,
                    rawId,
                    normalizedId,
                    matchId,
                    "EXACT_DUPLICATE",
                    now));
        } else {
            eventId = UUID.randomUUID().toString();
            evidence.insertEffectiveEvent(new EvidenceRows.EffectiveEventRow(
                    eventId,
                    batch.companyId(),
                    employeeId,
                    "PUNCH_POINT",
                    direction,
                    punchInstant,
                    null,
                    null,
                    sha256("XLSX_EVENT", employeeId, punchInstant.toString(), direction),
                    now));
            evidence.insertLifecycleFact(new EvidenceRows.LifecycleFactRow(
                    UUID.randomUUID().toString(),
                    eventId,
                    "ACTIVATED",
                    null,
                    null,
                    now,
                    actor,
                    requestId,
                    "VENDOR_MONTHLY_IMPORT",
                    sha256("XLSX_ACTIVATED", eventId)));
            evidence.insertEvidenceLink(new EvidenceRows.EvidenceLinkRow(
                    UUID.randomUUID().toString(),
                    eventId,
                    rawId,
                    normalizedId,
                    matchId,
                    "PRIMARY",
                    now));
        }
        commands.markRowPublished(row.rowId(), rawId, eventId);
        return exact != null && exact.size() == 1 ? 0 : 1;
    }

    private List<Issue> evaluate(BatchView batch, Map<String, String> raw) {
        List<Issue> issues = new ArrayList<>();
        String number = trimToNull(raw.get("employeeNumber"));
        if (number == null) {
            issues.add(new Issue(
                    "UNMATCHED_EMPLOYEE_NUMBER",
                    "BLOCKING",
                    "employeeNumber",
                    "缺少工号，无法匹配员工"));
            return issues;
        }
        Instant instant;
        LocalDate businessDate;
        try {
            LocalDateTime local = LocalDateTime.parse(raw.get("punchTime"), PUNCH_TIME);
            instant = local.atZone(BUSINESS_ZONE).toInstant();
            businessDate = local.toLocalDate();
        } catch (RuntimeException exception) {
            issues.add(new Issue(
                    "PUNCH_TIME_INVALID",
                    "BLOCKING",
                    "punchTime",
                    "打卡时间无法解析"));
            return issues;
        }
        raw.put("_instant", instant.toString());
        raw.put("_businessDate", businessDate.toString());
        List<EmployeeMatch> matches = commands.matchEmployees(
                batch.companyId(), number, businessDate);
        if (matches.isEmpty()) {
            issues.add(new Issue(
                    "UNMATCHED_EMPLOYEE_NUMBER",
                    "BLOCKING",
                    "employeeNumber",
                    "工号在所选公司无法匹配"));
            return issues;
        }
        if (matches.size() > 1) {
            issues.add(new Issue(
                    "AMBIGUOUS_EMPLOYEE_NUMBER",
                    "BLOCKING",
                    "employeeNumber",
                    "工号在所选公司不唯一"));
            return issues;
        }
        EmployeeMatch match = matches.getFirst();
        raw.put("_employeeId", match.employeeId());
        raw.put("_employmentPeriodId", match.employmentPeriodId());
        raw.put("_fingerprint", sha256(
                batch.companyId(),
                match.employeeId(),
                instant.toString(),
                raw.get("direction")));
        var exact = evidence.findExactEvents(
                batch.companyId(),
                match.employeeId(),
                instant,
                raw.get("direction"));
        if (exact != null && !exact.isEmpty()) {
            issues.add(new Issue(
                    "EXACT_DUPLICATE",
                    "WARNING",
                    "punchTime",
                    "库中已有同一员工同一时刻同一方向的打卡"));
        }
        return issues;
    }

    private void requireScope(
            String actor,
            String capability,
            String companyId,
            Instant at) {
        if (commands.countCompanyScope(actor, capability, companyId, at) < 1) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private void bump(String batchId, long expectedVersion) {
        if (commands.incrementBatchVersion(batchId, expectedVersion) != 1) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT, "STALE_VERSION", "任务已被更新，请刷新后重试");
        }
    }

    private void appendState(
            String batchId,
            String from,
            String to,
            String reason,
            String requestId,
            String actor,
            Instant now) {
        commands.insertState(
                UUID.randomUUID().toString(),
                batchId,
                commands.nextStateSequence(batchId),
                from,
                to,
                reason,
                requestId,
                actor,
                now);
    }

    private void failValidation(
            String batchId,
            String actor,
            String requestId,
            Instant now,
            String reason) {
        appendState(
                batchId,
                BatchState.VALIDATING.name(),
                BatchState.VALIDATION_FAILED.name(),
                reason,
                requestId,
                actor,
                now);
    }

    private static BatchView withToken(BatchView batch, String token) {
        return new BatchView(
                batch.batchId(),
                batch.companyId(),
                batch.sourceId(),
                batch.originalFilename(),
                batch.fileSha256(),
                batch.state(),
                batch.totalRows(),
                batch.validRows(),
                batch.invalidRows(),
                batch.exactDuplicateRows(),
                batch.nearDuplicateRows(),
                batch.affectedEmployees(),
                batch.affectedDateFrom(),
                batch.affectedDateTo(),
                true,
                token,
                batch.createdAt(),
                batch.rowVersion());
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("unable to write json", exception);
        }
    }

    private Map<String, String> readJson(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {
            });
        } catch (Exception exception) {
            throw new IllegalStateException("unable to read json", exception);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Issue(String code, String severity, String field, String message) {
    }

    private static final class PunchWorkbookPolicyType {
        private static final String XLSX =
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

        private PunchWorkbookPolicyType() {
        }
    }
}
