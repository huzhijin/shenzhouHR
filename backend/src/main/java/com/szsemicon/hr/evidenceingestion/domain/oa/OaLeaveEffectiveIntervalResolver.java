package com.szsemicon.hr.evidenceingestion.domain.oa;

import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer.LocalInterval;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer.ResolvedSubject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves effective leave intervals and immutable return facts from the
 * complete current set of OA leave-revocation documents.
 *
 * <p>The business correlation is an exact string equality between leave
 * {@code formmain_0170.field0097} and leave-revocation
 * {@code formmain_0370.field0099}. One leave may have multiple current
 * revocation documents. Each approved document contributes one continuous
 * return interval and one system-calculated return-hours fact; it never
 * replaces the whole original leave. Approved return intervals must be inside
 * the original interval and must not overlap, and their cumulative hours must
 * not exceed the original leave hours already deducted.</p>
 *
 * <p>This policy does not query OA, collapse document history, or interpret raw
 * approval-state values. A caller may use it only after a signed query contract
 * has established that the original leave is approved, returned a complete set
 * of current revocation business documents for the exact leave serial, and
 * mapped every current raw state to {@link ApprovalDecision}. A partial page or
 * an unresolved historical version set cannot prove the zero-revocation case.</p>
 */
public final class OaLeaveEffectiveIntervalResolver {

    private static final BigDecimal HALF_HOUR = new BigDecimal("0.5");

    private OaLeaveEffectiveIntervalResolver() {
    }

    public enum ApprovalDecision {
        APPROVED_EFFECTIVE,
        NOT_EFFECTIVE,
        NOT_VERIFIED
    }

    public enum RevocationSetCompleteness {
        COMPLETE_CURRENT_DOCUMENTS,
        NOT_VERIFIED
    }

    public enum IssueCode {
        LEAVE_SERIAL_INVALID,
        LEAVE_DEDUCTED_HOURS_INVALID,
        LEAVE_REVOCATION_SET_NOT_VERIFIED,
        LEAVE_REVOCATION_REQUEST_ID_INVALID,
        LEAVE_REVOCATION_REQUEST_ID_DUPLICATE,
        LEAVE_REVOCATION_REFERENCE_MISMATCH,
        LEAVE_REVOCATION_SUBJECT_MISMATCH,
        LEAVE_REVOCATION_APPROVAL_NOT_VERIFIED,
        LEAVE_REVOCATION_RETURN_HOURS_INVALID,
        LEAVE_REVOCATION_INTERVAL_OUTSIDE_LEAVE,
        LEAVE_REVOCATION_INTERVAL_OVERLAP,
        LEAVE_REVOCATION_RETURN_EXCEEDS_DEDUCTED
    }

    public record LeaveSnapshot(
            String serialNumber,
            ResolvedSubject subject,
            LocalInterval interval,
            BigDecimal deductedHours) {

        public LeaveSnapshot {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(deductedHours, "deductedHours");
        }
    }

    public record LeaveRevocationSnapshot(
            String sourceRevocationRequestId,
            String originalLeaveSerialNumber,
            ResolvedSubject subject,
            LocalInterval returnInterval,
            BigDecimal returnedHours,
            ApprovalDecision approvalDecision) {

        public LeaveRevocationSnapshot {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(returnInterval, "returnInterval");
            Objects.requireNonNull(returnedHours, "returnedHours");
            Objects.requireNonNull(approvalDecision, "approvalDecision");
        }
    }

    public record ApprovedReturnFact(
            String sourceRevocationRequestId,
            LocalInterval returnInterval,
            BigDecimal returnedHours) {

        public ApprovedReturnFact {
            sourceRevocationRequestId = Objects.requireNonNull(
                    sourceRevocationRequestId,
                    "sourceRevocationRequestId");
            if (!validSourceId(sourceRevocationRequestId)) {
                throw new IllegalArgumentException(
                        "sourceRevocationRequestId is invalid");
            }
            Objects.requireNonNull(returnInterval, "returnInterval");
            Objects.requireNonNull(returnedHours, "returnedHours");
        }
    }

    public record Resolution(
            List<LocalInterval> effectiveLeaveIntervals,
            List<ApprovedReturnFact> approvedReturnFacts,
            BigDecimal totalReturnedHours,
            List<IssueCode> issues) {

        public Resolution {
            effectiveLeaveIntervals = List.copyOf(effectiveLeaveIntervals);
            approvedReturnFacts = List.copyOf(approvedReturnFacts);
            Objects.requireNonNull(totalReturnedHours, "totalReturnedHours");
            issues = List.copyOf(issues);
            if (!issues.isEmpty()
                    && (!effectiveLeaveIntervals.isEmpty()
                    || !approvedReturnFacts.isEmpty()
                    || totalReturnedHours.signum() != 0)) {
                throw new IllegalArgumentException(
                        "failed resolution cannot expose effective leave or return facts");
            }
        }

        public boolean resolved() {
            return issues.isEmpty();
        }
    }

