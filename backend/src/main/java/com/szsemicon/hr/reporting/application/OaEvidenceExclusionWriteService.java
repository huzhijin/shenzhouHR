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
 * V74 OA evidence exclusion thin write service.
 * Corrected: geometry from immutable normalized record (not latest OA), field-by-field replay,
 * MICROS truncation, real LEAVE/ANNUAL validation, audit reason truncation.
 */
@Service
public class OaEvidenceExclusionWriteService {

    private static final Logger log =
            LoggerFactory.getLogger(OaEvidenceExclusionWriteService.class);

    private static final String SHARED_SOURCE_ID = "e05866a7-97b0-11f1-8924-00155d09fd0e";
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final java.util.regex.Pattern HEX64_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$");
    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final OaEvidenceExclusionPort mapper;
    private final AttendanceEvidenceRepository evidenceRepository;
    private final AuditService auditService;
    private final CurrentCapabilityService capabilityService;
    private final EmployeeAuthorizationPort employeeAuthorizationPort;
    private final CurrentPrincipalProvider principalProvider;
    private final Clock clock;
    private final java.util.List<com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort> oaSourcePorts;

    public OaEvidenceExclusionWriteService(
            OaEvidenceExclusionPort mapper,
            AttendanceEvidenceRepository evidenceRepository,
            AuditService auditService,
            CurrentCapabilityService capabilityService,
            EmployeeAuthorizationPort employeeAuthorizationPort,
            CurrentPrincipalProvider principalProvider,
            Clock clock,
            java.util.List<com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort> oaSourcePorts) {
        this.mapper = mapper;
        this.evidenceRepository = evidenceRepository;
        this.auditService = auditService;
        this.capabilityService = capabilityService;
        this.employeeAuthorizationPort = employeeAuthorizationPort;
        this.principalProvider = principalProvider;
        this.clock = clock;
        this.oaSourcePorts = oaSourcePorts;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExclusionReceipt exclude(ExclusionIntent intent) {
        // Input validation
        validateUuid(intent.requestId(), "requestId");
        validateUuid(intent.exclusionId(), "exclusionId");
        validateUuid(intent.companyId(), "companyId");
        validateUuid(intent.employeeId(), "employeeId");
        validateUuid(intent.oaAttendanceDocumentId(), "oaAttendanceDocumentId");

        if (intent.sourceBusinessKey() == null || intent.sourceBusinessKey().isBlank()) {
            throw new IllegalArgumentException("sourceBusinessKey cannot be blank");
        }
        if (intent.sourceVersion() == null || intent.sourceVersion().isBlank()) {
            throw new IllegalArgumentException("sourceVersion cannot be blank");
        }
        if (intent.expectedBusinessDate() == null) {
            throw new IllegalArgumentException("expectedBusinessDate cannot be null");
        }
        if (intent.expectedIntervalStart() == null || intent.expectedIntervalEnd() == null) {
            throw new IllegalArgumentException("expected interval bounds cannot be null");
        }
        if (!intent.expectedIntervalEnd().isAfter(intent.expectedIntervalStart())) {
            throw new IllegalArgumentException("expected interval end must be after start");
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

        // Re-take time after lock acquisition
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

        // Fetch immutable geometry from the EXACT version's normalized record
        var geometry = mapper.findDocumentGeometry(
                intent.oaAttendanceDocumentId(),
                SHARED_SOURCE_ID,
                intent.sourceBusinessKey(),
                intent.sourceVersion());

        if (geometry == null) {
            throw new IllegalStateException(
                    "Document geometry not found for: " + intent.oaAttendanceDocumentId());
        }

        // Gate: version and identity already matched by query WHERE clause
        // Gate: validation_status = VALID (query)
        // Gate: match_status = MATCHED (query)

        // Gate: source status must be APPROVED
        if (!"APPROVED".equals(geometry.sourceStatus())) {
            throw new IllegalStateException(
                    "Source status not APPROVED: " + geometry.sourceStatus());
        }

        // Gate: document type must be LEAVE
        if (!"LEAVE".equals(geometry.documentType())) {
            throw new IllegalStateException(
                    "Document type not LEAVE: " + geometry.documentType());
        }

        // Gate: leave type must be ANNUAL
        if (!"ANNUAL".equals(geometry.leaveType())) {
            throw new IllegalStateException(
                    "Leave type not ANNUAL: " + geometry.leaveType());
        }

        // Gate: employee match
        if (!intent.employeeId().equals(geometry.employeeId())) {
            throw new IllegalStateException(
                    "Employee mismatch: expected=" + intent.employeeId()
                    + ", matched=" + geometry.employeeId());
        }

        // Gate: company match (from actual employee.company_id)
        if (!intent.companyId().equals(geometry.companyId())) {
            throw new IllegalStateException(
                    "Company mismatch: expected=" + intent.companyId()
                    + ", employee company=" + geometry.companyId());
        }

        // Gate: geometry must match (normalized interval from this exact version)
        if (!intent.expectedIntervalStart().equals(geometry.intervalStart())
                || !intent.expectedIntervalEnd().equals(geometry.intervalEnd())) {
            throw new IllegalStateException(
                    "Interval mismatch: expected [" + intent.expectedIntervalStart()
                    + "," + intent.expectedIntervalEnd() + "], actual ["
                    + geometry.intervalStart() + "," + geometry.intervalEnd() + "]");
        }

        // Gate: business date must match (reconstruct from normalized interval in Shanghai time)
        LocalDate actualStartDate = geometry.intervalStart().atZone(SHANGHAI_ZONE).toLocalDate();
        LocalDate actualEndDate = intent.expectedIntervalEnd().minusNanos(1)
                .atZone(SHANGHAI_ZONE).toLocalDate();
        if (!intent.expectedBusinessDate().equals(actualStartDate)
                || !intent.expectedBusinessDate().equals(actualEndDate)) {
            throw new IllegalStateException(
                    "Business date mismatch or cross-day interval: expected="
                    + intent.expectedBusinessDate() + ", actual start=" + actualStartDate
                    + ", end=" + actualEndDate);
        }

        // Insert exclusion
        Instant knowledgeAt = nowAfterLock;
        String auditReference = "resource:" + intent.exclusionId();

        int inserted = mapper.insertExclusion(
                intent.exclusionId(),
                intent.companyId(),
                intent.employeeId(),
                SHARED_SOURCE_ID,
                intent.sourceBusinessKey(),
                intent.sourceVersion(),
                intent.oaAttendanceDocumentId(),
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
        var insertedRow = mapper.findById(intent.exclusionId());
        if (insertedRow == null) {
            throw new IllegalStateException(
                    "Failed to read back inserted exclusion: " + intent.exclusionId());
        }

        // Audit success with truncated reason
        String auditReason = truncateAuditReason(intent.requestId());
        String beforeDigest = "none";
        String afterDigest = computeRowDigest(insertedRow);

        auditService.record(
                actorId,
                "CREATE_OA_EVIDENCE_EXCLUSION",
                "attendance_report_oa_evidence_exclusion",
                intent.exclusionId(),
                "SUCCESS",
                auditReason,
                beforeDigest,
                afterDigest);

        log.info("Created OA evidence exclusion: exclusionId={}, employeeId={}, doc={}",
                intent.exclusionId(), intent.employeeId(), intent.oaAttendanceDocumentId());

        return buildReceipt(insertedRow);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExclusionReceipt reverse(ReversalIntent intent) {
        // Input validation
        validateUuid(intent.reversalRequestId(), "reversalRequestId");
        validateUuid(intent.exclusionId(), "exclusionId");
        validateUuid(intent.companyId(), "companyId");
        validateUuid(intent.employeeId(), "employeeId");

        if (intent.reversalReason() == null || intent.reversalReason().trim().isEmpty()
                || intent.reversalReason().length() > 1000) {
            throw new IllegalArgumentException("reversalReason must be 1-1000 chars, non-blank");
        }
        if (intent.expectedExclusionDigest() == null
                || !HEX64_PATTERN.matcher(intent.expectedExclusionDigest()).matches()) {
            throw new IllegalArgumentException("expectedExclusionDigest must be 64 hex chars");
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

        // Read existing exclusion
        var existingRow = mapper.findById(intent.exclusionId());
        if (existingRow == null) {
            throw new IllegalStateException("Exclusion not found: " + intent.exclusionId());
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
        if (!intent.expectedExclusionDigest().equals(actualDigest)) {
            throw new IllegalStateException(
                    "Exclusion digest mismatch: expected=" + intent.expectedExclusionDigest()
                    + ", actual=" + actualDigest);
        }

        // Check if already reversed
        if (existingRow.reversedAt() != null) {
            // Check replay: same reversal request
            if (intent.reversalRequestId().equals(existingRow.reversalRequestId())
                    && intent.reversalReason().equals(existingRow.reversalReason())) {
                log.info("Replaying reversal: exclusionId={}, reversalRequestId={}",
                        existingRow.exclusionId(), intent.reversalRequestId());
                return buildReceipt(existingRow);
            }
            throw new IllegalStateException(
                    "Exclusion already reversed at " + existingRow.reversedAt()
                    + " by " + existingRow.reversedBy());
        }

        // Perform CAS update
        int updated = mapper.reverseExclusion(
                intent.exclusionId(),
                nowAfterLock,
                actorId,
                intent.reversalReason(),
                intent.reversalRequestId());

        if (updated != 1) {
            throw new IllegalStateException(
                    "CAS reversal failed, rows affected: " + updated);
        }

        // Read back reversed row
        var reversedRow = mapper.findById(intent.exclusionId());
        if (reversedRow == null || reversedRow.reversedAt() == null) {
            throw new IllegalStateException(
                    "Failed to read back reversed exclusion: " + intent.exclusionId());
        }

        // Audit reversal
        String auditReason = truncateAuditReason(intent.reversalRequestId());
        String beforeDigest = actualDigest;
        String afterDigest = computeRowDigest(reversedRow);

        auditService.record(
                actorId,
                "REVERSE_OA_EVIDENCE_EXCLUSION",
                "attendance_report_oa_evidence_exclusion",
                intent.exclusionId(),
                "SUCCESS",
                auditReason,
                beforeDigest,
                afterDigest);

        log.info("Reversed OA evidence exclusion: exclusionId={}, reversedAt={}",
                intent.exclusionId(), reversedRow.reversedAt());

        return buildReceipt(reversedRow);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExclusionReceipt excludeConfirmedSourceDeletion(ConfirmedSourceDeletionIntent intent) {
        validateUuid(intent.requestId(), "requestId");
        validateUuid(intent.exclusionId(), "exclusionId");
        validateUuid(intent.companyId(), "companyId");
        validateUuid(intent.employeeId(), "employeeId");
        validateUuid(intent.oaAttendanceDocumentId(), "oaAttendanceDocumentId");

        if (intent.sourceBusinessKey() == null || intent.sourceBusinessKey().isBlank()) {
            throw new IllegalArgumentException("sourceBusinessKey cannot be blank");
        }
        if (intent.sourceVersion() == null || intent.sourceVersion().isBlank()) {
            throw new IllegalArgumentException("sourceVersion cannot be blank");
        }
        if (intent.expectedIntervalStart() == null || intent.expectedIntervalEnd() == null) {
            throw new IllegalArgumentException("expected interval bounds cannot be null");
        }
        if (!intent.expectedIntervalEnd().isAfter(intent.expectedIntervalStart())) {
            throw new IllegalArgumentException("expected interval end must be after start");
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

        capabilityService.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, initialNow, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        var existingRow = mapper.findByRequest(
                intent.requestId(), intent.oaAttendanceDocumentId());

        if (existingRow != null) {
            return handleConfirmedDeletionReplay(intent, existingRow, actorId, initialNow);
        }

        var lockedSource = mapper.lockActiveOaSource(SHARED_SOURCE_ID);
        if (lockedSource == null) {
            throw new IllegalStateException("OA source " + SHARED_SOURCE_ID + " not ACTIVE");
        }

        Instant nowAfterLock = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, nowAfterLock, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        existingRow = mapper.findByRequest(
                intent.requestId(), intent.oaAttendanceDocumentId());
        if (existingRow != null) {
            return handleConfirmedDeletionReplay(intent, existingRow, actorId, nowAfterLock);
        }

        var latest = evidenceRepository.findLatestOaObservation(
                SHARED_SOURCE_ID, intent.sourceBusinessKey(), false);

        var resolved = evidenceRepository.findLatestOaObservation(
                SHARED_SOURCE_ID, intent.sourceBusinessKey(), true);

        var observation = (latest != null
                           && latest.oaAttendanceDocumentId() != null
                           && resolved != null)
                          ? resolved
                          : latest;

        if (observation == null || observation.oaAttendanceDocumentId() == null) {
            throw new IllegalStateException(
                    "Latest OA observation for key " + intent.sourceBusinessKey()
                    + " is unparsed or missing, refusing write");
        }

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

        var geometry = mapper.findDocumentGeometry(
                intent.oaAttendanceDocumentId(),
                SHARED_SOURCE_ID,
                intent.sourceBusinessKey(),
                intent.sourceVersion());

        if (geometry == null) {
            throw new IllegalStateException(
                    "Document geometry not found for: " + intent.oaAttendanceDocumentId());
        }

        if (!"OVERTIME".equals(geometry.documentType())) {
            throw new IllegalStateException(
                    "Document type not OVERTIME: " + geometry.documentType());
        }

        if (!intent.employeeId().equals(geometry.employeeId())) {
            throw new IllegalStateException(
                    "Employee mismatch: expected=" + intent.employeeId()
                    + ", matched=" + geometry.employeeId());
        }

        if (!intent.companyId().equals(geometry.companyId())) {
            throw new IllegalStateException(
                    "Company mismatch: expected=" + intent.companyId()
                    + ", employee company=" + geometry.companyId());
        }

        if (!intent.expectedIntervalStart().equals(geometry.intervalStart())
                || !intent.expectedIntervalEnd().equals(geometry.intervalEnd())) {
            throw new IllegalStateException(
                    "Interval mismatch: expected [" + intent.expectedIntervalStart()
                    + "," + intent.expectedIntervalEnd() + "], actual ["
                    + geometry.intervalStart() + "," + geometry.intervalEnd() + "]");
        }

        long overtimeDetailId;
        try {
            if (!intent.sourceBusinessKey().startsWith("OVERTIME:")) {
                throw new IllegalStateException("sourceBusinessKey must start with OVERTIME:");
            }
            overtimeDetailId = Long.parseLong(intent.sourceBusinessKey().substring("OVERTIME:".length()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid overtime detail ID in sourceBusinessKey", e);
        }

        if (oaSourcePorts.isEmpty()) {
            throw new IllegalStateException(
                    "No OA source ports available: confirmed source deletion requires at least one configured adapter");
        }

        if (oaSourcePorts.size() > 1) {
            throw new IllegalStateException(
                    "Multiple OA source ports found (" + oaSourcePorts.size()
                    + "): confirmed source deletion requires exactly one adapter");
        }

        com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort sourcePort = oaSourcePorts.get(0);

        var presence = sourcePort.checkOvertimeDocumentPresence(SHARED_SOURCE_ID, overtimeDetailId);

        if (presence != com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OvertimeDocumentPresence.ABSENT) {
            throw new IllegalStateException(
                    "Source overtime document presence check failed: " + presence
                    + " (only ABSENT is allowed for confirmed deletion exclusion)");
        }

        Instant knowledgeAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, knowledgeAt, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        String auditReference = "resource:" + intent.exclusionId();

        int inserted = mapper.insertExclusion(
                intent.exclusionId(),
                intent.companyId(),
                intent.employeeId(),
                SHARED_SOURCE_ID,
                intent.sourceBusinessKey(),
                intent.sourceVersion(),
                intent.oaAttendanceDocumentId(),
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

        var insertedRow = mapper.findById(intent.exclusionId());
        if (insertedRow == null) {
            throw new IllegalStateException(
                    "Failed to read back inserted exclusion: " + intent.exclusionId());
        }

        String auditReason = truncateAuditReason(intent.requestId());
        String beforeDigest = "none";
        String afterDigest = computeRowDigest(insertedRow);

        auditService.record(
                actorId,
                "CREATE_OA_EVIDENCE_EXCLUSION_CONFIRMED_SOURCE_DELETION",
                "attendance_report_oa_evidence_exclusion",
                intent.exclusionId(),
                "SUCCESS",
                auditReason,
                beforeDigest,
                afterDigest);

        log.info("Created OA confirmed source deletion exclusion: exclusionId={}, employeeId={}, doc={}",
                intent.exclusionId(), intent.employeeId(), intent.oaAttendanceDocumentId());

        return buildReceipt(insertedRow);
    }

    private ExclusionReceipt handleConfirmedDeletionReplay(
            ConfirmedSourceDeletionIntent intent,
            OaEvidenceExclusionPort.ExclusionRow existingRow,
            String actorId,
            Instant now) {

        capabilityService.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, now, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        if (!intent.exclusionId().equals(existingRow.exclusionId())) {
            throw new IllegalStateException("exclusionId mismatch in replay");
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

        var geometry = mapper.findDocumentGeometry(
                existingRow.oaAttendanceDocumentId(),
                SHARED_SOURCE_ID,
                existingRow.sourceBusinessKey(),
                existingRow.sourceVersion());

        if (geometry == null) {
            throw new IllegalStateException(
                    "Cannot verify replay: original document geometry no longer available for "
                    + existingRow.oaAttendanceDocumentId());
        }

        if (!"OVERTIME".equals(geometry.documentType())) {
            throw new IllegalStateException("documentType mismatch in replay: expected OVERTIME");
        }
        if (!intent.expectedIntervalStart().equals(geometry.intervalStart())) {
            throw new IllegalStateException("expectedIntervalStart mismatch in replay");
        }
        if (!intent.expectedIntervalEnd().equals(geometry.intervalEnd())) {
            throw new IllegalStateException("expectedIntervalEnd mismatch in replay");
        }

        log.info("Replaying OA confirmed source deletion exclusion: exclusionId={}, requestId={}",
                existingRow.exclusionId(), intent.requestId());

        return buildReceipt(existingRow);
    }

    private ExclusionReceipt handleReplay(
            ExclusionIntent intent,
            OaEvidenceExclusionPort.ExclusionRow existingRow,
            String actorId,
            Instant now) {

        // Re-authorize for replay
        capabilityService.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.ATTENDANCE_REPORT_REFRESH, now, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        // Field-by-field comparison (V74 doesn't store geometry, must reconstruct from document)
        if (!intent.exclusionId().equals(existingRow.exclusionId())) {
            throw new IllegalStateException("exclusionId mismatch in replay");
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

        // Verify geometry from persisted version (NOT latest OA version)
        var geometry = mapper.findDocumentGeometry(
                existingRow.oaAttendanceDocumentId(),
                SHARED_SOURCE_ID,
                existingRow.sourceBusinessKey(),
                existingRow.sourceVersion());

        if (geometry == null) {
            throw new IllegalStateException(
                    "Cannot verify replay: original document geometry no longer available for "
                    + existingRow.oaAttendanceDocumentId());
        }

        // Gate: must be APPROVED LEAVE/ANNUAL same-day for annual endpoint replay
        if (!"APPROVED".equals(geometry.sourceStatus())) {
            throw new IllegalStateException(
                    "Replay validation failed: source status not APPROVED: " + geometry.sourceStatus());
        }
        if (!"LEAVE".equals(geometry.documentType())) {
            throw new IllegalStateException(
                    "Replay validation failed: document type not LEAVE: " + geometry.documentType());
        }
        if (!"ANNUAL".equals(geometry.leaveType())) {
            throw new IllegalStateException(
                    "Replay validation failed: leave type not ANNUAL: " + geometry.leaveType());
        }

        LocalDate startDate = geometry.intervalStart().atZone(SHANGHAI_ZONE).toLocalDate();
        LocalDate endDate = geometry.intervalEnd().minusNanos(1).atZone(SHANGHAI_ZONE).toLocalDate();
        if (!startDate.equals(endDate)) {
            throw new IllegalStateException(
                    "Replay validation failed: interval not same-day: start=" + startDate + ", end=" + endDate);
        }

        if (!intent.expectedBusinessDate().equals(startDate)) {
            throw new IllegalStateException("expectedBusinessDate mismatch in replay");
        }
        if (!intent.expectedIntervalStart().equals(geometry.intervalStart())) {
            throw new IllegalStateException("expectedIntervalStart mismatch in replay");
        }
        if (!intent.expectedIntervalEnd().equals(geometry.intervalEnd())) {
            throw new IllegalStateException("expectedIntervalEnd mismatch in replay");
        }

        log.info("Replaying OA evidence exclusion: exclusionId={}, requestId={}",
                existingRow.exclusionId(), intent.requestId());

        return buildReceipt(existingRow);
    }

    private ExclusionReceipt buildReceipt(OaEvidenceExclusionPort.ExclusionRow row) {
        return new ExclusionReceipt(
                row.exclusionId(),
                row.knowledgeAt(),
                computeRowDigest(row),
                row.reversedAt(),
                row.reversedBy());
    }

    private String computeRowDigest(OaEvidenceExclusionPort.ExclusionRow row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Length-prefixed canonical serialization
            addLengthPrefixedField(digest, row.exclusionId());
            addLengthPrefixedField(digest, row.companyId());
            addLengthPrefixedField(digest, row.employeeId());
            addLengthPrefixedField(digest, row.attendanceSourceId());
            addLengthPrefixedField(digest, row.sourceBusinessKey());
            addLengthPrefixedField(digest, row.sourceVersion());
            addLengthPrefixedField(digest, row.oaAttendanceDocumentId());
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

    public record ExclusionIntent(
            String requestId,
            String exclusionId,
            String companyId,
            String employeeId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            LocalDate expectedBusinessDate,
            Instant expectedIntervalStart,
            Instant expectedIntervalEnd,
            String reason,
            String userConfirmationText,
            String userConfirmationSha256,
            String evidenceSha256) {
    }

    public record ExclusionReceipt(
            String exclusionId,
            Instant knowledgeAt,
            String exclusionDigest,
            Instant reversedAt,
            String reversedBy) {
    }

    public record ReversalIntent(
            String reversalRequestId,
            String exclusionId,
            String companyId,
            String employeeId,
            String expectedExclusionDigest,
            String reversalReason) {
    }

    public record ConfirmedSourceDeletionIntent(
            String requestId,
            String exclusionId,
            String companyId,
            String employeeId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            Instant expectedIntervalStart,
            Instant expectedIntervalEnd,
            String reason,
            String userConfirmationText,
            String userConfirmationSha256,
            String evidenceSha256) {
    }
}
