package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trusted, internal-only inputs for publishing an immutable formal report
 * projection. These types deliberately have no raw punch, location, OA reason,
 * or other free-form evidence fields.
 */
public final class AttendanceReportPublicationModels {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_:-]{1,64}");
    private static final Set<String> OA_DOCUMENT_TYPES = Set.of(
            "LEAVE",
            "LEAVE_REVOCATION",
            "OVERTIME",
            "TRIP",
            "OUTING",
            "PUNCH_CORRECTION",
            "TIME_OFF",
            "EXEMPT_PUNCH");
    private static final Set<String> OA_SOURCE_STATUSES = Set.of(
            "APPROVED",
            "DRAFT",
            "REJECTED",
            "UNKNOWN",
            "MODIFIED",
            "SUPPLEMENTED",
            "REVOKED");

    private AttendanceReportPublicationModels() {
    }

    public enum PeriodState {
        OPEN,
        FROZEN,
        CLOSED,
        REOPENED
    }

    public enum OaTemporalShape {
        POINT,
        INTERVAL,
        DATE_RANGE
    }

    public record VerifiedProjectionMetadata(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String formulaCatalogVersion,
            List<String> sourceVersions,
            String sourceSnapshotDigest,
            Instant dataAsOf,
            String createdByPrincipalId) {

        public VerifiedProjectionMetadata {
            companyId = databaseId(companyId, "companyId");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(periodState, "periodState");
            formulaCatalogVersion = version(
                    formulaCatalogVersion, "formulaCatalogVersion");
            sourceVersions = Objects.requireNonNull(
                            sourceVersions, "sourceVersions")
                    .stream()
                    .map(value -> version(value, "sourceVersion"))
                    .sorted()
                    .distinct()
                    .toList();
            if (sourceVersions.isEmpty()) {
                throw new IllegalArgumentException(
                        "sourceVersions must not be empty");
            }
            sourceSnapshotDigest = digest(
                    sourceSnapshotDigest, "sourceSnapshotDigest");
            dataAsOf = databaseInstant(dataAsOf, "dataAsOf");
            createdByPrincipalId = databaseId(
                    createdByPrincipalId, "createdByPrincipalId");
        }
    }

    /**
     * Binds projector-produced facts to the exact employee and assignment
     * versions used by the trusted calculator snapshot.
     */
    public record VerifiedCalculatedFacts(
            String employeeVersionId,
            String employmentAssignmentId,
            ProjectionFacts facts) {

        public VerifiedCalculatedFacts {
            employeeVersionId = databaseId(
                    employeeVersionId, "employeeVersionId");
            employmentAssignmentId = databaseId(
                    employmentAssignmentId, "employmentAssignmentId");
            Objects.requireNonNull(facts, "facts");
        }
    }

    /**
     * A verified OA fact contains only identity, temporal, status, and
     * recognized-minute data. It cannot carry the source reason, destination,
     * location, or raw form payload.
     */
    public record VerifiedOaDocumentFact(
            String companyId,
            String oaAttendanceDocumentId,
            String employeeId,
            String employeeVersionId,
            String employmentAssignmentId,
            String organizationId,
            String organizationVersionId,
            String documentType,
            String leaveTypeCode,
            OaTemporalShape temporalShape,
            Instant pointInstant,
            Instant intervalStart,
            Instant intervalEndExclusive,
            long recognizedMinutes,
            String sourceStatus,
            String sourceVersion,
            String sourceOrigin) {

        public VerifiedOaDocumentFact(
                String companyId,
                String oaAttendanceDocumentId,
                String employeeId,
                String employeeVersionId,
                String employmentAssignmentId,
                String organizationId,
                String organizationVersionId,
                String documentType,
                String leaveTypeCode,
                OaTemporalShape temporalShape,
                Instant pointInstant,
                Instant intervalStart,
                Instant intervalEndExclusive,
                long recognizedMinutes,
                String sourceStatus,
                String sourceVersion) {
            this(
                    companyId,
                    oaAttendanceDocumentId,
                    employeeId,
                    employeeVersionId,
                    employmentAssignmentId,
                    organizationId,
                    organizationVersionId,
                    documentType,
                    leaveTypeCode,
                    temporalShape,
                    pointInstant,
                    intervalStart,
                    intervalEndExclusive,
                    recognizedMinutes,
                    sourceStatus,
                    sourceVersion,
                    "OA");
        }

        public VerifiedOaDocumentFact {
            companyId = databaseId(companyId, "companyId");
            oaAttendanceDocumentId = databaseId(
                    oaAttendanceDocumentId, "oaAttendanceDocumentId");
            employeeId = databaseId(employeeId, "employeeId");
            employeeVersionId = databaseId(
                    employeeVersionId, "employeeVersionId");
            employmentAssignmentId = databaseId(
                    employmentAssignmentId, "employmentAssignmentId");
            organizationId = databaseId(
                    organizationId, "organizationId");
            organizationVersionId = databaseId(
                    organizationVersionId, "organizationVersionId");
            documentType = exactCode(
                    documentType, "documentType", OA_DOCUMENT_TYPES);
            leaveTypeCode = optionalCode(leaveTypeCode, "leaveTypeCode");
            Objects.requireNonNull(temporalShape, "temporalShape");
            if (temporalShape == OaTemporalShape.POINT) {
                pointInstant = databaseInstant(
                        pointInstant, "pointInstant");
                if (intervalStart != null || intervalEndExclusive != null) {
                    throw new IllegalArgumentException(
                            "point OA fact cannot contain an interval");
                }
            } else {
                intervalStart = databaseInstant(
                        intervalStart, "intervalStart");
                intervalEndExclusive = databaseInstant(
                        intervalEndExclusive, "intervalEndExclusive");
                if (pointInstant != null
                        || !intervalStart.isBefore(intervalEndExclusive)) {
                    throw new IllegalArgumentException(
                            "interval OA fact must be half-open");
                }
            }
            unsignedInt(recognizedMinutes, "recognizedMinutes");
            sourceStatus = exactCode(
                    sourceStatus, "sourceStatus", OA_SOURCE_STATUSES);
            if (!Set.of("APPROVED", "MODIFIED", "SUPPLEMENTED", "UNKNOWN")
                            .contains(sourceStatus)
                    && recognizedMinutes != 0) {
                throw new IllegalArgumentException(
                        "non-effective OA fact cannot recognize minutes");
            }
            sourceVersion = version(sourceVersion, "sourceVersion");
            sourceOrigin = sourceOrigin == null || sourceOrigin.isBlank()
                    ? "OA"
                    : sourceOrigin;
            if (!Set.of("OA", "PAPER").contains(sourceOrigin)) {
                throw new IllegalArgumentException("sourceOrigin is invalid");
            }
        }
    }

    /**
     * A verified ledger projection. No adjustment reason or source payload is
     * accepted here; the upstream Wave 6 adapter remains responsible for
     * deriving these balance components from its immutable ledger.
     */
    public record VerifiedTimeAccountFact(
            String companyId,
            String accountId,
            String employeeId,
            String employeeVersionId,
            String organizationId,
            String organizationVersionId,
            TimeAccountType accountType,
            BigDecimal openingHours,
            BigDecimal grantedHours,
            BigDecimal overtimeCreditHours,
            BigDecimal manualIncreaseHours,
            BigDecimal usedHours,
            BigDecimal expiredHours,
            BigDecimal returnedHours,
            BigDecimal manualDeductionHours,
            String ledgerVersion) {

        public VerifiedTimeAccountFact {
            companyId = databaseId(companyId, "companyId");
            accountId = reference(accountId, "accountId", 128);
            employeeId = databaseId(employeeId, "employeeId");
            employeeVersionId = databaseId(
                    employeeVersionId, "employeeVersionId");
            organizationId = databaseId(
                    organizationId, "organizationId");
            organizationVersionId = databaseId(
                    organizationVersionId, "organizationVersionId");
            Objects.requireNonNull(accountType, "accountType");
            openingHours = signedDecimal(openingHours, "openingHours");
            grantedHours = decimal(grantedHours, "grantedHours");
            overtimeCreditHours = decimal(
                    overtimeCreditHours, "overtimeCreditHours");
            manualIncreaseHours = decimal(
                    manualIncreaseHours, "manualIncreaseHours");
            usedHours = decimal(usedHours, "usedHours");
            expiredHours = decimal(expiredHours, "expiredHours");
            returnedHours = decimal(returnedHours, "returnedHours");
            manualDeductionHours = decimal(
                    manualDeductionHours, "manualDeductionHours");
            ledgerVersion = version(ledgerVersion, "ledgerVersion");
        }
    }

    /**
     * Binds a current-state exception case projection to the exact identity
     * versions used by the trusted exception reconciler. This supports
     * blocking cases that exist even when no daily calculation fact can be
     * published.
     */
    public record VerifiedCurrentExceptionFact(
            String companyId,
            String employeeVersionId,
            String employmentAssignmentId,
            String organizationVersionId,
            com.szsemicon.hr.reporting.domain.AttendanceReportModels
                    .ExceptionFact fact) {

        public VerifiedCurrentExceptionFact {
            companyId = databaseId(companyId, "companyId");
            employeeVersionId = databaseId(
                    employeeVersionId, "employeeVersionId");
            employmentAssignmentId = databaseId(
                    employmentAssignmentId, "employmentAssignmentId");
            organizationVersionId = databaseId(
                    organizationVersionId, "organizationVersionId");
            Objects.requireNonNull(fact, "fact");
        }
    }

    public record PublishCommand(
            VerifiedProjectionMetadata metadata,
            List<VerifiedCalculatedFacts> calculatedFacts,
            List<VerifiedCurrentExceptionFact> currentExceptionFacts,
            List<VerifiedOaDocumentFact> oaDocumentFacts,
            List<VerifiedTimeAccountFact> timeAccountFacts) {

        public PublishCommand {
            Objects.requireNonNull(metadata, "metadata");
            calculatedFacts = List.copyOf(Objects.requireNonNull(
                    calculatedFacts, "calculatedFacts"));
            currentExceptionFacts = List.copyOf(Objects.requireNonNull(
                    currentExceptionFacts, "currentExceptionFacts"));
            oaDocumentFacts = List.copyOf(Objects.requireNonNull(
                    oaDocumentFacts, "oaDocumentFacts"));
            timeAccountFacts = List.copyOf(Objects.requireNonNull(
                    timeAccountFacts, "timeAccountFacts"));
        }

        public PublishCommand(
                VerifiedProjectionMetadata metadata,
                List<VerifiedCalculatedFacts> calculatedFacts,
                List<VerifiedOaDocumentFact> oaDocumentFacts,
                List<VerifiedTimeAccountFact> timeAccountFacts) {
            this(
                    metadata,
                    calculatedFacts,
                    List.of(),
                    oaDocumentFacts,
                    timeAccountFacts);
        }
    }

    public record PublicationResult(
            String projectionId,
            String projectionVersion,
            String projectionDigest,
            PeriodState periodState,
            Instant dataAsOf,
            Instant publishedAt,
            boolean created) {

        public PublicationResult {
            projectionId = databaseId(projectionId, "projectionId");
            projectionVersion = version(
                    projectionVersion, "projectionVersion");
            projectionDigest = digest(
                    projectionDigest, "projectionDigest");
            Objects.requireNonNull(periodState, "periodState");
            Objects.requireNonNull(dataAsOf, "dataAsOf");
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }

    static String databaseId(String value, String field) {
        String safe = reference(value, field, 36);
        if (!IDENTIFIER.matcher(safe).matches()) {
            throw new IllegalArgumentException(
                    field + " contains invalid characters");
        }
        return safe;
    }

    static String reference(String value, String field, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || !value.equals(value.strip())
                || value.chars().anyMatch(
                        character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String version(String value, String field) {
        return reference(value, field, 128);
    }

    static String digest(String value, String field) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    static long unsignedInt(long value, String field) {
        if (value < 0 || value > 4_294_967_295L) {
            throw new IllegalArgumentException(
                    field + " is outside unsigned INT range");
        }
        return value;
    }

    static BigDecimal decimal(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        BigDecimal scaled = scaledHours(value, field);
        if (scaled.signum() < 0 || scaled.precision() > 16) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return scaled;
    }

    static BigDecimal signedDecimal(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        BigDecimal scaled = scaledHours(value, field);
        if (scaled.precision() > 16) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return scaled;
    }

    private static BigDecimal scaledHours(BigDecimal value, String field) {
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    field + " must have at most two decimals");
        }
    }

    static Instant databaseInstant(Instant value, String field) {
        Objects.requireNonNull(value, field);
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private static String optionalCode(String value, String field) {
        if (value == null) {
            return null;
        }
        if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String exactCode(
            String value, String field, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
