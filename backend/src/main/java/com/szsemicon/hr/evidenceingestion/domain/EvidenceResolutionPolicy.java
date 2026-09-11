package com.szsemicon.hr.evidenceingestion.domain;

import com.szsemicon.hr.evidenceingestion.domain.oa.OaEmployeeNumberCatalog;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvidenceResolutionPolicy {

    /**
     * Device/OA intern numbers that are not on the HR roster, mapped to the
     * unique formal employee number. Used when empno lookup is empty.
     */
    private static final Map<String, String> INTERN_TO_ROSTER = Map.of(
            "SZSTSX50", "SZST0498",
            "SZSTSX58", "SZST0554",
            "SZSTSX61", "SZST0677",
            "SZSTSX67", "SZST0654",
            "SZSTSX71", "SZST0680",
            "SZSTSX80", "SZST0699",
            "SZSTSX85", "SZST0701");

    private EvidenceResolutionPolicy() {
    }

    public enum MatchStatus {
        MATCHED,
        UNMATCHED,
        AMBIGUOUS
    }

    public record MatchDecision(
            MatchStatus status,
            String reason,
            String employeeId,
            String employmentPeriodId,
            String resolverSnapshotDigest,
            String companyId) {
    }

    public static MatchDecision resolve(
            EmployeeEmploymentResolverPort resolver,
            String sourceId,
            String companyId,
            String employeeNumber,
            String locationId,
            String deviceId,
            String externalPersonRef,
            ConfirmedBindingKind bindingKind,
            Instant at) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(at, "at");
        return resolve(
                resolver,
                sourceId,
                companyId,
                employeeNumber,
                locationId,
                deviceId,
                externalPersonRef,
                bindingKind,
                null,
                at);
    }

    public static MatchDecision resolve(
            EmployeeEmploymentResolverPort resolver,
            String sourceId,
            String companyId,
            String employeeNumber,
            String locationId,
            String deviceId,
            String externalPersonRef,
            ConfirmedBindingKind bindingKind,
            String memberName,
            Instant at) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(at, "at");
        // companyId is kept on the port for callers; matching is by
        // employee number / confirmed binding / unique display name because
        // HR, OA and Deli organization trees are independent. A confirmed
        // Deli person-id binding wins over empno. Unique CHECKIN member_name
        // then wins over a colliding device empno (彭伟 user_id 218 still
        // prints 赵艺娴 SZST0289; directory short id 387 must never be used).
        List<EmployeeEmploymentResolverPort.Resolution> byNumber =
                normalized(employeeNumber == null || employeeNumber.isBlank()
                        ? List.of()
                        : resolver.resolveByEmployeeNumber(
                                companyId, employeeNumber, at));
        if (byNumber.isEmpty() && employeeNumber != null && !employeeNumber.isBlank()) {
            String aliased = OaEmployeeNumberCatalog.alias(employeeNumber);
            if (aliased != null && !aliased.equals(employeeNumber)) {
                byNumber = normalized(resolver.resolveByEmployeeNumber(
                        companyId, aliased, at));
            }
        }
        if (byNumber.isEmpty() && employeeNumber != null && !employeeNumber.isBlank()) {
            String rosterNumber = INTERN_TO_ROSTER.get(employeeNumber);
            if (rosterNumber != null) {
                byNumber = normalized(resolver.resolveByEmployeeNumber(
                        companyId, rosterNumber, at));
            }
        }
        List<EmployeeEmploymentResolverPort.Resolution> byBinding =
                normalized(bindingKind == null
                                || externalPersonRef == null
                                || externalPersonRef.isBlank()
                        ? List.of()
                        : resolver.resolveByConfirmedBinding(
                                sourceId,
                                companyId,
                                locationId,
                                deviceId,
                                bindingKind,
                                externalPersonRef,
                                at));
        List<EmployeeEmploymentResolverPort.Resolution> byName =
                normalized(memberName == null || memberName.isBlank()
                        ? List.of()
                        : resolver.resolveByDisplayName(
                                companyId, memberName, at));
        if (byBinding.size() == 1) {
            return matched("CONFIRMED_BINDING", byBinding.getFirst());
        }
        if (byBinding.size() > 1) {
            return unresolved(
                    MatchStatus.AMBIGUOUS, "CONFIRMED_BINDING_MULTIPLE");
        }
        if (byName.size() == 1) {
            if (byNumber.size() == 1
                    && !byNumber.getFirst().employeeId().equals(
                            byName.getFirst().employeeId())) {
                return matched("NAME_OVER_DEVICE_EMPNO", byName.getFirst());
            }
            return matched("DISPLAY_NAME", byName.getFirst());
        }
        if (byNumber.size() == 1) {
            return matched("EMPLOYEE_NUMBER", byNumber.getFirst());
        }
        if (byNumber.size() > 1) {
            return unresolved(
                    MatchStatus.AMBIGUOUS, "EMPLOYEE_NUMBER_MULTIPLE");
        }
        return unresolved(MatchStatus.UNMATCHED, "NO_AUTHORITATIVE_MATCH");
    }

    private static List<EmployeeEmploymentResolverPort.Resolution> normalized(
            List<EmployeeEmploymentResolverPort.Resolution> resolutions) {
        if (resolutions == null) {
            throw new IllegalStateException("resolver must fail closed, not return null");
        }
        return resolutions.stream()
                .sorted(Comparator
                        .comparing(EmployeeEmploymentResolverPort.Resolution::employeeId)
                        .thenComparing(
                                EmployeeEmploymentResolverPort.Resolution::employmentPeriodId))
                .toList();
    }

    private static MatchDecision matched(
            String reason,
            EmployeeEmploymentResolverPort.Resolution resolution) {
        return new MatchDecision(
                MatchStatus.MATCHED,
                reason,
                resolution.employeeId(),
                resolution.employmentPeriodId(),
                resolution.resolverSnapshotDigest(),
                resolution.companyId());
    }

    private static MatchDecision unresolved(MatchStatus status, String reason) {
        return new MatchDecision(status, reason, null, null, null, null);
    }
}
