package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exact, fail-closed employee resolution for attendance evidence ingestion.
 *
 * <p>Employee numbers and confirmed Deli binding values use binary database
 * collation and are never trimmed, case-folded or coerced to numbers. Multiple
 * active employment periods intentionally remain multiple results so the
 * caller quarantines the evidence as ambiguous.</p>
 */
@Repository
public class MyBatisEmployeeEmploymentResolver
        implements EmployeeEmploymentResolverPort {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_REFERENCE_LENGTH = 128;

    private final EvidenceEmployeeResolverMapper mapper;

    public MyBatisEmployeeEmploymentResolver(
            EvidenceEmployeeResolverMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Resolution> resolveByEmployeeNumber(
            String legalEntityId, String employeeNumber, Instant at) {
        requireIdentifier(legalEntityId, "legalEntityId", 36);
        requireReference(employeeNumber, "employeeNumber");
        Objects.requireNonNull(at, "at");
        return resolutions(mapper.resolveByEmployeeNumber(
                legalEntityId,
                employeeNumber,
                at.atZone(BUSINESS_ZONE).toLocalDate()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Resolution> resolveByConfirmedBinding(
            String legalEntityId,
            String locationId,
            String deviceId,
            ConfirmedBindingKind bindingKind,
            String externalPersonRef,
            Instant at) {
        requireIdentifier(legalEntityId, "legalEntityId", 36);
        optionalReference(locationId, "locationId", 36);
        optionalReference(deviceId, "deviceId", 191);
        Objects.requireNonNull(bindingKind, "bindingKind");
        requireReference(externalPersonRef, "externalPersonRef");
        Objects.requireNonNull(at, "at");
        return resolutions(mapper.resolveByConfirmedDeliBinding(
                legalEntityId,
                bindingKind.name(),
                externalPersonRef,
                at.atZone(BUSINESS_ZONE).toLocalDateTime(),
                at.atZone(BUSINESS_ZONE).toLocalDate()));
    }

    private static List<Resolution> resolutions(
            List<EvidenceEmployeeResolverRow> rows) {
        if (rows == null) {
            throw new IllegalStateException(
                    "employee resolver persistence returned null");
        }
        return rows.stream()
                .map(row -> new Resolution(
                        requireStored(row.employeeId(), "employeeId"),
                        requireStored(
                                row.employmentPeriodId(),
                                "employmentPeriodId"),
                        digest(row)))
                .sorted(Comparator
                        .comparing(Resolution::employeeId)
                        .thenComparing(Resolution::employmentPeriodId))
                .toList();
    }

    private static String digest(EvidenceEmployeeResolverRow row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, requireStored(row.employeeId(), "employeeId"));
            append(
                    digest,
                    requireStored(
                            row.employmentPeriodId(),
                            "employmentPeriodId"));
            append(
                    digest,
                    requireStored(
                            row.assignmentVersionId(),
                            "assignmentVersionId"));
            append(
                    digest,
                    requireStored(
                            row.employeeVersionId(),
                            "employeeVersionId"));
            append(
                    digest,
                    requireStored(row.organizationId(), "organizationId"));
            append(digest, row.bindingId() == null ? "" : row.bindingId());
            append(digest, Long.toString(row.employeeAggregateVersion()));
            append(digest, Long.toString(row.employeeVersion()));
            append(digest, Long.toString(row.employmentVersion()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "employee resolver digest is unavailable", exception);
        }
    }

    private static void append(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length)
                .array());
        digest.update(bytes);
    }

    private static String requireStored(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "employee resolver returned an invalid " + field);
        }
        return value;
    }

    private static void requireReference(String value, String field) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_REFERENCE_LENGTH
                || hasControlCharacter(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void optionalReference(
            String value, String field, int maximumLength) {
        if (value != null
                && (value.isBlank()
                        || value.length() > maximumLength
                        || hasControlCharacter(value))) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireIdentifier(
            String value, String field, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || hasControlCharacter(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(character ->
                Character.isISOControl(character)
                        || Character.getType(character)
                                == Character.LINE_SEPARATOR
                        || Character.getType(character)
                                == Character.PARAGRAPH_SEPARATOR);
    }
}