    public static Resolution resolve(
            LeaveSnapshot leave,
            List<LeaveRevocationSnapshot> revocationsForLeaveSerial,
            RevocationSetCompleteness completeness) {
        Objects.requireNonNull(leave, "leave");
        Objects.requireNonNull(completeness, "completeness");
        List<LeaveRevocationSnapshot> revocations =
                List.copyOf(Objects.requireNonNull(
                        revocationsForLeaveSerial,
                        "revocationsForLeaveSerial"));

        if (leave.serialNumber() == null || leave.serialNumber().isBlank()) {
            return failed(IssueCode.LEAVE_SERIAL_INVALID);
        }
        if (!validHours(leave.deductedHours())) {
            return failed(IssueCode.LEAVE_DEDUCTED_HOURS_INVALID);
        }
        if (completeness != RevocationSetCompleteness
                .COMPLETE_CURRENT_DOCUMENTS) {
            return failed(
                    IssueCode.LEAVE_REVOCATION_SET_NOT_VERIFIED);
        }

        List<ApprovedReturnFact> approvedReturns = new ArrayList<>();
        Set<String> currentRevocationIds = new HashSet<>();
        for (LeaveRevocationSnapshot revocation : revocations) {
            if (!validSourceId(revocation.sourceRevocationRequestId())) {
                return failed(
                        IssueCode.LEAVE_REVOCATION_REQUEST_ID_INVALID);
            }
            if (!currentRevocationIds.add(
                    revocation.sourceRevocationRequestId())) {
                return failed(
                        IssueCode.LEAVE_REVOCATION_REQUEST_ID_DUPLICATE);
            }
            if (!leave.serialNumber().equals(
                    revocation.originalLeaveSerialNumber())) {
                return failed(
                        IssueCode.LEAVE_REVOCATION_REFERENCE_MISMATCH);
            }
            if (!leave.subject().equals(revocation.subject())) {
                return failed(IssueCode.LEAVE_REVOCATION_SUBJECT_MISMATCH);
            }
            if (revocation.approvalDecision()
                    == ApprovalDecision.NOT_VERIFIED) {
                return failed(
                        IssueCode.LEAVE_REVOCATION_APPROVAL_NOT_VERIFIED);
            }
            if (revocation.approvalDecision()
                    == ApprovalDecision.NOT_EFFECTIVE) {
                continue;
            }
            if (!validHours(revocation.returnedHours())) {
                return failed(
                        IssueCode.LEAVE_REVOCATION_RETURN_HOURS_INVALID);
            }
            if (!contains(
                    leave.interval(),
                    revocation.returnInterval())) {
                return failed(
                        IssueCode.LEAVE_REVOCATION_INTERVAL_OUTSIDE_LEAVE);
            }
            approvedReturns.add(new ApprovedReturnFact(
                    revocation.sourceRevocationRequestId(),
                    revocation.returnInterval(),
                    revocation.returnedHours()));
        }

        approvedReturns.sort(Comparator
                .comparing((ApprovedReturnFact fact) ->
                        fact.returnInterval().start())
                .thenComparing(fact -> fact.returnInterval().end())
                .thenComparing(ApprovedReturnFact::sourceRevocationRequestId));

        for (int index = 1; index < approvedReturns.size(); index++) {
            LocalInterval previous =
                    approvedReturns.get(index - 1).returnInterval();
            LocalInterval current =
                    approvedReturns.get(index).returnInterval();
            if (current.start().isBefore(previous.end())) {
                return failed(
                        IssueCode.LEAVE_REVOCATION_INTERVAL_OVERLAP);
            }
        }

        BigDecimal totalReturnedHours = approvedReturns.stream()
                .map(ApprovedReturnFact::returnedHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalReturnedHours.compareTo(leave.deductedHours()) > 0) {
            return failed(
                    IssueCode.LEAVE_REVOCATION_RETURN_EXCEEDS_DEDUCTED);
        }

        return resolved(
                subtract(leave.interval(), approvedReturns),
                approvedReturns,
                totalReturnedHours);
    }

    private static Resolution resolved(
            List<LocalInterval> effectiveLeaveIntervals,
            List<ApprovedReturnFact> approvedReturnFacts,
            BigDecimal totalReturnedHours) {
        return new Resolution(
                effectiveLeaveIntervals,
                approvedReturnFacts,
                totalReturnedHours,
                List.of());
    }

    private static Resolution failed(IssueCode issue) {
        return new Resolution(
                List.of(),
                List.of(),
                BigDecimal.ZERO,
                List.of(issue));
    }

    private static boolean validHours(BigDecimal hours) {
        return hours.signum() > 0
                && hours.remainder(HALF_HOUR)
                .compareTo(BigDecimal.ZERO) == 0;
    }

    private static boolean validSourceId(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 128
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean contains(
            LocalInterval outer,
            LocalInterval inner) {
        return !inner.start().isBefore(outer.start())
                && !inner.end().isAfter(outer.end());
    }

    private static List<LocalInterval> subtract(
            LocalInterval original,
            List<ApprovedReturnFact> approvedReturns) {
        List<LocalInterval> effective = new ArrayList<>();
        var cursor = original.start();
        for (ApprovedReturnFact approvedReturn : approvedReturns) {
            LocalInterval returned = approvedReturn.returnInterval();
            if (returned.start().isAfter(cursor)) {
                effective.add(new LocalInterval(cursor, returned.start()));
            }
            cursor = returned.end();
        }
        if (cursor.isBefore(original.end())) {
            effective.add(new LocalInterval(cursor, original.end()));
        }
        return List.copyOf(effective);
    }
}
