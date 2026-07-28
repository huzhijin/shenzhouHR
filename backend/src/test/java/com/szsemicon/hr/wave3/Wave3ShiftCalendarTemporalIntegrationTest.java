package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.application.CalendarRepository;
import com.szsemicon.hr.attendance.application.ShiftRepository;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.CalendarModels.CalendarStatus;
import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import com.szsemicon.hr.attendance.domain.CalendarSnapshotDigest;
import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.SegmentType;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftTemplate;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import com.szsemicon.hr.attendance.domain.ShiftModels.VersionStatus;
import com.szsemicon.hr.attendance.domain.ShiftSnapshotDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Wave3ShiftCalendarTemporalIntegrationTest
        extends Wave1IntegrationTestSupport {

    private static final String LEGAL_ENTITY =
            "30000000-0000-0000-0000-000000000001";
    private static final String NON_CANONICAL_DIGEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private CalendarRepository calendarRepository;

    @Test
    void shiftFutureDeactivationCarriesAdjacentSuccessorWithoutTimelineGap() {
        String locationId = "a7100000-0000-0000-0000-000000000001";
        String shiftId = "a7200000-0000-0000-0000-000000000001";
        String firstVersionId = "a7300000-0000-0000-0000-000000000001";
        String successorVersionId = "a7300000-0000-0000-0000-000000000002";
        LocalDate firstFrom = LocalDate.of(2027, 1, 1);
        LocalDate boundary = LocalDate.of(2027, 2, 1);
        LocalDate successorTo = LocalDate.of(2027, 3, 1);
        Instant createdAt = Instant.parse("2026-07-20T00:00:00Z");
        Instant firstPublishedAt = Instant.parse("2026-07-21T00:00:00Z");
        Instant successorPublishedAt = Instant.parse("2026-07-21T01:00:00Z");
        Instant deactivatedAt = Instant.parse("2026-07-22T00:00:00Z");
        insertLocation(locationId, "TEMPORAL_SHIFT_LOCATION", createdAt);

        ShiftTemplate template = new ShiftTemplate(
                shiftId,
                LEGAL_ENTITY,
                locationId,
                "TEMPORAL_SHIFT",
                "连续时态班次",
                LifecycleStatus.ACTIVE,
                0,
                "创建连续时态班次",
                ADMIN_PRINCIPAL,
                createdAt,
                ADMIN_PRINCIPAL,
                createdAt);
        shiftRepository.insertTemplate(template, "temporal-shift-template-create");

        List<Segment> segments = List.of(new Segment(
                SegmentType.WORK,
                LocalTime.of(9, 0),
                0,
                LocalTime.of(18, 0),
                0));
        ShiftVersion first = shiftVersion(
                firstVersionId,
                shiftId,
                1,
                firstFrom,
                boundary,
                segments,
                createdAt);
        ShiftVersion successor = shiftVersion(
                successorVersionId,
                shiftId,
                2,
                boundary,
                successorTo,
                segments,
                createdAt.plusSeconds(60));
        shiftRepository.insertVersion(
                first, segmentsJson(), "temporal-shift-version-first");
        shiftRepository.insertVersion(
                successor, segmentsJson(), "temporal-shift-version-successor");

        ShiftVersion persistedFirst =
                shiftRepository.findVersion(firstVersionId).orElseThrow();
        ShiftVersion persistedSuccessor =
                shiftRepository.findVersion(successorVersionId).orElseThrow();
        String firstCanonicalDigest = ShiftSnapshotDigest.digest(persistedFirst);
        String successorCanonicalDigest =
                ShiftSnapshotDigest.digest(persistedSuccessor);
        assertThat(persistedFirst.snapshotDigest())
                .isEqualTo(firstCanonicalDigest);
        assertThat(persistedSuccessor.snapshotDigest())
                .isEqualTo(successorCanonicalDigest);
        assertThat(shiftRepository.publishVersion(
                firstVersionId,
                0,
                NON_CANONICAL_DIGEST,
                ADMIN_PRINCIPAL,
                "错误摘要不得绑定发布",
                firstPublishedAt.minusSeconds(1))).isFalse();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM shift_publication_timeline
                WHERE shift_version_id = ?
                """,
                Long.class,
                firstVersionId)).isZero();
        assertThat(shiftRepository.publishVersion(
                firstVersionId,
                0,
                firstCanonicalDigest,
                ADMIN_PRINCIPAL,
                "发布第一段班次",
                firstPublishedAt)).isTrue();
        assertThat(shiftRepository.publishVersion(
                successorVersionId,
                0,
                successorCanonicalDigest,
                ADMIN_PRINCIPAL,
                "发布承接班次",
                successorPublishedAt)).isTrue();

        String databaseDigestBeforeDeactivation = jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM shift_version
                WHERE shift_version_id = ?
                """,
                String.class,
                firstVersionId);
        assertThat(databaseDigestBeforeDeactivation)
                .isEqualTo(firstCanonicalDigest);
        assertThat(shiftRepository.scheduleVersionDeactivation(
                firstVersionId,
                1,
                boundary,
                successorVersionId,
                ADMIN_PRINCIPAL,
                "未来边界停用并原子承接",
                deactivatedAt)).isTrue();

        assertThat(jdbc.queryForList(
                """
                SELECT state
                FROM shift_publication_timeline
                WHERE shift_template_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                shiftId))
                .containsExactly(
                        "PUBLISHED", "PUBLISHED", "INACTIVE", "PUBLISHED");
        assertThat(jdbc.queryForList(
                """
                SELECT business_effective_from
                FROM shift_publication_timeline
                WHERE shift_template_id = ?
                ORDER BY event_sequence
                """,
                Date.class,
                shiftId))
                .containsExactly(
                        Date.valueOf(firstFrom),
                        Date.valueOf(boundary),
                        Date.valueOf(boundary),
                        Date.valueOf(boundary));

        Instant afterDeactivation = deactivatedAt.plusSeconds(1);
        assertThat(shiftRepository.resolvePublishedAt(
                shiftId, boundary.minusDays(1), afterDeactivation))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.shiftVersionId())
                            .isEqualTo(firstVersionId);
                    assertThat(resolved.status())
                            .isEqualTo(VersionStatus.PUBLISHED);
                    assertThat(resolved.snapshotDigest())
                            .isEqualTo(firstCanonicalDigest);
                });
        assertThat(shiftRepository.resolvePublishedAt(
                shiftId, boundary, afterDeactivation))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.shiftVersionId())
                            .isEqualTo(successorVersionId);
                    assertThat(resolved.status())
                            .isEqualTo(VersionStatus.PUBLISHED);
                });
        assertThat(shiftRepository.resolvePublishedAt(
                shiftId, boundary.plusDays(14), afterDeactivation))
                .singleElement()
                .extracting(ShiftVersion::shiftVersionId)
                .isEqualTo(successorVersionId);
        assertThat(shiftRepository.findVersion(firstVersionId))
                .get()
                .satisfies(current -> {
                    assertThat(current.status())
                            .isEqualTo(VersionStatus.INACTIVE);
                    assertThat(current.effectiveTo()).isEqualTo(boundary);
                    assertThat(current.snapshotDigest())
                            .isEqualTo(firstCanonicalDigest);
                    assertThat(current.rowVersion()).isEqualTo(2);
                });
        assertThat(jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM shift_version
                WHERE shift_version_id = ?
                """,
                String.class,
                firstVersionId)).isEqualTo(databaseDigestBeforeDeactivation);
    }

    @Test
    void calendarPublishPersistsCanonicalDigestAndFutureCarryStaysContinuous() {
        String locationId = "a7100000-0000-0000-0000-000000000002";
        String calendarId = "a7400000-0000-0000-0000-000000000001";
        String firstDraftVersionId =
                "a7500000-0000-0000-0000-000000000001";
        String successorDraftVersionId =
                "a7500000-0000-0000-0000-000000000002";
        LocalDate firstFrom = LocalDate.of(2027, 1, 1);
        LocalDate boundary = LocalDate.of(2027, 1, 3);
        LocalDate successorTo = LocalDate.of(2027, 1, 5);
        Instant createdAt = Instant.parse("2026-07-20T00:00:00Z");
        Instant firstPublishedAt = Instant.parse("2026-07-21T00:00:00Z");
        Instant successorPublishedAt = Instant.parse("2026-07-21T01:00:00Z");
        Instant deactivatedAt = Instant.parse("2026-07-22T00:00:00Z");
        insertLocation(locationId, "TEMPORAL_CALENDAR_LOCATION", createdAt);

        WorkCalendar firstDraft = calendar(
                calendarId,
                locationId,
                firstDraftVersionId,
                1,
                firstFrom,
                boundary,
                "连续时态日历一",
                createdAt);
        calendarRepository.insertCalendar(
                firstDraft, "temporal-calendar-first-create");
        calendarRepository.replaceDays(
                firstDraftVersionId,
                List.of(
                        calendarDay(
                                calendarId,
                                firstDraftVersionId,
                                firstFrom,
                                DayType.WORKDAY,
                                createdAt),
                        calendarDay(
                                calendarId,
                                firstDraftVersionId,
                                firstFrom.plusDays(1),
                                DayType.WEEKEND,
                                createdAt)),
                0,
                ADMIN_PRINCIPAL,
                "补齐第一段日历日",
                createdAt.plusSeconds(60));

        WorkCalendar first = calendarRepository.findCalendar(calendarId)
                .orElseThrow();
        List<WorkCalendarDay> firstDays = calendarRepository.listDays(
                first.calendarVersionId(),
                firstFrom,
                boundary.minusDays(1),
                100,
                0);
        String firstCanonicalDigest =
                CalendarSnapshotDigest.digest(first, firstDays);
        assertThat(first.snapshotDigest()).isEqualTo(firstCanonicalDigest);
        assertThat(calendarRepository.publishVersion(
                first.calendarVersionId(),
                first.rowVersion(),
                NON_CANONICAL_DIGEST,
                ADMIN_PRINCIPAL,
                "错误摘要不得绑定发布",
                firstPublishedAt.minusSeconds(1))).isFalse();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM calendar_publication_timeline
                WHERE work_calendar_version_id = ?
                """,
                Long.class,
                first.calendarVersionId())).isZero();
        assertThat(calendarRepository.publishVersion(
                first.calendarVersionId(),
                first.rowVersion(),
                firstCanonicalDigest,
                ADMIN_PRINCIPAL,
                "发布第一段日历",
                firstPublishedAt)).isTrue();

        WorkCalendar successorDraft = calendar(
                calendarId,
                locationId,
                successorDraftVersionId,
                calendarRepository.nextVersionNumber(calendarId),
                boundary,
                successorTo,
                "连续时态日历二",
                createdAt.plusSeconds(120));
        calendarRepository.insertVersion(
                successorDraft, "temporal-calendar-successor-create");
        calendarRepository.replaceDays(
                successorDraftVersionId,
                List.of(
                        calendarDay(
                                calendarId,
                                successorDraftVersionId,
                                boundary,
                                DayType.WORKDAY,
                                createdAt.plusSeconds(120)),
                        calendarDay(
                                calendarId,
                                successorDraftVersionId,
                                boundary.plusDays(1),
                                DayType.SPECIAL_WORKDAY,
                                createdAt.plusSeconds(120))),
                0,
                ADMIN_PRINCIPAL,
                "补齐承接日历日",
                createdAt.plusSeconds(180));
        WorkCalendar successor = calendarRepository.findCalendar(calendarId)
                .orElseThrow();
        List<WorkCalendarDay> successorDays = calendarRepository.listDays(
                successor.calendarVersionId(),
                boundary,
                successorTo.minusDays(1),
                100,
                0);
        String successorCanonicalDigest =
                CalendarSnapshotDigest.digest(successor, successorDays);
        assertThat(successor.snapshotDigest())
                .isEqualTo(successorCanonicalDigest);
        assertThat(calendarRepository.publishVersion(
                successor.calendarVersionId(),
                successor.rowVersion(),
                successorCanonicalDigest,
                ADMIN_PRINCIPAL,
                "发布承接日历",
                successorPublishedAt)).isTrue();

        WorkCalendar publishedFirst = calendarRepository.findVersion(
                first.calendarVersionId()).orElseThrow();
        assertThat(calendarRepository.scheduleVersionDeactivation(
                publishedFirst.calendarVersionId(),
                publishedFirst.rowVersion(),
                boundary,
                successor.calendarVersionId(),
                ADMIN_PRINCIPAL,
                "未来边界停用并原子承接",
                deactivatedAt)).isTrue();

        String databaseDigest = jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM work_calendar_version
                WHERE work_calendar_version_id = ?
                """,
                String.class,
                first.calendarVersionId());
        assertThat(databaseDigest).isEqualTo(firstCanonicalDigest);
        assertThat(calendarRepository.findVersion(first.calendarVersionId()))
                .get()
                .satisfies(persisted -> {
                    assertThat(persisted.snapshotDigest())
                            .isEqualTo(firstCanonicalDigest);
                    assertThat(persisted.status())
                            .isEqualTo(CalendarStatus.INACTIVE);
                });

        Instant afterDeactivation = deactivatedAt.plusSeconds(1);
        assertThat(calendarRepository.resolvePublishedVersions(
                calendarId, boundary.minusDays(1), afterDeactivation))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.calendarVersionId())
                            .isEqualTo(first.calendarVersionId());
                    assertThat(resolved.status())
                            .isEqualTo(CalendarStatus.PUBLISHED);
                    assertThat(resolved.snapshotDigest())
                            .isEqualTo(firstCanonicalDigest);
                });
        assertThat(calendarRepository.resolvePublishedVersions(
                calendarId, boundary, afterDeactivation))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.calendarVersionId())
                            .isEqualTo(successor.calendarVersionId());
                    assertThat(resolved.status())
                            .isEqualTo(CalendarStatus.PUBLISHED);
                    assertThat(resolved.snapshotDigest())
                            .isEqualTo(successorCanonicalDigest);
                });
        assertThat(calendarRepository.resolvePublishedVersions(
                calendarId, boundary.plusDays(1), afterDeactivation))
                .singleElement()
                .extracting(WorkCalendar::calendarVersionId)
                .isEqualTo(successor.calendarVersionId());
    }

    private ShiftVersion shiftVersion(
            String versionId,
            String shiftId,
            int versionNumber,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            List<Segment> segments,
            Instant createdAt) {
        return new ShiftVersion(
                versionId,
                shiftId,
                versionNumber,
                VersionStatus.DRAFT,
                effectiveFrom,
                effectiveTo,
                "Asia/Shanghai",
                segments,
                null,
                0,
                "创建连续时态班次版本",
                ADMIN_PRINCIPAL,
                createdAt,
                null,
                ADMIN_PRINCIPAL,
                createdAt);
    }

    private WorkCalendar calendar(
            String calendarId,
            String locationId,
            String versionId,
            int versionNumber,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String name,
            Instant createdAt) {
        return new WorkCalendar(
                calendarId,
                LEGAL_ENTITY,
                locationId,
                "TEMPORAL_CALENDAR",
                versionId,
                versionNumber,
                name,
                effectiveFrom.getYear(),
                "Asia/Shanghai",
                CalendarStatus.DRAFT,
                effectiveFrom,
                effectiveTo,
                null,
                0,
                "创建连续时态日历版本",
                ADMIN_PRINCIPAL,
                createdAt,
                ADMIN_PRINCIPAL,
                createdAt);
    }

    private WorkCalendarDay calendarDay(
            String calendarId,
            String versionId,
            LocalDate businessDate,
            DayType dayType,
            Instant createdAt) {
        return new WorkCalendarDay(
                UUID.randomUUID().toString(),
                calendarId,
                versionId,
                businessDate,
                dayType,
                null,
                0,
                "配置连续时态日历日",
                ADMIN_PRINCIPAL,
                createdAt,
                ADMIN_PRINCIPAL,
                createdAt);
    }

    private String segmentsJson() {
        return """
                [{
                  "segmentType":"WORK",
                  "startLocalTime":"09:00:00",
                  "startDayOffset":0,
                  "endLocalTime":"18:00:00",
                  "endDayOffset":0
                }]
                """;
    }

    private void insertLocation(
            String locationId, String code, Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO location (
                    location_id, legal_entity_id, location_code, row_version,
                    created_by, created_at
                ) VALUES (?, ?, ?, 0, ?, ?)
                """,
                locationId,
                LEGAL_ENTITY,
                code,
                ADMIN_PRINCIPAL,
                Timestamp.from(createdAt));
    }
}
