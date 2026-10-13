package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.port.AttendancePeriodProtectionPort
        .PeriodStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectionBackedAttendancePeriodProtectionTest {

    private static final String COMPANY = "company-1";
    private static final String EMPLOYEE = "employee-1";
    private static final LocalDate BUSINESS_DATE =
            LocalDate.parse("2026-07-29");

    private final AttendancePeriodProtectionMapper mapper =
            mock(AttendancePeriodProtectionMapper.class);
    private final ProjectionBackedAttendancePeriodProtection protection =
            new ProjectionBackedAttendancePeriodProtection(mapper);

    @Test
    void explicitOpenProjectionAllowsMutation() {
        when(mapper.resolveLatestPublished(
                        COMPANY, EMPLOYEE, BUSINESS_DATE))
                .thenReturn(List.of(row("OPEN", "a".repeat(64))));

        var result = protection.protectionFor(
                COMPANY, EMPLOYEE, BUSINESS_DATE);

        assertThat(result.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(result.periodVersion()).isEqualTo("projection-v1");
        assertThat(result.snapshotDigest()).matches("[0-9a-f]{64}");
        assertThat(result.allowsEffectiveMutation()).isTrue();
        verify(mapper).resolveLatestPublished(
                COMPANY, EMPLOYEE, BUSINESS_DATE);
    }

    @Test
    void frozenAndClosedProjectionsBlockMutation() {
        when(mapper.resolveLatestPublished(
                        COMPANY, EMPLOYEE, BUSINESS_DATE))
                .thenReturn(
                        List.of(row("FROZEN", "a".repeat(64))),
                        List.of(row("CLOSED", "b".repeat(64))));

        var frozen = protection.protectionFor(
                COMPANY, EMPLOYEE, BUSINESS_DATE);
        var closed = protection.protectionFor(
                COMPANY, EMPLOYEE, BUSINESS_DATE);

        assertThat(frozen.status()).isEqualTo(PeriodStatus.FROZEN);
        assertThat(frozen.allowsEffectiveMutation()).isFalse();
        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closed.allowsEffectiveMutation()).isFalse();
    }

    @Test
    void authoritativeReopenedProjectionAllowsRecalculationMutation() {
        when(mapper.resolveLatestPublished(
                        COMPANY, EMPLOYEE, BUSINESS_DATE))
                .thenReturn(List.of(row("REOPENED", "a".repeat(64))));

        var result = protection.protectionFor(
                COMPANY, EMPLOYEE, BUSINESS_DATE);

        assertThat(result.status()).isEqualTo(PeriodStatus.REOPENED);
        assertThat(result.allowsEffectiveMutation()).isTrue();
    }

    @Test
    void absentAmbiguousOrCrossLegalAuthorityRemainsUnknown() {
        AttendancePeriodProjectionRow crossLegal =
                withCompany(row("OPEN", "a".repeat(64)),
                        "company-2");
        when(mapper.resolveLatestPublished(
                        COMPANY, EMPLOYEE, BUSINESS_DATE))
                .thenReturn(
                        List.of(),
                        List.of(
                                row("OPEN", "a".repeat(64)),
                                row("OPEN", "b".repeat(64))),
                        List.of(crossLegal));

        var absent = protection.protectionFor(
                COMPANY, EMPLOYEE, BUSINESS_DATE);
        var ambiguous = protection.protectionFor(
                COMPANY, EMPLOYEE, BUSINESS_DATE);
        var crossEntity = protection.protectionFor(
                COMPANY, EMPLOYEE, BUSINESS_DATE);

        assertThat(absent.status()).isEqualTo(PeriodStatus.UNKNOWN);
        assertThat(ambiguous.status()).isEqualTo(PeriodStatus.UNKNOWN);
        assertThat(crossEntity.status()).isEqualTo(PeriodStatus.UNKNOWN);
        assertThat(absent.allowsEffectiveMutation()).isFalse();
        assertThat(ambiguous.allowsEffectiveMutation()).isFalse();
        assertThat(crossEntity.allowsEffectiveMutation()).isFalse();
    }

    @Test
    void periodSummaryChangesWithProjectionSnapshot() {
        when(mapper.resolveLatestPublished(
                        COMPANY, EMPLOYEE, BUSINESS_DATE))
                .thenReturn(
                        List.of(row("OPEN", "a".repeat(64))),
                        List.of(row("OPEN", "b".repeat(64))));

        String first = protection.protectionFor(
                        COMPANY, EMPLOYEE, BUSINESS_DATE)
                .snapshotDigest();
        String second = protection.protectionFor(
                        COMPANY, EMPLOYEE, BUSINESS_DATE)
                .snapshotDigest();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void malformedInputAndNullPersistenceResultFailSafely() {
        assertThatThrownBy(() -> protection.protectionFor(
                        "legal\nentity", EMPLOYEE, BUSINESS_DATE))
                .isInstanceOf(IllegalArgumentException.class);

        when(mapper.resolveLatestPublished(
                        COMPANY, EMPLOYEE, BUSINESS_DATE))
                .thenReturn(null);
        assertThatThrownBy(() -> protection.protectionFor(
                        COMPANY, EMPLOYEE, BUSINESS_DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned null");
    }

    private static AttendancePeriodProjectionRow row(
            String state, String projectionDigest) {
        return new AttendancePeriodProjectionRow(
                "projection-1",
                COMPANY,
                LocalDate.parse("2026-07-01"),
                LocalDate.parse("2026-08-01"),
                state,
                "projection-v1",
                "c".repeat(64),
                projectionDigest,
                Instant.parse("2026-07-29T07:00:00Z"),
                EMPLOYEE,
                "employee-version-1",
                3,
                "employment-period-1",
                "employment-assignment-1",
                4,
                "organization-1");
    }

    private static AttendancePeriodProjectionRow withCompany(
            AttendancePeriodProjectionRow row, String companyId) {
        return new AttendancePeriodProjectionRow(
                row.projectionId(),
                companyId,
                row.periodStart(),
                row.periodEndExclusive(),
                row.periodState(),
                row.projectionVersion(),
                row.sourceSnapshotDigest(),
                row.projectionDigest(),
                row.publishedAt(),
                row.employeeId(),
                row.employeeVersionId(),
                row.employeeVersion(),
                row.employmentPeriodId(),
                row.employmentAssignmentId(),
                row.employmentVersion(),
                row.organizationId());
    }
}
