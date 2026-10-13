package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.employee.application.EmployeeAuthorizationPort;
import com.szsemicon.hr.evidenceingestion.application.AttendanceEvidenceRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * V75 OA overtime recognition thin write service.
 * Corrected: field-by-field comparison, MICROS truncation, real geometry validation,
 * other consumable OA overlap check, 30-minute grid enforcement, audit reason truncation.
 */
@Service
public class OaOvertimeRecognitionWriteService {

    private static final Logger log =
            LoggerFactory.getLogger(OaOvertimeRecognitionWriteService.class);

    private static final String SHARED_SOURCE_ID = "e05866a7-97b0-11f1-8924-00155d09fd0e";
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final java.util.regex.Pattern HEX64_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$");
    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final OaOvertimeRecognitionPort mapper;
    private final AttendanceEvidenceRepository evidenceRepository;
    private final AuditService auditService;
    private final CurrentCapabilityService capabilityService;
    private final EmployeeAuthorizationPort employeeAuthorizationPort;
    private final CurrentPrincipalProvider principalProvider;
    private final Clock clock;

    public OaOvertimeRecognitionWriteService(
            OaOvertimeRecognitionPort mapper,
            AttendanceEvidenceRepository evidenceRepository,
            AuditService auditService,
            CurrentCapabilityService capabilityService,
            EmployeeAuthorizationPort employeeAuthorizationPort,
            CurrentPrincipalProvider principalProvider,
            Clock clock) {
        this.mapper = mapper;
        this.evidenceRepository = evidenceRepository;
        this.auditService = auditService;
        this.capabilityService = capabilityService;
        this.employeeAuthorizationPort = employeeAuthorizationPort;
        this.principalProvider = principalProvider;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RecognitionReceipt recognize(RecognitionIntent intent) {
        // Input validation
        validateUuid(intent.requestId(), "requestId");
        validateUuid(intent.recognitionId(), "recognitionId");
        validateUuid(intent.companyId(), "companyId");
        validateUuid(intent.employeeId(), "employeeId");
        validateUuid(intent.oaAttendanceDocumentId(), "oaAttendanceDocumentId");

        if (intent.sourceBusinessKey() == null || intent.sourceBusinessKey().isBlank()) {
            throw new IllegalArgumentException("sourceBusinessKey cannot be blank");
        }
        if (intent.sourceVersion() == null || intent.sourceVersion().isBlank()) {
            throw new IllegalArgumentException("sourceVersion cannot be blank");
        }
        if (intent.businessDate() == null) {
            throw new IllegalArgumentException("businessDate cannot be null");
        }
        if (intent.expectedIntervalStart() == null || intent.expectedIntervalEnd() == null) {
            throw new IllegalArgumentException("interval bounds cannot be null");
        }
        if (!intent.expectedIntervalEnd().isAfter(intent.expectedIntervalStart())) {
            throw new IllegalArgumentException("interval end must be after start");
        }

        // 30-minute grid validation
        long startSecondOfDay = intent.expectedIntervalStart().atZone(SHANGHAI_ZONE)
                .toLocalTime().toSecondOfDay();
        long endSecondOfDay = intent.expectedIntervalEnd().atZone(SHANGHAI_ZONE)
                .toLocalTime().toSecondOfDay();
        if (startSecondOfDay % 1800 != 0 || endSecondOfDay % 1800 != 0) {
            throw new IllegalArgumentException(
                    "interval start/end must be on 30-minute grid in Shanghai time");
        }

        // Minutes validation
        if (intent.recognizedMinutes() <= 0 || intent.recognizedMinutes() > 1440
                || intent.recognizedMinutes() % 30 != 0) {
            throw new IllegalArgumentException(
                    "recognizedMinutes must be in (0,1440], multiple of 30");
        }

        // Interval duration check
        long intervalMinutes = ChronoUnit.MINUTES.between(
                intent.expectedIntervalStart(), intent.expectedIntervalEnd());
        if (intent.recognizedMinutes() > intervalMinutes) {
            throw new IllegalArgumentException(
                    "recognizedMinutes (" + intent.recognizedMinutes()
                    + ") exceeds interval duration (" + intervalMinutes + " minutes)");
        }

        if (intent.reason() == null || intent.reason().trim().isEmpty()
                || intent.reason().length() > 1000) {
            throw new IllegalArgumentException("reason must be 1-1000 chars, non-blank");
        }
        if (intent.userConfirmationText() == null || intent.userConfirmationText().trim().isEmpty()) {
            throw new IllegalArgumentException("userConfirmationText cannot be blank");
        }
        if (intent.userConfirmationSha256() == null
                || !HEX64_PATTERN.matcher(intent.userConfirmationSha256()).matches()) {
            throw new IllegalArgumentException("userConfirmationSha256 must be 64 hex chars");
        }

        // Verify user confirmation SHA matches actual text
        String actualConfirmationSha = computeSha256(intent.userConfirmationText());
        if (!actualConfirmationSha.equals(intent.userConfirmationSha256())) {
            throw new IllegalArgumentException(
                    "userConfirmationSha256 does not match userConfirmationText");
        }

        if (intent.evidenceSha256() == null
                || !HEX64_PATTERN.matcher(intent.evidenceSha256()).matches()) {
            throw new IllegalArgumentException("evidenceSha256 must be 64 hex chars");
        }

        Instant initialNow = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String actorId = principalProvider.currentPrincipalId();

        // Authorization: capability + employee data scope
        capabilityService.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, initialNow, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        // Check replay before acquiring source lock
        var existingRow = mapper.findByRequest(
                intent.requestId(), intent.oaAttendanceDocumentId());

        if (existingRow != null) {
            return handleReplay(intent, existingRow, actorId, initialNow);
        }

        // Lock source to serialize version reads
        var lockedSource = mapper.lockActiveOaSource(SHARED_SOURCE_ID);
        if (lockedSource == null) {
            throw new IllegalStateException("OA source " + SHARED_SOURCE_ID + " not ACTIVE");
        }

        // Re-take time after lock acquisition for temporal gates
        Instant nowAfterLock = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // Re-authorize after acquiring lock
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, nowAfterLock, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        // Re-check receipt after lock acquisition
        existingRow = mapper.findByRequest(
                intent.requestId(), intent.oaAttendanceDocumentId());
        if (existingRow != null) {
            return handleReplay(intent, existingRow, actorId, nowAfterLock);
        }

        // Check latest OA observation with precedence logic (OaDocumentPageTransaction:296-304)
        // 1. Read latest (including unparsed)
        var latest = evidenceRepository.findLatestOaObservation(
                SHARED_SOURCE_ID, intent.sourceBusinessKey(), false);

        // 2. Read latest resolved
        var resolved = evidenceRepository.findLatestOaObservation(
                SHARED_SOURCE_ID, intent.sourceBusinessKey(), true);

        // 3. Precedence: use resolved only when latest has a document ID and resolved exists
        var observation = (latest != null
                           && latest.oaAttendanceDocumentId() != null
                           && resolved != null)
                          ? resolved
                          : latest;

        // 4. Reject if observation is null or unparsed
        if (observation == null || observation.oaAttendanceDocumentId() == null) {
            throw new IllegalStateException(
                    "Latest OA observation for key " + intent.sourceBusinessKey()
                    + " is unparsed or missing, refusing write");
        }

        // Gate: intent must reference the current latest observation version
        if (!intent.sourceVersion().equals(observation.sourceVersion())) {
            throw new IllegalStateException(
                    "Version mismatch: request references " + intent.sourceVersion()
                    + ", but current latest observation is " + observation.sourceVersion());
        }

        if (!intent.oaAttendanceDocumentId().equals(observation.oaAttendanceDocumentId())) {
            throw new IllegalStateException(
                    "Document ID mismatch: request references " + intent.oaAttendanceDocumentId()
                    + ", but current latest observation is " + observation.oaAttendanceDocumentId());
        }

        // Fetch and validate complete OA document with real joins
        var validated = mapper.findValidatedOaDocument(
                intent.oaAttendanceDocumentId(),
                SHARED_SOURCE_ID,
                intent.sourceBusinessKey(),
                intent.sourceVersion());

        if (validated == null) {
            throw new IllegalStateException(
                    "Document not found or validation failed for: " + intent.oaAttendanceDocumentId());
        }

        // Gate: version and identity already matched by query WHERE clause
        // Gate: validation_status = VALID (query)
        // Gate: match_status = MATCHED (query)

        // Gate: source status must be APPROVED
        if (!"APPROVED".equals(validated.sourceStatus())) {
            throw new IllegalStateException(
                    "Source status not APPROVED: " + validated.sourceStatus());
        }

        // Gate: document type must be OVERTIME (explicit check)
        if (!"OVERTIME".equals(validated.documentType())) {
            throw new IllegalStateException(
                    "Document type not OVERTIME: " + validated.documentType());
        }

        // Gate: activation decision must be ACTIVATED
        if (!"ACTIVATED".equals(validated.activationDecision())) {
            throw new IllegalStateException(
                    "Document not ACTIVATED: " + validated.activationDecision());
        }

        // Gate: overtime_type must be COMPENSATORY
        if (!"COMPENSATORY".equals(validated.overtimeType())) {
            throw new IllegalStateException(
                    "overtime_type not COMPENSATORY: " + validated.overtimeType());
        }

        // Gate: normalized interval must match
        if (!intent.expectedIntervalStart().equals(validated.intervalStart())
                || !intent.expectedIntervalEnd().equals(validated.intervalEnd())) {
            throw new IllegalStateException(
                    "Interval mismatch: expected [" + intent.expectedIntervalStart()
                    + "," + intent.expectedIntervalEnd() + "], actual ["
                    + validated.intervalStart() + "," + validated.intervalEnd() + "]");
        }

        // Gate: business date must match (same Shanghai day, end.minusNanos(1) allowed)
        LocalDate actualStartDate = validated.intervalStart().atZone(SHANGHAI_ZONE).toLocalDate();
        LocalDate actualEndDate = intent.expectedIntervalEnd().minusNanos(1)
                .atZone(SHANGHAI_ZONE).toLocalDate();
        if (!intent.businessDate().equals(actualStartDate)
                || !intent.businessDate().equals(actualEndDate)) {
            throw new IllegalStateException(
                    "Business date mismatch or cross-day interval: expected="
                    + intent.businessDate() + ", actual start=" + actualStartDate
                    + ", end=" + actualEndDate);
        }

        // Gate: employee match
        if (!intent.employeeId().equals(validated.employeeId())) {
            throw new IllegalStateException(
                    "Employee mismatch: expected=" + intent.employeeId()
                    + ", matched=" + validated.employeeId());
        }

        // Gate: company match (from actual employee.company_id, not OA source company)
        if (!intent.companyId().equals(validated.companyId())) {
            throw new IllegalStateException(
                    "Company mismatch: expected=" + intent.companyId()
                    + ", employee company=" + validated.companyId());
        }

        // Gate: no V74 exclusion
        boolean excluded = mapper.hasActiveExclusion(
                intent.companyId(), intent.employeeId(), SHARED_SOURCE_ID,
                intent.sourceBusinessKey(), intent.sourceVersion(),
                intent.oaAttendanceDocumentId(), nowAfterLock);
        if (excluded) {
            throw new IllegalStateException(
                    "Document is excluded by V74 decision");
        }

        // Gate: no existing V75 overlap
        boolean v75Overlap = mapper.hasExistingV75Overlap(
                intent.companyId(), intent.employeeId(), SHARED_SOURCE_ID,
                intent.businessDate(), intent.expectedIntervalStart(),
                intent.expectedIntervalEnd(), intent.oaAttendanceDocumentId(),
                nowAfterLock);
        if (v75Overlap) {
            throw new IllegalStateException(
                    "Overlapping V75 recognition already exists");
        }

        // Gate: no other consumable OA overtime document overlap
        boolean oaOverlap = mapper.hasOtherConsumableOaOverlap(
                intent.companyId(), SHARED_SOURCE_ID, intent.employeeId(),
                intent.expectedIntervalStart(), intent.expectedIntervalEnd(),
                intent.oaAttendanceDocumentId(), intent.sourceBusinessKey(),
                nowAfterLock);
        if (oaOverlap) {
            throw new IllegalStateException(
                    "Overlapping consumable OA overtime document exists");
        }

        // Insert new recognition
        Instant knowledgeAt = nowAfterLock;
        String auditReference = "resource:" + intent.recognitionId();

        int inserted = mapper.insertRecognition(
                intent.recognitionId(),
                intent.companyId(),
                intent.employeeId(),
                SHARED_SOURCE_ID,
                intent.sourceBusinessKey(),
                intent.sourceVersion(),
                intent.oaAttendanceDocumentId(),
                intent.businessDate(),
                intent.expectedIntervalStart(),
                intent.expectedIntervalEnd(),
                "COMPENSATORY",
                intent.recognizedMinutes(),
                knowledgeAt,
                intent.reason(),
                actorId,
                auditReference,
                intent.userConfirmationText(),
                intent.userConfirmationSha256(),
                intent.evidenceSha256(),
                intent.requestId());

        if (inserted != 1) {
            throw new IllegalStateException(
                    "Insert failed, rows affected: " + inserted);
        }

        // Read back inserted row for consistent receipt
        var insertedRow = mapper.findById(intent.recognitionId());
        if (insertedRow == null) {
            throw new IllegalStateException(
                    "Failed to read back inserted recognition: " + intent.recognitionId());
        }

        // Audit success with truncated reason
        String auditReason = truncateAuditReason(intent.requestId());
        String beforeDigest = "none";
        String afterDigest = computeRowDigest(insertedRow);

        auditService.record(
                actorId,
                "CREATE_OA_OVERTIME_RECOGNITION",
                "attendance_report_oa_overtime_recognition",
                intent.recognitionId(),
                "SUCCESS",
                auditReason,
                beforeDigest,
                afterDigest);

        log.info("Created OA overtime recognition: recognitionId={}, employeeId={}, minutes={}",
                intent.recognitionId(), intent.employeeId(), intent.recognizedMinutes());

        return buildReceipt(insertedRow);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RecognitionReceipt reverse(ReversalIntent intent) {
        // Input validation
        validateUuid(intent.reversalRequestId(), "reversalRequestId");
        validateUuid(intent.recognitionId(), "recognitionId");
        validateUuid(intent.companyId(), "companyId");
        validateUuid(intent.employeeId(), "employeeId");

        if (intent.reversalReason() == null || intent.reversalReason().trim().isEmpty()
                || intent.reversalReason().length() > 1000) {
            throw new IllegalArgumentException("reversalReason must be 1-1000 chars, non-blank");
        }
        if (intent.expectedRecognitionDigest() == null
                || !HEX64_PATTERN.matcher(intent.expectedRecognitionDigest()).matches()) {
            throw new IllegalArgumentException("expectedRecognitionDigest must be 64 hex chars");
        }

        Instant initialNow = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String actorId = principalProvider.currentPrincipalId();

        // Authorization: capability + employee data scope
        capabilityService.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, initialNow, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        // Lock source to serialize operations
        var lockedSource = mapper.lockActiveOaSource(SHARED_SOURCE_ID);
        if (lockedSource == null) {
            throw new IllegalStateException("OA source " + SHARED_SOURCE_ID + " not ACTIVE");
        }

        // Re-take time after lock acquisition
        Instant nowAfterLock = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // Re-authorize after acquiring lock
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, nowAfterLock, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        // Read existing recognition
        var existingRow = mapper.findById(intent.recognitionId());
        if (existingRow == null) {
            throw new IllegalStateException("Recognition not found: " + intent.recognitionId());
        }

        // Validate identity match
        if (!intent.companyId().equals(existingRow.companyId())) {
            throw new IllegalStateException("Company mismatch");
        }
        if (!intent.employeeId().equals(existingRow.employeeId())) {
            throw new IllegalStateException("Employee mismatch");
        }

        // Verify expected digest
        String actualDigest = computeRowDigest(existingRow);
        if (!intent.expectedRecognitionDigest().equals(actualDigest)) {
            throw new IllegalStateException(
                    "Recognition digest mismatch: expected=" + intent.expectedRecognitionDigest()
                    + ", actual=" + actualDigest);
        }

        // Check if already reversed
        if (existingRow.reversedAt() != null) {
            // Check replay: same reversal request
            if (intent.reversalRequestId().equals(existingRow.reversalRequestId())
                    && intent.reversalReason().equals(existingRow.reversalReason())) {
                log.info("Replaying reversal: recognitionId={}, reversalRequestId={}",
                        existingRow.recognitionId(), intent.reversalRequestId());
                return buildReceipt(existingRow);
            }
            throw new IllegalStateException(
                    "Recognition already reversed at " + existingRow.reversedAt()
                    + " by " + existingRow.reversedBy());
        }

        // Perform CAS update
        int updated = mapper.reverseRecognition(
                intent.recognitionId(),
                nowAfterLock,
                actorId,
                intent.reversalReason(),
                intent.reversalRequestId());

        if (updated != 1) {
            throw new IllegalStateException(
                    "CAS reversal failed, rows affected: " + updated);
        }

        // Read back reversed row
        var reversedRow = mapper.findById(intent.recognitionId());
        if (reversedRow == null || reversedRow.reversedAt() == null) {
            throw new IllegalStateException(
                    "Failed to read back reversed recognition: " + intent.recognitionId());
        }

        // Audit reversal
        String auditReason = truncateAuditReason(intent.reversalRequestId());
        String beforeDigest = actualDigest;
        String afterDigest = computeRowDigest(reversedRow);

        auditService.record(
                actorId,
                "REVERSE_OA_OVERTIME_RECOGNITION",
                "attendance_report_oa_overtime_recognition",
                intent.recognitionId(),
                "SUCCESS",
                auditReason,
                beforeDigest,
                afterDigest);

        log.info("Reversed OA overtime recognition: recognitionId={}, reversedAt={}",
                intent.recognitionId(), reversedRow.reversedAt());

        return buildReceipt(reversedRow);
    }

    private RecognitionReceipt handleReplay(
            RecognitionIntent intent,
            OaOvertimeRecognitionPort.RecognitionRow existingRow,
            String actorId,
            Instant now) {

        // Re-authorize for replay
        capabilityService.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, now, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        // Field-by-field comparison of immutable fields
        if (!intent.recognitionId().equals(existingRow.recognitionId())) {
            throw new IllegalStateException("recognitionId mismatch in replay");
        }
        if (!intent.companyId().equals(existingRow.companyId())) {
            throw new IllegalStateException("companyId mismatch in replay");
        }
        if (!intent.employeeId().equals(existingRow.employeeId())) {
            throw new IllegalStateException("employeeId mismatch in replay");
        }
        if (!SHARED_SOURCE_ID.equals(existingRow.attendanceSourceId())) {
            throw new IllegalStateException("attendanceSourceId mismatch in replay");
        }
        if (!intent.sourceBusinessKey().equals(existingRow.sourceBusinessKey())) {
            throw new IllegalStateException("sourceBusinessKey mismatch in replay");
        }
        if (!intent.sourceVersion().equals(existingRow.sourceVersion())) {
            throw new IllegalStateException("sourceVersion mismatch in replay");
        }
        if (!intent.oaAttendanceDocumentId().equals(existingRow.oaAttendanceDocumentId())) {
            throw new IllegalStateException("oaAttendanceDocumentId mismatch in replay");
        }
        if (!intent.businessDate().equals(existingRow.businessDate())) {
            throw new IllegalStateException("businessDate mismatch in replay");
        }
        if (!intent.expectedIntervalStart().equals(existingRow.expectedIntervalStart())) {
            throw new IllegalStateException("expectedIntervalStart mismatch in replay");
        }
        if (!intent.expectedIntervalEnd().equals(existingRow.expectedIntervalEnd())) {
            throw new IllegalStateException("expectedIntervalEnd mismatch in replay");
        }
        if (!"COMPENSATORY".equals(existingRow.overtimeType())) {
            throw new IllegalStateException(
                    "Existing row has non-COMPENSATORY type: " + existingRow.overtimeType());
        }
        if (intent.recognizedMinutes() != existingRow.recognizedMinutes()) {
            throw new IllegalStateException("recognizedMinutes mismatch in replay");
        }
        if (!intent.reason().equals(existingRow.reason())) {
            throw new IllegalStateException("reason mismatch in replay");
        }
        if (!intent.userConfirmationText().equals(existingRow.userConfirmationText())) {
            throw new IllegalStateException("userConfirmationText mismatch in replay");
        }
        if (!intent.userConfirmationSha256().equals(existingRow.userConfirmationSha256())) {
            throw new IllegalStateException("userConfirmationSha256 mismatch in replay");
        }
        if (!intent.evidenceSha256().equals(existingRow.evidenceSha256())) {
            throw new IllegalStateException("evidenceSha256 mismatch in replay");
        }

        log.info("Replaying OA overtime recognition: recognitionId={}, requestId={}",
                existingRow.recognitionId(), intent.requestId());

        return buildReceipt(existingRow);
    }

    private RecognitionReceipt buildReceipt(OaOvertimeRecognitionPort.RecognitionRow row) {
        return new RecognitionReceipt(
                row.recognitionId(),
                row.knowledgeAt(),
                computeRowDigest(row),
                row.reversedAt(),
                row.reversedBy());
    }

    private String computeRowDigest(OaOvertimeRecognitionPort.RecognitionRow row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Length-prefixed canonical serialization to avoid field boundary ambiguity
            addLengthPrefixedField(digest, row.recognitionId());
            addLengthPrefixedField(digest, row.companyId());
            addLengthPrefixedField(digest, row.employeeId());
            addLengthPrefixedField(digest, row.attendanceSourceId());
            addLengthPrefixedField(digest, row.sourceBusinessKey());
            addLengthPrefixedField(digest, row.sourceVersion());
            addLengthPrefixedField(digest, row.oaAttendanceDocumentId());
            addLengthPrefixedField(digest, row.businessDate().toString());
            addLengthPrefixedField(digest, row.expectedIntervalStart().toString());
            addLengthPrefixedField(digest, row.expectedIntervalEnd().toString());
            addLengthPrefixedField(digest, row.overtimeType());
            addLengthPrefixedField(digest, String.valueOf(row.recognizedMinutes()));
            addLengthPrefixedField(digest, row.knowledgeAt().toString());
            addLengthPrefixedField(digest, row.reason());
            addLengthPrefixedField(digest, row.userConfirmationText());
            addLengthPrefixedField(digest, row.userConfirmationSha256());
            addLengthPrefixedField(digest, row.evidenceSha256());
            addLengthPrefixedField(digest, row.requestId());

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void addLengthPrefixedField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(bytes.length);
        digest.update(buffer.array());
        digest.update(bytes);
    }

    private String computeSha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String truncateAuditReason(String requestId) {
        String full = "req=" + requestId;
        return full.length() <= 64 ? full : full.substring(0, 64);
    }

    private void validateUuid(String value, String fieldName) {
        if (value == null || value.length() != 36) {
            throw new IllegalArgumentException(fieldName + " must be exactly 36 characters: " + value);
        }
        if (!UUID_PATTERN.matcher(value.toLowerCase()).matches()) {
            throw new IllegalArgumentException(fieldName + " not valid UUID format: " + value);
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " not valid UUID: " + value, e);
        }
    }

    public record RecognitionIntent(
            String requestId,
            String recognitionId,
            String companyId,
            String employeeId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            LocalDate businessDate,
            Instant expectedIntervalStart,
            Instant expectedIntervalEnd,
            int recognizedMinutes,
            String reason,
            String userConfirmationText,
            String userConfirmationSha256,
            String evidenceSha256) {
    }

    public record RecognitionReceipt(
            String recognitionId,
            Instant knowledgeAt,
            String recognitionDigest,
            Instant reversedAt,
            String reversedBy) {
    }

    public record ReversalIntent(
            String reversalRequestId,
            String recognitionId,
            String companyId,
            String employeeId,
            String expectedRecognitionDigest,
            String reversalReason) {
    }
}
