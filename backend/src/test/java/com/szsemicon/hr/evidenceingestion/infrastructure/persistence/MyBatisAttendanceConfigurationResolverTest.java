package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MyBatisAttendanceConfigurationResolverTest {

    private static final Instant KNOWLEDGE_AT =
            Instant.parse("2026-07-29T08:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(KNOWLEDGE_AT, ZoneOffset.UTC);
    private static final String LEGAL_ENTITY = "legal-entity-1";
    private static final String EMPLOYEE = "employee-1";

    private final AttendanceConfigurationAuthorityMapper mapper =
            mock(AttendanceConfigurationAuthorityMapper.class);
    private final MyBatisAttendanceConfigurationResolver resolver =
            new MyBatisAttendanceConfigurationResolver(mapper, CLOCK);

    @BeforeEach
    void defaultToNoAuthority() {
        for (LocalDate date = LocalDate.parse("2026-07-26");
                !date.isAfter(LocalDate.parse("2026-07-30"));
                date = date.plusDays(1)) {
            when(mapper.resolveForBusinessDate(
                            LEGAL_ENTITY, EMPLOYEE, date, KNOWLEDGE_AT))
                    .thenReturn(List.of());
        }
    }

    @Test
    void resolvesPunchDateAndPreviousDateForCrossMidnightPunch() {
        Instant punch = Instant.parse("2026-07-28T17:30:00Z");
        LocalDate previous = LocalDate.parse("2026-07-28");
        LocalDate current = LocalDate.parse("2026-07-29");
        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, previous, KNOWLEDGE_AT))
                .thenReturn(List.of(row(previous)));
        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, current, KNOWLEDGE_AT))
                .thenReturn(List.of(row(current)));

        var result = resolver.resolve(LEGAL_ENTITY, EMPLOYEE, punch);

        assertThat(result.authoritative()).isTrue();
        assertThat(result.businessTimeZone())
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(result.candidateBusinessDates())
                .containsExactlyInAnyOrder(previous, current);
        assertThat(result.locationId()).isEqualTo("location-1");
        assertThat(result.attendanceGroupRevisionId())
                .isEqualTo("group-revision-1");
        assertThat(result.shiftVersionId()).isEqualTo("shift-version-1");
        assertThat(result.resolverSnapshotDigest())
                .matches("[0-9a-f]{64}");
        verify(mapper).resolveForBusinessDate(
                LEGAL_ENTITY,
                EMPLOYEE,
                LocalDate.parse("2026-07-26"),
                KNOWLEDGE_AT);
        verify(mapper).resolveForBusinessDate(
                LEGAL_ENTITY,
                EMPLOYEE,
                LocalDate.parse("2026-07-29"),
                KNOWLEDGE_AT);
    }

    @Test
    void ordinaryPunchUsesOnlyItsLocationBusinessDate() {
        Instant punch = Instant.parse("2026-07-29T04:00:00Z");
        LocalDate current = LocalDate.parse("2026-07-29");
        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, current, KNOWLEDGE_AT))
                .thenReturn(List.of(row(current)));

        var result = resolver.resolve(LEGAL_ENTITY, EMPLOYEE, punch);

        assertThat(result.authoritative()).isTrue();
        assertThat(result.candidateBusinessDates())
                .isEqualTo(Set.of(current));
    }

    @Test
    void missingOrMultipleApplicableRowsFailClosed() {
        Instant punch = Instant.parse("2026-07-28T17:30:00Z");
        LocalDate previous = LocalDate.parse("2026-07-28");
        LocalDate current = LocalDate.parse("2026-07-29");
        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, current, KNOWLEDGE_AT))
                .thenReturn(List.of(row(current)));

        assertThat(resolver.resolve(LEGAL_ENTITY, EMPLOYEE, punch)
                        .authoritative())
                .isFalse();

        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, previous, KNOWLEDGE_AT))
                .thenReturn(List.of(row(previous), row(previous)));

        assertThat(resolver.resolve(LEGAL_ENTITY, EMPLOYEE, punch)
                        .authoritative())
                .isFalse();
    }

    @Test
    void crossLegalOrInconsistentCrossDayConfigurationFailsClosed() {
        Instant punch = Instant.parse("2026-07-28T17:30:00Z");
        LocalDate previous = LocalDate.parse("2026-07-28");
        LocalDate current = LocalDate.parse("2026-07-29");
        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, previous, KNOWLEDGE_AT))
                .thenReturn(List.of(row(previous)));
        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, current, KNOWLEDGE_AT))
                .thenReturn(List.of(withLegalEntity(
                        row(current), "legal-entity-2")));

        assertThat(resolver.resolve(LEGAL_ENTITY, EMPLOYEE, punch)
                        .authoritative())
                .isFalse();

        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, current, KNOWLEDGE_AT))
                .thenReturn(List.of(withShift(
                        row(current), "shift-version-2")));

        assertThat(resolver.resolve(LEGAL_ENTITY, EMPLOYEE, punch)
                        .authoritative())
                .isFalse();
    }

    @Test
    void stableSummaryChangesWhenAuthoritativeSnapshotChanges() {
        Instant punch = Instant.parse("2026-07-29T04:00:00Z");
        LocalDate current = LocalDate.parse("2026-07-29");
        AttendanceConfigurationAuthorityRow initial = row(current);
        AttendanceConfigurationAuthorityRow changed =
                withDayDigest(initial, "f".repeat(64));
        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, current, KNOWLEDGE_AT))
                .thenReturn(List.of(initial), List.of(changed));

        String first = resolver.resolve(LEGAL_ENTITY, EMPLOYEE, punch)
                .resolverSnapshotDigest();
        String second = resolver.resolve(LEGAL_ENTITY, EMPLOYEE, punch)
                .resolverSnapshotDigest();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void malformedInputAndNullPersistenceResultFailSafely() {
        assertThatThrownBy(() -> resolver.resolve(
                        "legal\nentity", EMPLOYEE, KNOWLEDGE_AT))
                .isInstanceOf(IllegalArgumentException.class);

        LocalDate firstSeed = LocalDate.parse("2026-07-27");
        when(mapper.resolveForBusinessDate(
                        LEGAL_ENTITY, EMPLOYEE, firstSeed, KNOWLEDGE_AT))
                .thenReturn(null);
        assertThatThrownBy(() -> resolver.resolve(
                        LEGAL_ENTITY,
                        EMPLOYEE,
                        Instant.parse("2026-07-29T04:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned null");
    }

    private static AttendanceConfigurationAuthorityRow row(LocalDate date) {
        String suffix = Integer.toString(date.getDayOfMonth());
        return new AttendanceConfigurationAuthorityRow(
                date,
                LEGAL_ENTITY,
                EMPLOYEE,
                "employee-version-1",
                3,
                "employment-period-1",
                "employment-assignment-1",
                4,
                "organization-1",
                "group-assignment-1",
                "1".repeat(64),
                "group-1",
                "group-revision-1",
                "2".repeat(64),
                "location-1",
                "location-revision-1",
                "Asia/Shanghai",
                "3".repeat(64),
                "calendar-1",
                "calendar-version-" + suffix,
                "Asia/Shanghai",
                "4".repeat(64),
                "calendar-day-" + suffix,
                "WORKDAY",
                "5".repeat(64),
                "group-shift-template-1",
                "shift-template-1",
                "shift-version-1",
                "Asia/Shanghai",
                "6".repeat(64));
    }

    private static AttendanceConfigurationAuthorityRow withLegalEntity(
            AttendanceConfigurationAuthorityRow row, String legalEntityId) {
        return copy(row, legalEntityId, row.shiftVersionId(),
                row.calendarDaySnapshotDigest());
    }

    private static AttendanceConfigurationAuthorityRow withShift(
            AttendanceConfigurationAuthorityRow row, String shiftVersionId) {
        return copy(row, row.legalEntityId(), shiftVersionId,
                row.calendarDaySnapshotDigest());
    }

    private static AttendanceConfigurationAuthorityRow withDayDigest(
            AttendanceConfigurationAuthorityRow row, String dayDigest) {
        return copy(
                row, row.legalEntityId(), row.shiftVersionId(), dayDigest);
    }

    private static AttendanceConfigurationAuthorityRow copy(
            AttendanceConfigurationAuthorityRow row,
            String legalEntityId,
            String shiftVersionId,
            String dayDigest) {
        return new AttendanceConfigurationAuthorityRow(
                row.businessDate(),
                legalEntityId,
                row.employeeId(),
                row.employeeVersionId(),
                row.employeeVersion(),
                row.employmentPeriodId(),
                row.employmentAssignmentId(),
                row.employmentVersion(),
                row.organizationId(),
                row.attendanceGroupAssignmentId(),
                row.assignmentSnapshotDigest(),
                row.attendanceGroupId(),
                row.attendanceGroupRevisionId(),
                row.groupSnapshotDigest(),
                row.locationId(),
                row.locationRevisionId(),
                row.locationTimeZone(),
                row.locationSnapshotDigest(),
                row.workCalendarId(),
                row.workCalendarVersionId(),
                row.calendarTimeZone(),
                row.calendarSnapshotDigest(),
                row.workCalendarDayId(),
                row.dayType(),
                dayDigest,
                row.groupShiftTemplateId(),
                row.shiftTemplateId(),
                shiftVersionId,
                row.shiftTimeZone(),
                row.shiftSnapshotDigest());
    }
}
