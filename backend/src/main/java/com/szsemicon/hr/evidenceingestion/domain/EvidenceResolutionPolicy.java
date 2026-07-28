package com.szsemicon.hr.evidenceingestion.domain;

import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class EvidenceResolutionPolicy {

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
            String resolverSnapshotDigest) {
    }

    public static MatchDecision resolve(
            EmployeeEmploymentResolverPort resolver,
            String legalEntityId,
            String employeeNumber,
            String locationId,
            String deviceId,
            String externalPersonRef,
            ConfirmedBindingKind bindingKind,
            Instant at) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(at, "at");
        List<EmployeeEmploymentResolverPort.Resolution> byNumber =
                normalized(employeeNumber == null || employeeNumber.isBlank()
                        ? List.of()
                        : resolver.resolveByEmployeeNumber(
                                legalEntityId, employeeNumber, at));
        if (byNumber.size() == 1) {
            return matched("EMPLOYEE_NUMBER", byNumber.getFirst());
        }
        if (byNumber.size() > 1) {
            return unresolved(
                    MatchStatus.AMBIGUOUS, "EMPLOYEE_NUMBER_MULTIPLE");
        }
        List<EmployeeEmploymentResolverPort.Resolution> byBinding =
                normalized(externalPersonRef == null || externalPersonRef.isBlank()
                        ? List.of()
                        : resolver.resolveByConfirmedBinding(
                                legalEntityId,
                                locationId,
                                deviceId,
                                bindingKind,
                                externalPersonRef,
                                at));
        if (byBinding.size() == 1) {
            return matched("CONFIRMED_BINDING", byBinding.getFirst());
        }
        if (byBinding.size() > 1) {
            return unresolved(
                    MatchStatus.AMBIGUOUS, "CONFIRMED_BINDING_MULTIPLE");
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
                resolution.resolverSnapshotDigest());
    }

    private static MatchDecision unresolved(MatchStatus status, String reason) {
        return new MatchDecision(status, reason, null, null, null);
    }
}
