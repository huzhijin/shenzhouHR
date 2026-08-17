package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.DailyFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.ExceptionFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.OaDocumentFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.ProjectionDraft;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.StoredProjection;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.TimeAccountFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCurrentExceptionFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedProjectionMetadata;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedTimeAccountFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class AttendanceReportProjectionPublisherTest {

    private static final Instant NOW =
            Instant.parse("2026-07-31T10:00:00Z");

    @Test
    void publishesDraftFactsAndClosedStateBeforeAtomicPublish() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);

        var result = publisher.publish(command(
                PeriodState.CLOSED, daily(10), safeException()));

        assertThat(result.created()).isTrue();
        assertThat(result.periodState()).isEqualTo(PeriodState.CLOSED);
        assertThat(result.projectionVersion())
                .isEqualTo("ARP1-" + result.projectionDigest());
        assertThat(writer.calls)
                .containsExactly(
                        "LOCK",
                        "FIND",
                        "LATEST",
                        "DRAFT",
                        "DAILY",
                        "EXCEPTION",
                        "OA",
                        "ACCOUNT",
                        "PUBLISH");
        assertThat(writer.drafts).singleElement().satisfies(draft -> {
            assertThat(draft.periodState()).isEqualTo(PeriodState.CLOSED);
            assertThat(draft.periodStart())
                    .isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(draft.periodEndExclusive())
                    .isEqualTo(LocalDate.of(2026, 8, 1));
        });
    }

    @Test
    void identicalCanonicalInputIsIdempotentAndDoesNotRewriteFacts() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        PublishCommand command =
                command(PeriodState.FROZEN, daily(10), safeException());

        var first = publisher.publish(command);
        int writesAfterFirst = writer.writeCount();
        var second = publisher.publish(command);

        assertThat(second.created()).isFalse();
        assertThat(second.projectionId()).isEqualTo(first.projectionId());
        assertThat(second.projectionVersion())
                .isEqualTo(first.projectionVersion());
        assertThat(writer.writeCount()).isEqualTo(writesAfterFirst);
    }

    @Test
    void changedCalculatedContentCreatesANewImmutableVersion() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);

        var first = publisher.publish(command(
                PeriodState.OPEN, daily(10), safeException()));
        var second = publisher.publish(command(
                PeriodState.OPEN, daily(11), safeException()));

        assertThat(second.created()).isTrue();
        assertThat(second.projectionId()).isNotEqualTo(first.projectionId());
        assertThat(second.projectionVersion())
                .isNotEqualTo(first.projectionVersion());
        assertThat(writer.drafts).hasSize(2);
        assertThat(writer.publishedIds).hasSize(2);
    }

    @Test
    void rejectsALateArrivingSnapshotOlderThanTheLatestPublication() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        publisher.publish(withMetadata(
                command(PeriodState.OPEN, daily(10), safeException()),
                metadata(
                        PeriodState.OPEN,
                        Instant.parse("2026-07-31T09:30:00Z"))));
        int writes = writer.writeCount();

        assertThatThrownBy(() -> publisher.publish(withMetadata(
                        command(
                                PeriodState.OPEN,
                                daily(11),
                                safeException()),
                        metadata(
                                PeriodState.OPEN,
                                Instant.parse(
                                        "2026-07-31T09:00:00Z")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("older snapshot");
        assertThat(writer.writeCount()).isEqualTo(writes);
    }

    @Test
    void closedProjectionCannotBeRegressedToOrdinaryOpenPublication() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        publisher.publish(command(
                PeriodState.CLOSED, daily(10), safeException()));
        int writes = writer.writeCount();

        assertThatThrownBy(() -> publisher.publish(withMetadata(
                        command(
                                PeriodState.OPEN,
                                daily(11),
                                safeException()),
                        metadata(
                                PeriodState.OPEN,
                                Instant.parse(
                                        "2026-07-31T09:30:00Z")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period-state transition");
        assertThat(writer.writeCount()).isEqualTo(writes);
    }

    @Test
    void trustedAuthoritativeReopenCanFollowClosedProjection() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        publisher.publish(command(
                PeriodState.CLOSED, daily(10), safeException()));

        var reopened = publisher.publish(withMetadata(
                command(
                        PeriodState.REOPENED,
                        daily(11),
                        safeException()),
                metadata(
                        PeriodState.REOPENED,
                        Instant.parse("2026-07-31T09:30:00Z"))));

        assertThat(reopened.created()).isTrue();
        assertThat(reopened.periodState())
                .isEqualTo(PeriodState.REOPENED);
    }

    @Test
    void failsClosedBeforePersistenceForUnboundOrUnsafeException() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        ExceptionFact unsafe = new ExceptionFact(
                "case-a",
                "employee-b",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                LocalDate.of(2026, 7, 15),
                "LATE",
                ExceptionSeverity.WARNING,
                ExceptionState.OPEN,
                10,
                "OA原因=病假；位置=31.2,121.4",
                "calculation-v1");

        assertThatThrownBy(() -> publisher.publish(command(
                        PeriodState.OPEN, daily(10), unsafe)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exception fact");
        assertThat(writer.calls).isEmpty();
    }

    @Test
    void rejectsGraceExemptLateExceptionBeforePersistence() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);

        assertThatThrownBy(() -> publisher.publish(command(
                        PeriodState.OPEN,
                        daily(10, 0),
                        safeException())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grace-exempt late");
        assertThat(writer.calls).isEmpty();
    }

    @Test
    void aFactWriteFailureNeverTransitionsTheProjectionToPublished() {
        RecordingWriter writer = new RecordingWriter();
        writer.failOa = true;
        var publisher = publisher(writer);

        assertThatThrownBy(() -> publisher.publish(command(
                        PeriodState.OPEN, daily(10), safeException())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated OA reference rejection");
        assertThat(writer.calls).doesNotContain("PUBLISH");
        assertThat(writer.publishedIds).isEmpty();
    }

    @Test
    void publishesTrustedCurrentExceptionWithoutInventingADailyFact() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        ExceptionFact current = new ExceptionFact(
                "case-no-group",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                LocalDate.of(2026, 7, 15),
                "NO_ATTENDANCE_GROUP",
                ExceptionSeverity.ERROR,
                ExceptionState.PENDING_REVIEW,
                0,
                "原因码=NO_ATTENDANCE_GROUP；证据数量=1",
                "calculation-v1");
        var command = new PublishCommand(
                metadata(PeriodState.OPEN),
                List.of(),
                List.of(new VerifiedCurrentExceptionFact(
                        "legal-a",
                        "employee-version-a",
                        "assignment-a",
                        "organization-version-a",
                        current)),
                List.of(),
                List.of());

        publisher.publish(command);

        assertThat(writer.daily).isEmpty();
        assertThat(writer.exceptions).singleElement().satisfies(value ->
                assertThat(value.fact().exceptionType())
                        .isEqualTo("NO_ATTENDANCE_GROUP"));
        assertThat(writer.calls)
                .containsExactly(
                        "LOCK",
                        "FIND",
                        "LATEST",
                        "DRAFT",
                        "EXCEPTION",
                        "PUBLISH");
    }

    @Test
    void publicationBoundaryIsTransactional() throws Exception {
        Transactional annotation = AttendanceReportProjectionPublisher.class
                .getMethod("publish", PublishCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isFalse();
    }

    private AttendanceReportProjectionPublisher publisher(
            RecordingWriter writer) {
        return new AttendanceReportProjectionPublisher(
                writer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PublishCommand command(
            PeriodState periodState,
            DailyFact daily,
            ExceptionFact exception) {
        var metadata = metadata(periodState);
        var calculated = new VerifiedCalculatedFacts(
                "employee-version-a",
                "assignment-a",
                new ProjectionFacts(daily, List.of(exception)));
        var oa = new VerifiedOaDocumentFact(
                "legal-a",
                "oa-document-a",
                "employee-a",
                "employee-version-a",
                "assignment-a",
                "organization-a",
                "organization-version-a",
                "LEAVE",
                "ANNUAL_LEAVE",
                OaTemporalShape.INTERVAL,
                null,
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T02:00:00Z"),
                60,
                "APPROVED",
                "oa-v1");
        var account = new VerifiedTimeAccountFact(
                "legal-a",
                "annual-leave:employee-a:2026",
                "employee-a",
                "employee-version-a",
                "organization-a",
                "organization-version-a",
                TimeAccountType.ANNUAL_LEAVE,
                new BigDecimal("40.00"),
                new BigDecimal("80.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("8.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "ledger-v1");
        return new PublishCommand(
                metadata, List.of(calculated), List.of(oa), List.of(account));
    }

    private VerifiedProjectionMetadata metadata(PeriodState periodState) {
        return metadata(
                periodState, Instant.parse("2026-07-31T09:00:00Z"));
    }

    private VerifiedProjectionMetadata metadata(
            PeriodState periodState, Instant dataAsOf) {
        return new VerifiedProjectionMetadata(
                "legal-a",
                YearMonth.of(2026, 7),
                periodState,
                "formula-catalog-v1",
                List.of("oa-v1", "calculation-v1", "ledger-v1"),
                "b".repeat(64),
                dataAsOf,
                "principal-a");
    }

    private PublishCommand withMetadata(
            PublishCommand command,
            VerifiedProjectionMetadata metadata) {
        return new PublishCommand(
                metadata,
                command.calculatedFacts(),
                command.currentExceptionFacts(),
                command.oaDocumentFacts(),
                command.timeAccountFacts());
    }

    private DailyFact daily(long lateMinutes) {
        return daily(lateMinutes, lateMinutes);
    }

    private DailyFact daily(
            long lateMinutes, long penalizedLateMinutes) {
        return new DailyFact(
                "calculated-daily-a",
                "legal-a",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "organization-version-a",
                "制造中心",
                LocalDate.of(2026, 7, 15),
                DayType.WEEKDAY,
                "总部夏令班",
                480,
                460,
                0,
                0,
                20,
                460,
                1,  // scheduledAttendanceDays
                1,  // actualAttendanceDays
                lateMinutes,
                penalizedLateMinutes,
                10,
                0,
                Instant.parse("2026-07-15T00:40:00Z"),
                Instant.parse("2026-07-15T09:20:00Z"),
                "calculation-v1",
                "a".repeat(64));
    }

    private ExceptionFact safeException() {
        return new ExceptionFact(
                "case-a",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                LocalDate.of(2026, 7, 15),
                "LATE",
                ExceptionSeverity.WARNING,
                ExceptionState.OPEN,
                10,
                "原因码=LATE_CHARGEABLE；证据数量=2",
                "calculation-v1");
    }

    private static final class RecordingWriter
            implements AttendanceReportProjectionWriter {

        private final List<String> calls = new ArrayList<>();
        private final List<ProjectionDraft> drafts = new ArrayList<>();
        private final List<DailyFactWrite> daily = new ArrayList<>();
        private final List<ExceptionFactWrite> exceptions = new ArrayList<>();
        private final List<OaDocumentFactWrite> oa = new ArrayList<>();
        private final List<TimeAccountFactWrite> accounts = new ArrayList<>();
        private final List<String> publishedIds = new ArrayList<>();
        private final List<StoredProjection> stored = new ArrayList<>();
        private boolean failOa;

        @Override
        public boolean lockCompany(String companyId) {
            calls.add("LOCK");
            return "legal-a".equals(companyId);
        }

        @Override
        public Optional<StoredProjection> findByDigest(
                String companyId,
                LocalDate periodStart,
                String projectionDigest) {
            calls.add("FIND");
            return stored.stream()
                    .filter(value -> value.companyId().equals(companyId)
                            && value.periodStart().equals(periodStart)
                            && value.projectionDigest().equals(
                                    projectionDigest))
                    .findFirst();
        }

        @Override
        public Optional<StoredProjection> findLatestPublished(
                String companyId, LocalDate periodStart) {
            calls.add("LATEST");
            return stored.stream()
                    .filter(value -> value.companyId().equals(companyId)
                            && value.periodStart().equals(periodStart)
                            && "PUBLISHED".equals(value.status()))
                    .max(java.util.Comparator
                            .comparing(StoredProjection::publishedAt)
                            .thenComparing(
                                    StoredProjection::projectionId));
        }

        @Override
        public void createDraft(ProjectionDraft draft) {
            calls.add("DRAFT");
            drafts.add(draft);
        }

        @Override
        public void appendDailyFact(DailyFactWrite fact) {
            calls.add("DAILY");
            daily.add(fact);
        }

        @Override
        public void appendExceptionFact(ExceptionFactWrite fact) {
            calls.add("EXCEPTION");
            exceptions.add(fact);
        }

        @Override
        public void appendOaDocumentFact(OaDocumentFactWrite fact) {
            calls.add("OA");
            if (failOa) {
                throw new IllegalStateException(
                        "simulated OA reference rejection");
            }
            oa.add(fact);
        }

        @Override
        public void appendTimeAccountFact(TimeAccountFactWrite fact) {
            calls.add("ACCOUNT");
            accounts.add(fact);
        }

        @Override
        public void markPublished(
                String projectionId, Instant publishedAt) {
            calls.add("PUBLISH");
            publishedIds.add(projectionId);
            ProjectionDraft draft = drafts.stream()
                    .filter(value -> value.projectionId().equals(projectionId))
                    .findFirst()
                    .orElseThrow();
            stored.add(new StoredProjection(
                    draft.projectionId(),
                    draft.companyId(),
                    draft.periodStart(),
                    draft.periodEndExclusive(),
                    draft.periodState(),
                    draft.projectionVersion(),
                    draft.formulaCatalogVersion(),
                    draft.sourceVersions(),
                    draft.sourceSnapshotDigest(),
                    draft.projectionDigest(),
                    "PUBLISHED",
                    draft.dataAsOf(),
                    publishedAt));
        }

        private int writeCount() {
            return drafts.size()
                    + daily.size()
                    + exceptions.size()
                    + oa.size()
                    + accounts.size()
                    + publishedIds.size();
        }
    }
}
