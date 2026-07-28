package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.validation.IdempotencyKeyPolicy;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DeliSourceRegistrationService {

    private static final Pattern SOURCE_CODE =
            Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,63}");
    private static final Pattern SECRET_REFERENCE =
            Pattern.compile("[A-Z][A-Z0-9_]{2,127}");
    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final DeliSourceRegistrationRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public DeliSourceRegistrationService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            DeliSourceRegistrationRepository repository,
            AuditService auditService,
            Clock clock) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    public DeliSourceRegistrationModels.SourceView register(
            DeliSourceRegistrationModels.Command command,
            String idempotencyKey) {
        validate(command, idempotencyKey);
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_CONFIGURE);
        String principalId = principalProvider.currentPrincipalId();
        var at = clock.instant();
        String requestDigest = requestDigest(command);
        String snapshotDigest = AttendanceEvidenceDigests.sha256(
                "DELI_SOURCE_CONFIG_V1",
                command.legalEntityId(),
                command.sourceCode(),
                command.displayName(),
                command.sourceTimeZone(),
                Integer.toString(command.pageSize()),
                Integer.toString(command.rateLimitPerMinute()),
                Integer.toString(command.backoffSeconds()),
                command.secretReferenceName(),
                "DELI_EPLUS_CHECKIN_QUERY",
                "CONFIRMED_DELI_BINDING",
                "DROP_FORBIDDEN_PAYLOAD");
        DeliSourceRegistrationRepository.RegistrationResult result;
        try {
            result = repository.register(
                    command,
                    principalId,
                    CapabilityCodes.ATTENDANCE_SOURCE_CONFIGURE,
                    idempotencyKey,
                    requestDigest,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    snapshotDigest,
                    at);
        } catch (RuntimeException exception) {
            auditService.recordFailure(
                    principalId,
                    "DELI_SOURCE_REGISTER",
                    "ATTENDANCE_SOURCE",
                    command.sourceCode(),
                    "FAILURE",
                    "DELI_SOURCE_REGISTRATION_FAILED");
            throw exception;
        }
        if (result.state()
                == DeliSourceRegistrationRepository.RegistrationState.CREATED) {
            auditService.record(
                    principalId,
                    "DELI_SOURCE_REGISTER",
                    "ATTENDANCE_SOURCE",
                    Objects.requireNonNull(result.source()).sourceId(),
                    "SUCCESS",
                    "DELI_SOURCE_REGISTERED");
        } else if (result.state()
                == DeliSourceRegistrationRepository.RegistrationState.REPLAYED) {
            auditService.record(
                    principalId,
                    "DELI_SOURCE_REGISTER",
                    "ATTENDANCE_SOURCE",
                    Objects.requireNonNull(result.source()).sourceId(),
                    "SUCCESS",
                    "IDEMPOTENCY_REPLAY");
        } else {
            auditService.recordFailure(
                    principalId,
                    "DELI_SOURCE_REGISTER",
                    "ATTENDANCE_SOURCE",
                    command.sourceCode(),
                    "FAILURE",
                    result.state().name());
        }
        return switch (result.state()) {
            case CREATED -> Objects.requireNonNull(result.source());
            case REPLAYED -> Objects.requireNonNull(result.source()).asReplay();
            case RESOURCE_UNAVAILABLE ->
                    throw new ResourceNotAvailableAccessDeniedException();
            case IDEMPOTENCY_CONFLICT -> throw conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "幂等键已用于不同的数据源配置");
            case SOURCE_CODE_CONFLICT -> throw conflict(
                    "ATTENDANCE_SOURCE_CODE_CONFLICT",
                    "当前法人下已存在相同的数据源编码");
            case CREDENTIAL_REFERENCE_CONFLICT -> throw conflict(
                    "DELI_CREDENTIAL_REFERENCE_CONFLICT",
                    "该得力凭据引用已绑定到其他启用的数据源");
        };
    }

    private static String requestDigest(
            DeliSourceRegistrationModels.Command command) {
        return AttendanceEvidenceDigests.sha256(
                "DELI_SOURCE_REGISTER_V1",
                command.legalEntityId(),
                command.sourceCode(),
                command.displayName(),
                command.sourceTimeZone(),
                Integer.toString(command.pageSize()),
                Integer.toString(command.rateLimitPerMinute()),
                Integer.toString(command.backoffSeconds()),
                command.secretReferenceName(),
                command.reason());
    }

    private static void validate(
            DeliSourceRegistrationModels.Command command,
            String idempotencyKey) {
        Objects.requireNonNull(command, "command");
        requireReference(command.legalEntityId(), 36);
        if (!SOURCE_CODE.matcher(command.sourceCode()).matches()) {
            throw new IllegalArgumentException("invalid source code");
        }
        requireReference(command.displayName(), 100);
        requireReference(command.sourceTimeZone(), 64);
        try {
            ZoneId.of(command.sourceTimeZone());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid source time zone");
        }
        if (command.pageSize() < 1
                || command.pageSize() > 500
                || command.rateLimitPerMinute() < 60
                || command.rateLimitPerMinute() > 10_000
                || command.backoffSeconds() < 0
                || command.backoffSeconds() > 5) {
            throw new IllegalArgumentException(
                    "invalid source configuration limits");
        }
        if (!SECRET_REFERENCE
                .matcher(command.secretReferenceName())
                .matches()) {
            throw new IllegalArgumentException(
                    "invalid credential reference name");
        }
        requireReference(command.reason(), 500);
        if (command.reason().length() < 2) {
            throw new IllegalArgumentException("source reason is too short");
        }
        if (!IdempotencyKeyPolicy.isValid(idempotencyKey)) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
    }

    private static void requireReference(String value, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid source configuration");
        }
    }

    private static ApiProblemException conflict(
            String code, String message) {
        return new ApiProblemException(
                HttpStatus.CONFLICT, code, message);
    }
}
