package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer.LocalInterval;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer.ResolvedSubject;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveEffectiveIntervalResolver;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveEffectiveIntervalResolver.ApprovalDecision;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveEffectiveIntervalResolver.ApprovedReturnFact;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveEffectiveIntervalResolver.IssueCode;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveEffectiveIntervalResolver.LeaveRevocationSnapshot;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveEffectiveIntervalResolver.LeaveSnapshot;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveEffectiveIntervalResolver.RevocationSetCompleteness;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OaLeaveEffectiveIntervalResolverTest {

    private static final ResolvedSubject SUBJECT =
            new ResolvedSubject(BigInteger.valueOf(123), "0007A");
    private static final LocalInterval ORIGINAL = new LocalInterval(
            LocalDateTime.of(2026, 8, 10, 9, 0),
            LocalDateTime.of(2026, 8, 10, 18, 0));
    private static final LocalInterval FIRST_RETURN = new LocalInterval(
            LocalDateTime.of(2026, 8, 10, 10, 0),
            LocalDateTime.of(2026, 8, 10, 11, 0));
    private static final LocalInterval SECOND_RETURN = new LocalInterval(
            LocalDateTime.of(2026, 8, 10, 14, 0),
            LocalDateTime.of(2026, 8, 10, 15, 30));

    @Test
    void normalLeaveWithoutRevocationUsesTheLeaveInterval() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(),
                complete());

        assertThat(result.resolved()).isTrue();
        assertThat(result.effectiveLeaveIntervals())
                .containsExactly(ORIGINAL);
        assertThat(result.approvedReturnFacts()).isEmpty();
        assertThat(result.totalReturnedHours())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void oneApprovedRevocationProducesOneReturnFactAndRemainingIntervals() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(revocation(
                        "LEAVE-001",
                        SUBJECT,
                        FIRST_RETURN,
                        "1.0",
                        ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isTrue();
        assertThat(result.effectiveLeaveIntervals()).containsExactly(
                interval(9, 0, 10, 0),
                interval(11, 0, 18, 0));
        assertThat(result.approvedReturnFacts()).containsExactly(
                new ApprovedReturnFact(
                        revocationId(FIRST_RETURN),
                        FIRST_RETURN,
                        hours("1.0")));
        assertThat(result.totalReturnedHours())
                .isEqualByComparingTo("1.0");
    }

    @Test
    void multipleApprovedRevocationsAreSortedAndNeverLastWriteWins() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001", "2.5"),
                List.of(
                        revocation(
                                "LEAVE-001",
                                SUBJECT,
                                SECOND_RETURN,
                                "1.5",
                                ApprovalDecision.APPROVED_EFFECTIVE),
                        revocation(
                                "LEAVE-001",
                                SUBJECT,
                                FIRST_RETURN,
                                "1.0",
                                ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isTrue();
        assertThat(result.effectiveLeaveIntervals()).containsExactly(
                interval(9, 0, 10, 0),
                interval(11, 0, 14, 0),
                interval(15, 30, 18, 0));
        assertThat(result.approvedReturnFacts()).containsExactly(
                new ApprovedReturnFact(
                        revocationId(FIRST_RETURN),
                        FIRST_RETURN,
                        hours("1.0")),
                new ApprovedReturnFact(
                        revocationId(SECOND_RETURN),
                        SECOND_RETURN,
                        hours("1.5")));
        assertThat(result.totalReturnedHours())
                .isEqualByComparingTo("2.5");
    }

    @Test
    void knownNonEffectiveRevocationsDoNotCreateReturnFacts() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(revocation(
                        "LEAVE-001",
                        SUBJECT,
                        FIRST_RETURN,
                        "1.0",
                        ApprovalDecision.NOT_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isTrue();
        assertThat(result.effectiveLeaveIntervals())
                .containsExactly(ORIGINAL);
        assertThat(result.approvedReturnFacts()).isEmpty();
    }

    @Test
    void serialMatchingIsExactAndNeverTrimmed() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(revocation(
                        " LEAVE-001",
                        SUBJECT,
                        FIRST_RETURN,
                        "1.0",
                        ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isFalse();
        assertThat(result.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_REFERENCE_MISMATCH);
    }

    @Test
    void subjectMismatchFailsClosedEvenWhenTheSerialMatches() {
        ResolvedSubject anotherSubject =
                new ResolvedSubject(BigInteger.valueOf(456), "0008B");
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(revocation(
                        "LEAVE-001",
                        anotherSubject,
                        FIRST_RETURN,
                        "1.0",
                        ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isFalse();
        assertThat(result.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_SUBJECT_MISMATCH);
    }

    @Test
    void unknownApprovalMeaningFailsClosed() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(revocation(
                        "LEAVE-001",
                        SUBJECT,
                        FIRST_RETURN,
                        "1.0",
                        ApprovalDecision.NOT_VERIFIED)),
                complete());

        assertThat(result.resolved()).isFalse();
        assertThat(result.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_APPROVAL_NOT_VERIFIED);
    }

    @Test
    void blankLeaveSerialFailsClosedEvenWithoutARevocation() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave(" "),
                List.of(),
                complete());

        assertThat(result.resolved()).isFalse();
        assertThat(result.issues()).containsExactly(
                IssueCode.LEAVE_SERIAL_INVALID);
    }

    @Test
    void anIncompleteCurrentRevocationSetCannotProveThereIsNoRevocation() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(),
                RevocationSetCompleteness.NOT_VERIFIED);

        assertThat(result.resolved()).isFalse();
        assertThat(result.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_SET_NOT_VERIFIED);
    }

    @Test
    void overlappingApprovedReturnIntervalsFailClosed() {
        LocalInterval overlapping = new LocalInterval(
                LocalDateTime.of(2026, 8, 10, 10, 30),
                LocalDateTime.of(2026, 8, 10, 12, 0));
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(
                        revocation(
                                "LEAVE-001",
                                SUBJECT,
                                FIRST_RETURN,
                                "1.0",
                                ApprovalDecision.APPROVED_EFFECTIVE),
                        revocation(
                                "LEAVE-001",
                                SUBJECT,
                                overlapping,
                                "1.5",
                                ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isFalse();
        assertThat(result.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_INTERVAL_OVERLAP);
        assertThat(result.effectiveLeaveIntervals()).isEmpty();
        assertThat(result.approvedReturnFacts()).isEmpty();
    }

    @Test
    void adjacentReturnIntervalsAreAllowed() {
        LocalInterval adjacent = interval(11, 0, 12, 0);
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(
                        revocation(
                                "LEAVE-001",
                                SUBJECT,
                                FIRST_RETURN,
                                "1.0",
                                ApprovalDecision.APPROVED_EFFECTIVE),
                        revocation(
                                "LEAVE-001",
                                SUBJECT,
                                adjacent,
                                "1.0",
                                ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isTrue();
        assertThat(result.effectiveLeaveIntervals()).containsExactly(
                interval(9, 0, 10, 0),
                interval(12, 0, 18, 0));
        assertThat(result.approvedReturnFacts()).hasSize(2);
    }

    @Test
    void returnIntervalOutsideTheOriginalLeaveFailsClosed() {
        LocalInterval outside = interval(8, 30, 10, 0);
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(revocation(
                        "LEAVE-001",
                        SUBJECT,
                        outside,
                        "1.5",
                        ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isFalse();
        assertThat(result.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_INTERVAL_OUTSIDE_LEAVE);
    }

    @Test
    void cumulativeReturnCannotExceedAlreadyDeductedHours() {
        var result = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001", "2.0"),
                List.of(
                        revocation(
                                "LEAVE-001",
                                SUBJECT,
                                FIRST_RETURN,
                                "1.0",
                                ApprovalDecision.APPROVED_EFFECTIVE),
                        revocation(
                                "LEAVE-001",
                                SUBJECT,
                                SECOND_RETURN,
                                "1.5",
                                ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());

        assertThat(result.resolved()).isFalse();
        assertThat(result.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_RETURN_EXCEEDS_DEDUCTED);
    }

    @Test
    void systemHoursMustUseHalfHourUnits() {
        var invalidReturn = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(revocation(
                        "LEAVE-001",
                        SUBJECT,
                        FIRST_RETURN,
                        "0.25",
                        ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());
        var invalidDeduction = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001", "7.75"),
                List.of(),
                complete());

        assertThat(invalidReturn.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_RETURN_HOURS_INVALID);
        assertThat(invalidDeduction.issues()).containsExactly(
                IssueCode.LEAVE_DEDUCTED_HOURS_INVALID);
    }

    @Test
    void approvedSystemHoursMustBePositive() {
        var zeroReturn = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(revocation(
                        "LEAVE-001",
                        SUBJECT,
                        FIRST_RETURN,
                        "0.0",
                        ApprovalDecision.APPROVED_EFFECTIVE)),
                complete());
        var zeroDeduction = OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001", "0.0"),
                List.of(),
                complete());

        assertThat(zeroReturn.issues()).containsExactly(
                IssueCode.LEAVE_REVOCATION_RETURN_HOURS_INVALID);
        assertThat(zeroDeduction.issues()).containsExactly(
                IssueCode.LEAVE_DEDUCTED_HOURS_INVALID);
    }

    @Test
    void revocationRequestIdMustBePresentAndUnique() {
        LeaveRevocationSnapshot missingId = new LeaveRevocationSnapshot(
                " ",
                "LEAVE-001",
                SUBJECT,
                FIRST_RETURN,
                hours("1.0"),
                ApprovalDecision.APPROVED_EFFECTIVE);
        LeaveRevocationSnapshot duplicateId = new LeaveRevocationSnapshot(
                "REVOCATION-001",
                "LEAVE-001",
                SUBJECT,
                FIRST_RETURN,
                hours("1.0"),
                ApprovalDecision.APPROVED_EFFECTIVE);
        LeaveRevocationSnapshot duplicateIdWithDifferentPayload =
                new LeaveRevocationSnapshot(
                        "REVOCATION-001",
                        "LEAVE-001",
                        SUBJECT,
                        SECOND_RETURN,
                        hours("1.5"),
                        ApprovalDecision.APPROVED_EFFECTIVE);

        assertThat(OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"), List.of(missingId), complete()).issues())
                .containsExactly(
                        IssueCode.LEAVE_REVOCATION_REQUEST_ID_INVALID);
        assertThat(OaLeaveEffectiveIntervalResolver.resolve(
                leave("LEAVE-001"),
                List.of(duplicateId, duplicateIdWithDifferentPayload),
                complete()).issues())
                .containsExactly(
                        IssueCode.LEAVE_REVOCATION_REQUEST_ID_DUPLICATE);
    }

    private static LeaveSnapshot leave(String serialNumber) {
        return leave(serialNumber, "8.0");
    }

    private static LeaveSnapshot leave(
            String serialNumber,
            String deductedHours) {
        return new LeaveSnapshot(
                serialNumber,
                SUBJECT,
                ORIGINAL,
                hours(deductedHours));
    }

    private static LeaveRevocationSnapshot revocation(
            String originalLeaveSerialNumber,
            ResolvedSubject subject,
            LocalInterval returnInterval,
            String returnedHours,
            ApprovalDecision approvalDecision) {
        return new LeaveRevocationSnapshot(
                revocationId(returnInterval),
                originalLeaveSerialNumber,
                subject,
                returnInterval,
                hours(returnedHours),
                approvalDecision);
    }

    private static String revocationId(LocalInterval interval) {
        return "REVOCATION-" + interval.start();
    }

    private static LocalInterval interval(
            int startHour,
            int startMinute,
            int endHour,
            int endMinute) {
        return new LocalInterval(
                LocalDateTime.of(2026, 8, 10, startHour, startMinute),
                LocalDateTime.of(2026, 8, 10, endHour, endMinute));
    }

    private static BigDecimal hours(String value) {
        return new BigDecimal(value);
    }

    private static RevocationSetCompleteness complete() {
        return RevocationSetCompleteness.COMPLETE_CURRENT_DOCUMENTS;
    }
}
