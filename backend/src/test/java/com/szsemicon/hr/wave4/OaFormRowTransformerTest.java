package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer.IssueCode;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer.LocalDateRange;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer.LocalInterval;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaFormRowTransformer.LocalPoint;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.FormKind;
import com.szsemicon.hr.evidenceingestion.port.OaOrgMemberDirectoryPort;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaFormRowTransformerTest {

    private static final LocalDateTime START =
            LocalDateTime.of(2026, 7, 29, 9, 0);
    private static final LocalDateTime END =
            LocalDateTime.of(2026, 7, 29, 18, 0);

    @Test
    void resolvesOnlyThroughNumericOrgMemberIdAndPreservesCodeExactly() {
        var result = OaFormRowTransformer.transform(
                FormKind.TRIP,
                Map.of(
                        "field0137", "000123",
                        "field0148", START,
                        "field0149", END),
                Map.of(),
                id -> List.of(new OaOrgMemberDirectoryPort.OrgMemberRecord(
                        id, "00aBc-Z")));

        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().subject().orgMemberId())
                .isEqualTo(new BigInteger("123"));
        assertThat(result.candidate().subject().memberCode())
                .isEqualTo("00aBc-Z");
        assertThat(result.candidate().temporal())
                .isEqualTo(new LocalInterval(START, END));
        assertThat(result.effectiveCandidate()).isFalse();
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains(
                        IssueCode.LIVE_SCHEMA_NOT_VERIFIED,
                        IssueCode.APPROVAL_STATUS_NOT_VERIFIED,
                        IssueCode.SOURCE_TIME_ZONE_NOT_VERIFIED)
                .doesNotContain(
                        IssueCode.MAIN_DETAIL_FOREIGN_KEY_NOT_VERIFIED,
                        IssueCode.ENUM_MAPPING_NOT_VERIFIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", " 1", "1 ", "+1", "-1", "0", "1.0",
            "1,2", "[1]", "１", "abc"
    })
    void rejectsAnythingOtherThanOneStrictAsciiNumericId(String rawId) {
        AtomicInteger lookupCount = new AtomicInteger();

        var result = OaFormRowTransformer.transform(
                FormKind.TRIP,
                Map.of(
                        "field0137", rawId,
                        "field0148", START,
                        "field0149", END),
                Map.of(),
                id -> {
                    lookupCount.incrementAndGet();
                    return List.of();
                });

        assertThat(result.candidate()).isNull();
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.INVALID_SUBJECT_MEMBER_ID);
        assertThat(lookupCount).hasValue(0);
    }

    @Test
    void rejectsArrayOrDecimalSubjectValuesWithoutCallingDirectory() {
        AtomicInteger lookupCount = new AtomicInteger();

        var arrayResult = OaFormRowTransformer.transform(
                FormKind.TRIP,
                Map.of(
                        "field0137", List.of(1, 2),
                        "field0148", START,
                        "field0149", END),
                Map.of(),
                id -> {
                    lookupCount.incrementAndGet();
                    return List.of();
                });
        var decimalResult = OaFormRowTransformer.transform(
                FormKind.TRIP,
                Map.of(
                        "field0137", 123.0d,
                        "field0148", START,
                        "field0149", END),
                Map.of(),
                id -> {
                    lookupCount.incrementAndGet();
                    return List.of();
                });

        assertThat(arrayResult.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.INVALID_SUBJECT_MEMBER_ID);
        assertThat(decimalResult.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.INVALID_SUBJECT_MEMBER_ID);
        assertThat(lookupCount).hasValue(0);
    }

    @Test
    void failsClosedForZeroOrMultipleOrgMemberRows() {
        var missing = leaveResult(
                "000123",
                "EMP-001",
                id -> List.of());
        var ambiguous = leaveResult(
                "000123",
                "EMP-001",
                id -> List.of(
                        new OaOrgMemberDirectoryPort.OrgMemberRecord(
                                id, "EMP-001"),
                        new OaOrgMemberDirectoryPort.OrgMemberRecord(
                                id, "EMP-001")));

        assertThat(missing.candidate()).isNull();
        assertThat(missing.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.ORG_MEMBER_NOT_FOUND);
        assertThat(ambiguous.candidate()).isNull();
        assertThat(ambiguous.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.ORG_MEMBER_AMBIGUOUS);
    }

    @Test
    void treatsFormEmployeeNumberOnlyAsCaseSensitiveConsistencyCheck() {
        AtomicInteger lookupCount = new AtomicInteger();
        var exact = leaveResult(
                "123",
                "00AbC",
                id -> {
                    lookupCount.incrementAndGet();
                    return List.of(
                            new OaOrgMemberDirectoryPort.OrgMemberRecord(
                                    id, "00AbC"));
                });
        var mismatchedCase = leaveResult(
                "123",
                "00abc",
                id -> {
                    lookupCount.incrementAndGet();
                    return List.of(
                            new OaOrgMemberDirectoryPort.OrgMemberRecord(
                                    id, "00AbC"));
                });

        assertThat(exact.candidate().subject().memberCode())
                .isEqualTo("00AbC");
        assertThat(exact.issues())
                .extracting(issue -> issue.code())
                .doesNotContain(IssueCode.EMPLOYEE_CODE_MISMATCH);
        assertThat(mismatchedCase.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.EMPLOYEE_CODE_MISMATCH);
        assertThat(lookupCount).hasValue(2);
    }

    @Test
    void keepsExemptPunchAsDatesWithoutInventingEndOfDay() {
        LocalDate from = LocalDate.of(2026, 7, 29);
        LocalDate to = LocalDate.of(2026, 7, 29);
        var result = OaFormRowTransformer.transform(
                FormKind.EXEMPT_PUNCH,
                Map.of("field0083", "synthetic-filler"),
                Map.of(
                        "field0127", "123",
                        "field0131", "0007A",
                        "field0132", from,
                        "field0134", to),
                matching("0007A"));

        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().temporal())
                .isEqualTo(new LocalDateRange(from, to));
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains(
                        IssueCode.MAIN_DETAIL_FOREIGN_KEY_NOT_VERIFIED,
                        IssueCode.DATE_RANGE_BOUNDARY_NOT_VERIFIED)
                .doesNotContain(IssueCode.SOURCE_TIME_ZONE_NOT_VERIFIED);
    }

    @Test
    void keepsPunchCorrectionAsOnePointRatherThanAnInterval() {
        var result = OaFormRowTransformer.transform(
                FormKind.PUNCH_CORRECTION,
                Map.of("field0083", "synthetic-filler"),
                Map.of(
                        "field0127", 123L,
                        "field0131", "0007A",
                        "field0132", START),
                matching("0007A"));

        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().temporal())
                .isEqualTo(new LocalPoint(START));
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains(
                        IssueCode.APPROVAL_STATUS_NOT_VERIFIED,
                        IssueCode.MAIN_DETAIL_FOREIGN_KEY_NOT_VERIFIED,
                        IssueCode.ENUM_MAPPING_NOT_VERIFIED,
                        IssueCode.SOURCE_TIME_ZONE_NOT_VERIFIED);
    }

    @Test
    void transformsTheCompleteLeaveRevocationAllowlistFailClosed() {
        var result = OaFormRowTransformer.transform(
                FormKind.LEAVE_REVOCATION,
                Map.ofEntries(
                        Map.entry("field0097", "REV-001"),
                        Map.entry("field0074", "filler"),
                        Map.entry("field0075", "HR"),
                        Map.entry("field0076", LocalDate.of(2026, 8, 13)),
                        Map.entry("field0100", 1L),
                        Map.entry("field0098", "original leave"),
                        Map.entry("field0099", "LEAVE-001"),
                        Map.entry("field0083", "123"),
                        Map.entry("field0092", "engineer"),
                        Map.entry("field0093", "P5"),
                        Map.entry("field0085", "R&D"),
                        Map.entry("field0084", "0007A"),
                        Map.entry("field0089", 5959840635913392019L),
                        Map.entry("field0086", START),
                        Map.entry("field0087", END),
                        Map.entry("field0088", 1.0d),
                        Map.entry("field0090", "note"),
                        Map.entry("field0091", "revocation reason"),
                        Map.entry("field0094", "delegate"),
                        Map.entry("field0095", "R&D"),
                        Map.entry("field0096", "0007A")),
                Map.of(),
                matching("0007A"));

        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().formKind())
                .isEqualTo(FormKind.LEAVE_REVOCATION);
        assertThat(result.candidate().temporal())
                .isEqualTo(new LocalInterval(START, END));
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains(
                        IssueCode.LIVE_SCHEMA_NOT_VERIFIED,
                        IssueCode.APPROVAL_STATUS_NOT_VERIFIED,
                        IssueCode.ENUM_MAPPING_NOT_VERIFIED,
                        IssueCode.SOURCE_TIME_ZONE_NOT_VERIFIED)
                .doesNotContain(
                        IssueCode.UNKNOWN_COLUMN,
                        IssueCode.MAIN_DETAIL_FOREIGN_KEY_NOT_VERIFIED,
                        IssueCode.EMPLOYEE_CODE_MISMATCH);
        assertThat(result.effectiveCandidate()).isFalse();
    }

    @Test
    void rejectsUnknownColumnsAndUnverifiedEnumContracts() {
        var result = OaFormRowTransformer.transform(
                FormKind.OVERTIME,
                Map.of(
                        "field0074", "synthetic-filler",
                        "field9999", "not-allowlisted"),
                Map.of(
                        "field0093", "123",
                        "field0094", "0007A",
                        "field0100", START,
                        "field0099", END),
                matching("0007A"));

        assertThat(result.candidate()).isNotNull();
        assertThat(result.effectiveCandidate()).isFalse();
        assertThat(result.issues())
                .contains(new OaFormRowTransformer.Issue(
                        IssueCode.UNKNOWN_COLUMN,
                        "field9999"));
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.ENUM_MAPPING_NOT_VERIFIED);
    }

    @Test
    void rejectsStringParsingAndInvalidTemporalRanges() {
        var stringTimes = OaFormRowTransformer.transform(
                FormKind.TRIP,
                Map.of(
                        "field0137", "123",
                        "field0148", "2026-07-29 09:00:00",
                        "field0149", "2026-07-29 18:00:00"),
                Map.of(),
                matching("0007A"));
        var reversed = OaFormRowTransformer.transform(
                FormKind.TRIP,
                Map.of(
                        "field0137", "123",
                        "field0148", END,
                        "field0149", START),
                Map.of(),
                matching("0007A"));

        assertThat(stringTimes.candidate()).isNull();
        assertThat(stringTimes.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.TEMPORAL_VALUE_INVALID);
        assertThat(reversed.candidate()).isNull();
        assertThat(reversed.issues())
                .extracting(issue -> issue.code())
                .contains(IssueCode.TEMPORAL_RANGE_INVALID);
    }

    private static OaFormRowTransformer.TransformResult leaveResult(
            Object memberId,
            Object employeeNumber,
            OaOrgMemberDirectoryPort directory) {
        return OaFormRowTransformer.transform(
                FormKind.LEAVE,
                Map.of(
                        "field0097", "LEAVE-001",
                        "field0083", memberId,
                        "field0084", employeeNumber,
                        "field0086", START,
                        "field0087", END),
                Map.of(),
                directory);
    }

    private static OaOrgMemberDirectoryPort matching(String code) {
        return id -> List.of(
                new OaOrgMemberDirectoryPort.OrgMemberRecord(id, code));
    }
}
