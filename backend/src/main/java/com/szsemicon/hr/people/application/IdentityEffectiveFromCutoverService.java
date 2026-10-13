package com.szsemicon.hr.people.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.identityaccess.application.AuditPersistence;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AuditRecord;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.Blocked;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.Candidate;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.CutoverResult;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.Diagnosis;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverMapper;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityEffectiveFromCutoverService {

    static final LocalDate CUTOFF = LocalDate.of(2026, 1, 1);

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final IdentityEffectiveFromCutoverMapper mapper;
    private final AuditPersistence audits;
    private final Clock clock;

    public IdentityEffectiveFromCutoverService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            IdentityEffectiveFromCutoverMapper mapper,
            AuditPersistence audits,
            Clock clock) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.mapper = mapper;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Diagnosis diagnose() {
        capabilities.require(CapabilityCodes.IDENTITY_EFFECTIVE_FROM_CUTOVER);
        List<Candidate> movable = mapper.listMovable(CUTOFF);
        List<Candidate> applied = mapper.listAlreadyApplied(CUTOFF);
        List<Blocked> blocked = mapper.listBlocked(CUTOFF);
        return new Diagnosis(
                CUTOFF,
                movable,
                applied,
                blocked,
                blocked.isEmpty());
    }

    @Transactional
    public CutoverResult execute(String requestId, String reason) {
        capabilities.require(CapabilityCodes.IDENTITY_EFFECTIVE_FROM_CUTOVER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST, "REQUEST_ID_INVALID", "请求标识无效");
        }
        if (reason == null || reason.trim().length() < 2) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST, "CHANGE_REASON_INVALID", "变更原因至少 2 个字符");
        }
        String actor = principals.currentPrincipalId();
        String existing = mapper.findRunStatusByRequest(actor, requestId);
        if (existing != null) {
            return new CutoverResult(
                    requestId,
                    "ALREADY_APPLIED",
                    0,
                    0,
                    clock.instant(),
                    List.of());
        }
        Diagnosis diagnosis = new Diagnosis(
                CUTOFF,
                mapper.listMovable(CUTOFF),
                mapper.listAlreadyApplied(CUTOFF),
                mapper.listBlocked(CUTOFF),
                mapper.listBlocked(CUTOFF).isEmpty());
        String runId = UUID.randomUUID().toString();
        var now = clock.instant();
        if (!diagnosis.blocked().isEmpty()) {
            mapper.insertRun(
                    runId, requestId, actor, reason.trim(), CUTOFF,
                    "REFUSED", 0, diagnosis.blocked().size(), now);
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "IDENTITY_CUTOVER_UNSAFE",
                    "存在无法安全前移的对象，已整批停止");
        }
        if (diagnosis.movable().isEmpty()) {
            mapper.insertRun(
                    runId, requestId, actor, reason.trim(), CUTOFF,
                    "ALREADY_APPLIED", 0, 0, now);
            return new CutoverResult(
                    runId, "ALREADY_APPLIED", 0, 0, now, List.of());
        }
        for (Candidate candidate : diagnosis.movable()) {
            mapper.insertItem(
                    UUID.randomUUID().toString(),
                    runId,
                    candidate.objectType(),
                    candidate.objectId(),
                    candidate.ownerId(),
                    candidate.currentFrom(),
                    CUTOFF);
        }
        int updated = mapper.moveEmployeeVersions(CUTOFF)
                + mapper.moveAssignments(CUTOFF)
                + mapper.moveOrganizationVersions(CUTOFF)
                + mapper.moveGroupAssignments(CUTOFF)
                + mapper.moveGroupRevisions(CUTOFF)
                + mapper.moveCalendarVersions(CUTOFF)
                + mapper.movePolicyBindings(CUTOFF);
        mapper.insertRun(
                runId, requestId, actor, reason.trim(), CUTOFF,
                "COMMITTED", updated, 0, now);
        audits.appendAudit(new AuditRecord(
                UUID.randomUUID().toString(),
                now,
                actor,
                actor,
                "IDENTITY_EFFECTIVE_FROM_CUTOVER",
                "IDENTITY_CLOCK",
                runId,
                "COMMITTED",
                reason.trim(),
                requestId,
                requestId,
                digest("before", Integer.toString(diagnosis.movable().size())),
                digest("after", Integer.toString(updated)),
                digest(runId, requestId, Integer.toString(updated))));
        return new CutoverResult(runId, "COMMITTED", updated, 0, now, List.of());
    }

    private static String digest(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part)
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
