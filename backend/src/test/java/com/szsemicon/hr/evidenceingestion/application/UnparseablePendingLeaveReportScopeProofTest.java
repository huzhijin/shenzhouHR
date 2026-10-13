package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.*;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UnparseablePendingLeaveReportScopeProofTest {
    static final String VERSION = "2026-08-31T22:04:49:state=0:Asia/Shanghai";
    static final Instant START = Instant.parse("2026-08-31T00:30:00Z");
    static OaDocumentRecord candidate() { return candidate(null, null, null, null, "L1", SourceStatus.PENDING); }
    static OaDocumentRecord candidate(String member, String number, Instant end, LeaveType type, String serial, SourceStatus status) {
        return new OaDocumentRecord("LEAVE:1", VERSION, member, number, DocumentType.LEAVE, status,
                START, end, "Asia/Shanghai", START, null, null, null, null, true, null, type, serial, null);
    }
    static EvidenceRows.UnparseableLeaveScopeRow history() {
        return history("foreign", "employee", "SZST0001", "SZST0001", false, "UNKNOWN", VERSION, "L1", false);
    }
    static EvidenceRows.UnparseableLeaveScopeRow history(String company, String employee, String number,
            String historicalNumber, Boolean dependency, String status, String version, String serial, Boolean revocation) {
        return new EvidenceRows.UnparseableLeaveScopeRow("LEAVE:1", "raw", "OA_DOCUMENT", "norm", "VALID", "OA_INTERVAL",
                "doc", "LEAVE", true, "MATCHED", employee, company, number, historicalNumber, dependency,
                version, status, serial, revocation);
    }
    private boolean proof(OaDocumentRecord current, List<EvidenceRows.UnparseableLeaveScopeRow> history) {
        return UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "target", current, history, List.of("SZJN0001"), List.of(current));
    }
    @Test void exactPendingHistoryProvesOnlyTargetAbsenceWithoutChangingTheRecord() {
        assertThat(proof(candidate(), List.of(history()))).isTrue();
        assertThat(candidate().sourceStatus()).isEqualTo(SourceStatus.PENDING);
        assertThat(candidate().end()).isNull();
        assertThat(UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "foreign", candidate(), List.of(history()), List.of("SZST0001"), List.of(candidate()))).isFalse();
    }
    @ParameterizedTest @MethodSource("nonCandidates")
    void anyCurrentIdentityValidIntervalClassificationOrNonPendingStatusRemainsStrict(OaDocumentRecord current) {
        assertThat(proof(current, List.of(history()))).isFalse();
    }
    static Stream<Arguments> nonCandidates() {
        return Stream.of(
            candidate("member", null, null, null, "L1", SourceStatus.PENDING),
            candidate(null, "SZST0001", null, null, "L1", SourceStatus.PENDING),
            candidate(null, null, START.plusSeconds(60), null, "L1", SourceStatus.PENDING),
            candidate(null, null, null, LeaveType.PERSONAL, "L1", SourceStatus.PENDING),
            candidate(null, null, null, null, "L1", SourceStatus.APPROVED),
            candidate(null, null, null, null, "L1", SourceStatus.UNKNOWN),
            candidate(null, null, null, null, null, SourceStatus.PENDING),
            candidate(null, null, null, null, "", SourceStatus.PENDING),
            candidate(null, null, null, null, " L1", SourceStatus.PENDING),
            candidate(null, null, null, null, "L1 ", SourceStatus.PENDING)).map(Arguments::of);
    }
    @ParameterizedTest @MethodSource("unsafeHistories")
    void anyUnsafeHistoricalVersionPreventsIsolation(EvidenceRows.UnparseableLeaveScopeRow unsafe) {
        assertThat(proof(candidate(), List.of(history(), unsafe))).isFalse();
    }
    static Stream<Arguments> unsafeHistories() {
        return Stream.of(
            history("target", "employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", false),
            history("foreign", "other-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", false),
            history("another-foreign", "employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", false),
            history("foreign", "employee", "SZJN0001", "SZST0001", false, "PENDING", VERSION, "L1", false),
            history("foreign", "employee", "SZST0001", "SZJN0001", false, "PENDING", VERSION, "L1", false),
            history("foreign", "employee", "SZST0001", "SZST0001", true, "PENDING", VERSION, "L1", false),
            history("foreign", "employee", "SZST0001", "SZST0001", false, "APPROVED", VERSION, "L1", false),
            history("foreign", "employee", "SZST0001", "SZST0001", false, "UNKNOWN", "unknown-version", "L1", false),
            history("foreign", "employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "l1", false),
            history("foreign", "employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, " L1", false),
            history("foreign", "employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1 ", false),
            history("foreign", "employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, null, false),
            history("foreign", "employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", true),
            history("foreign", "employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", null),
            new EvidenceRows.UnparseableLeaveScopeRow("LEAVE:1", "raw2", "OA_DOCUMENT", null, null, null,
                null, null, false, null, null, null, null, null, false, VERSION, null, null, false)
        ).map(Arguments::of);
    }
    @Test void missingHistoryOrAliasCollisionDoesNotProveAbsence() {
        assertThat(proof(candidate(), List.of())).isFalse();
        assertThat(UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "target", candidate(), List.of(history()), List.of("SZT0001"), List.of(candidate()))).isFalse();
    }
    @ParameterizedTest @MethodSource("relatedSerials")
    void anyCurrentRelatedOrUnattributableRevocationPreventsIsolation(String originalSerial) {
        var revocation = new OaDocumentRecord("LEAVE_REVOCATION:2", VERSION, "other-member", "OTHER",
                DocumentType.LEAVE_REVOCATION, SourceStatus.REVOKED, START, START, "Asia/Shanghai",
                START, null, null, null, null, false, null, null, "R1", originalSerial);
        assertThat(UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "target", candidate(), List.of(history()), List.of("SZJN0001"), List.of(candidate(), revocation))).isFalse();
    }
    static Stream<Arguments> relatedSerials() { return Stream.of("L1", " L1 ", "", " ", null).map(Arguments::of); }
    private static EvidenceRows.UnparseableLeaveScopeRow sameCompanyOtherEmployee() {
        return history("target", "other-employee", "SZST0001", "SZST0001", false, "UNKNOWN", VERSION, "L1", false);
    }

    private static boolean employeeProof(OaDocumentRecord current, List<EvidenceRows.UnparseableLeaveScopeRow> rows) {
        return UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "target", List.of("selected-employee"), current, rows, List.of("SZST9001"), List.of(current));
    }

    @Test
    void explicitEmployeeScopeProvesOnlyTheUnselectedOwnerWithoutReclassifyingInvalidPendingLeave() {
        var current = candidate();
        var old = sameCompanyOtherEmployee();
        assertThat(employeeProof(current, List.of(old))).isTrue();
        assertThat(current.sourceStatus()).isEqualTo(SourceStatus.PENDING);
        assertThat(current.end()).isNull();
        assertThat(current.employeeNumber()).isNull();
        assertThat(UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "target", current, List.of(old), List.of("SZST9001"), List.of(current))).isFalse();
        assertThat(UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "target", List.of(), current, List.of(old), List.of("SZST9001"), List.of(current))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("nonCandidates")
    void employeeScopeNeverOmitsValidGeometryKnownIdentityOrOtherStatus(OaDocumentRecord current) {
        assertThat(employeeProof(current, List.of(sameCompanyOtherEmployee()))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("unsafeEmployeeHistories")
    void employeeIsolationStillRequiresEveryHistoryVersionAndSerialToBeSafe(EvidenceRows.UnparseableLeaveScopeRow unsafe) {
        assertThat(employeeProof(candidate(), List.of(sameCompanyOtherEmployee(), unsafe))).isFalse();
    }

    static Stream<Arguments> unsafeEmployeeHistories() {
        return Stream.of(
            history("target", "selected-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", false),
            history("target", "another-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", false),
            history("another-company", "other-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", false),
            history("target", "other-employee", "SZT9001", "SZST0001", false, "PENDING", VERSION, "L1", false),
            history("target", "other-employee", "SZST0001", "SZT9001", false, "PENDING", VERSION, "L1", false),
            history("target", "other-employee", "SZST0001", "SZST0001", true, "PENDING", VERSION, "L1", false),
            history("target", "other-employee", "SZST0001", "SZST0001", false, "APPROVED", VERSION, "L1", false),
            history("target", "other-employee", "SZST0001", "SZST0001", false, "UNKNOWN", "unknown-version", "L1", false),
            history("target", "other-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "l1", false),
            history("target", "other-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, " L1", false),
            history("target", "other-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1 ", false),
            history("target", "other-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, null, false),
            history("target", "other-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", true),
            history("target", "other-employee", "SZST0001", "SZST0001", false, "PENDING", VERSION, "L1", null),
            new EvidenceRows.UnparseableLeaveScopeRow("LEAVE:1", "raw2", "OA_DOCUMENT", null, null, null,
                null, null, false, null, null, null, null, null, false, VERSION, null, null, false)
        ).map(Arguments::of);
    }

    @Test
    void employeeIsolationRequiresNonemptyCompleteTargetNumbersAndHistory() {
        assertThat(employeeProof(candidate(), List.of())).isFalse();
        assertThat(UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "target", List.of("selected-employee"), candidate(), List.of(sameCompanyOtherEmployee()), List.of(), List.of(candidate()))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("relatedSerials")
    void employeeIsolationDoesNotDiscardAnyPossiblyRelatedCurrentRevocation(String serial) {
        var revocation = new OaDocumentRecord("LEAVE_REVOCATION:2", VERSION, "unrelated-member", "OTHER",
                DocumentType.LEAVE_REVOCATION, SourceStatus.REVOKED, START, START, "Asia/Shanghai",
                START, null, null, null, null, false, null, null, "R1", serial);
        assertThat(UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                "target", List.of("selected-employee"), candidate(), List.of(sameCompanyOtherEmployee()),
                List.of("SZST9001"), List.of(candidate(), revocation))).isFalse();
    }

}
