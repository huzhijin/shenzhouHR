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
import com.szsemicon.hr.shared.web.ApiProblemException;
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
    void employeeDayPublishCopiesOtherEmployeesOaAndAccounts() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        publisher.publish(command(
                PeriodState.OPEN, daily(10), safeException()));
        writer.calls.clear();

        var result = publisher.publish(
                command(PeriodState.OPEN, daily(11), safeException()),
                LocalDate.of(2026, 7, 14),
                LocalDate.of(2026, 7, 17),
                "employee-a");

        assertThat(result.created()).isTrue();
        assertThat(writer.calls)
                .contains(
                        "LOCK",
                        "FIND",
                        "LATEST",
                        "DRAFT",
                        "COPY",
                        "COPY_OA",
                        "COPY_ACCOUNT",
                        "DAILY",
                        "EXCEPTION",
                        "OA",
                        "ACCOUNT",
                        "PUBLISH");
        assertThat(writer.copyEmployeeIds).containsExactly("employee-a");
    }

    @Test
    void companyWindowPreservesOutsideOaButRebuildsAccountsOnce() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        publisher.publish(command(PeriodState.OPEN, daily(10), safeException()));
        writer.calls.clear();

        var result = publisher.publish(
                command(PeriodState.OPEN, daily(11), safeException()),
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 17));

        assertThat(result.created()).isTrue();
        assertThat(writer.calls).contains("COPY", "COPY_OA", "ACCOUNT", "PUBLISH")
                .doesNotContain("COPY_ACCOUNT");
        assertThat(writer.copyEmployeeIds).containsExactly((String) null);
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
    void partialPublicationRequiresAnExistingBaselineEvenForTheLegacyOverload() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        var command = command(PeriodState.OPEN, daily(10), safeException());
        LocalDate from = LocalDate.of(2026, 7, 14);
        LocalDate to = LocalDate.of(2026, 7, 17);

        assertThatThrownBy(() -> publisher.publishScoped(command, from, to, List.of(), "missing-base"))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_BASE_PROJECTION_REQUIRED");
        assertThatThrownBy(() -> publisher.publish(command, from, to))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_BASE_PROJECTION_REQUIRED");
        assertThat(writer.writeCount()).isZero();
        assertThat(writer.calls).containsExactly("LOCK", "LATEST", "LOCK", "LATEST");
    }

    @Test
    void baselineIsCheckedAfterLockAndAConcurrentPublicationCannotBeMixedIn() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        var baseline = publisher.publish(command(PeriodState.OPEN, daily(10), safeException()));
        publisher.publish(command(PeriodState.OPEN, daily(12), safeException()));
        int writes = writer.writeCount();
        writer.calls.clear();

        assertThatThrownBy(() -> publisher.publishScoped(
                command(PeriodState.OPEN, daily(11), safeException()),
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 17),
                List.of("employee-a"), baseline.projectionVersion()))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_BASE_PROJECTION_CHANGED");

        assertThat(writer.writeCount()).isEqualTo(writes);
        assertThat(writer.calls).containsExactly("LOCK", "LATEST", "FIND");
    }

    @Test
    void identicalPartialRetryReturnsItsCurrentResultWithoutCopyingAgain() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        var baseline = publisher.publish(command(PeriodState.OPEN, daily(10), safeException()));
        var target = command(PeriodState.OPEN, daily(11), safeException());
        LocalDate from = LocalDate.of(2026, 7, 14);
        LocalDate to = LocalDate.of(2026, 7, 17);
        var first = publisher.publishScoped(target, from, to,
                List.of("employee-b", "employee-a"), baseline.projectionVersion());
        int writes = writer.writeCount();

        var retry = publisher.publishScoped(target, from, to,
                List.of("employee-a", "employee-b", "employee-a"), baseline.projectionVersion());

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.projectionVersion()).isEqualTo(first.projectionVersion());
        assertThat(writer.writeCount()).isEqualTo(writes);
    }

    @Test
    void aHistoricalMatchingPartialResultCannotMasqueradeAsTheCurrentPin() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        var baseline = publisher.publish(command(PeriodState.OPEN, daily(10), safeException()));
        var target = command(PeriodState.OPEN, daily(11), safeException());
        LocalDate from = LocalDate.of(2026, 7, 14);
        LocalDate to = LocalDate.of(2026, 7, 17);
        publisher.publishScoped(target, from, to, List.of(), baseline.projectionVersion());
        publisher.publish(command(PeriodState.OPEN, daily(12), safeException()));
        int writes = writer.writeCount();

        assertThatThrownBy(() -> publisher.publishScoped(
                target, from, to, List.of(), baseline.projectionVersion()))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_BASE_PROJECTION_CHANGED");
        assertThat(writer.writeCount()).isEqualTo(writes);
    }

    @Test
    void partialDigestBindsBaselineWriteWindowAndEmployeeScope() {
        LocalDate from = LocalDate.of(2026, 7, 14);
        LocalDate to = LocalDate.of(2026, 7, 17);
        String original = partialDigest(10, from, to, List.of("employee-a"));

        assertThat(partialDigest(12, from, to, List.of("employee-a"))).isNotEqualTo(original);
        assertThat(partialDigest(10, from.plusDays(1), to, List.of("employee-a"))).isNotEqualTo(original);
        assertThat(partialDigest(10, from, to.plusDays(1), List.of("employee-a"))).isNotEqualTo(original);
        assertThat(partialDigest(10, from, to, List.of())).isNotEqualTo(original);
        assertThat(partialDigest(10, from, to, List.of("employee-a", "employee-b"))).isNotEqualTo(original);
        assertThat(partialDigest(10, from, to, List.of("employee-a", "employee-a"))).isEqualTo(original);
    }

    @Test
    void partialPublicationDoesNotReuseAWholeMonthDigestWithTheSameInputFacts() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        var command = command(PeriodState.OPEN, daily(10), safeException());
        var baseline = publisher.publish(command);
        writer.calls.clear();

        var partial = publisher.publishScoped(command,
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 17),
                List.of(), baseline.projectionVersion());

        assertThat(partial.created()).isTrue();
        assertThat(partial.projectionDigest()).isNotEqualTo(baseline.projectionDigest());
        assertThat(writer.calls).contains("COPY", "COPY_OA", "PUBLISH");
    }

    @Test
    void partialWindowMustBeNonemptyAndContainedInTheProjectionMonth() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        var command = command(PeriodState.OPEN, daily(10), safeException());
        LocalDate from = LocalDate.of(2026, 7, 14);
        for (LocalDate[] bounds : List.of(
                new LocalDate[] {from, from}, new LocalDate[] {from, from.minusDays(1)},
                new LocalDate[] {from.minusMonths(1), from},
                new LocalDate[] {from, LocalDate.of(2026, 8, 2)},
                new LocalDate[] {null, from})) {
            assertThatThrownBy(() -> publisher.publishScoped(
                    command, bounds[0], bounds[1], List.of(), "base"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(writer.calls).isEmpty();
    }

    private String partialDigest(
            int baselineLateMinutes, LocalDate from, LocalDate to, List<String> employeeIds) {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        var baseline = publisher.publish(command(PeriodState.OPEN, daily(baselineLateMinutes), safeException()));
        return publisher.publishScoped(command(PeriodState.OPEN, daily(11), safeException()),
                from, to, employeeIds, baseline.projectionVersion()).projectionDigest();
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
    void publishesExactStartLateWhenPenalizedMinutesAreZero() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        ExceptionFact exactStart = new ExceptionFact(
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
                0,
                "原因码=LATE_AT_SHIFT_START；证据数量=1",
                "calculation-v1");

        var result = publisher.publish(command(
                PeriodState.OPEN, daily(0, 0), exactStart));

        assertThat(result.created()).isTrue();
        assertThat(writer.exceptions).singleElement().satisfies(value -> {
            assertThat(value.fact().exceptionType()).isEqualTo("LATE");
            assertThat(value.fact().minutes()).isZero();
        });
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
    void changingOnlyDailyOaSharesProducesANewPinAndWritesTheCanonicalShares() {
        RecordingWriter writer = new RecordingWriter();
        var publisher = publisher(writer);
        PublishCommand old = command(PeriodState.OPEN, daily(10), safeException());
        var first = publisher.publish(old);
        var dated = old.oaDocumentFacts().getFirst().withRecognizedMinutesByDate(
                java.util.Map.of(LocalDate.of(2026, 7, 20), 60L));
        var updated = new PublishCommand(old.metadata(), old.calculatedFacts(),
                old.currentExceptionFacts(), List.of(dated), old.timeAccountFacts());
        var second = publisher.publish(updated);
        assertThat(second.projectionVersion()).isNotEqualTo(first.projectionVersion());
        assertThat(writer.oa.getLast().recognizedMinutesByDateJson())
                .isEqualTo("{\"2026-07-20\":60}");
        assertThat(publisher.publish(updated).projectionVersion()).isEqualTo(second.projectionVersion());
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
        private final List<String> copyEmployeeIds = new ArrayList<>();
        private boolean failOa;

        @Override
        public boolean lockCompany(String companyId, LocalDate periodStart) {
            calls.add("LOCK");
            return "legal-a".equals(companyId) && periodStart != null;
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
        public void copyFactsOutsideRange(
                String sourceProjectionId,
                String targetProjectionId,
                LocalDate windowStart,
                LocalDate windowEndExclusive,
                Instant createdAt,
                String employeeId) {
            calls.add("COPY");
            copyEmployeeIds.add(employeeId);
        }

        @Override
        public void copyOaDocumentFactsExceptEmployeeWindow(
                String sourceProjectionId,
                String targetProjectionId,
                Instant windowStart,
                Instant windowEndExclusive,
                Instant createdAt,
                String employeeId) {
            calls.add("COPY_OA");
        }

        @Override
        public void copyTimeAccountFactsExceptEmployee(
                String sourceProjectionId,
                String targetProjectionId,
                Instant createdAt,
                String employeeId) {
            calls.add("COPY_ACCOUNT");
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
