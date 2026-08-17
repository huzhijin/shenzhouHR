package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.application.AttendanceReportCalculationOrchestrator;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedProjectionMetadata;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.PunchEventRow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles an employee-level report projection for a company-month.
 *
 * <p>For every employee active in the period a daily fact is produced for each
 * business date the employee's identity versions cover. Days with activated
 * punch points carry the punch span as worked time; days without punches are
 * published as zero-work rows so downstream readers can distinguish "no
 * attendance recorded" from "employee not in scope".
 *
 * <h2>What this implementation does not claim</h2>
 * <p>No shift snapshot is resolved here, so {@code scheduledMinutes},
 * {@code leaveOrTimeOffMinutes}, {@code absenceMinutes},
 * {@code recognizedOvertimeMinutes}, {@code lateMinutes} and
 * {@code earlyDepartureMinutes} stay zero rather than being inferred from a
 * default workday: attributing lateness or absence requires the scheduled
 * segments that only {@code DeterministicAttendanceCalculator} can bind. For
 * the same reason worked time is reported as the confirmed in-schedule
 * component and never split into overtime. Exception facts are likewise left
 * empty; they are owned by the exception reconciler.
 *
 * <p>When assignments overlap, the assignment with the most recent effective
 * date owns the employee-day. A tie on that latest date (including ambiguous
 * employee or organization versions) is skipped rather than guessed. A day
 * with no valid organization version is skipped too, because the projection
 * writer requires one.
 *
 * <p>This legacy punch-span implementation is intentionally not a Spring
 * bean. Report publication requires exactly one calculation orchestrator and
 * is served by {@link FullCalculationEngineOrchestrator}; retaining this class
 * only keeps its deterministic compatibility tests and migration reference.
 */
public class CalculationEngineOrchestrator
        implements AttendanceReportCalculationOrchestrator {

    /** Zone the business date and punch spans are interpreted in. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * Algorithm tag for the projection header. Bump this whenever the derived
     * numbers below change meaning so published projections stay comparable.
     */
    private static final String FORMULA_CATALOG_VERSION = "PUNCH_SPAN_V1";

    /**
     * Per-row algorithm tag stored on every daily fact. Must satisfy the
     * 128-character version constraint of {@code VerifiedProjectionMetadata}.
     */
    private static final String CALCULATION_VERSION_ID =
            "ATTENDANCE.PUNCH_SPAN:V1";

    /** Source tokens recorded on the projection header. */
    private static final List<String> SOURCE_VERSIONS = List.of(
            "PEOPLE.EMPLOYEE_VERSION:V1",
            "PEOPLE.EMPLOYMENT_ASSIGNMENT:V1",
            "ORGANIZATION.ORGANIZATION_VERSION:V1",
            "ATTENDANCE.EFFECTIVE_EVENT:V1",
            "ATTENDANCE.WORK_CALENDAR_DAY:V1");

    /**
     * Shift label used when no shift snapshot is bound to the day. It states
     * the absence of a resolved shift instead of naming one.
     */
    private static final String UNRESOLVED_SHIFT_LABEL = "未匹配班次";

    private static final String ROW_DIGEST_FORMAT =
            "ATTENDANCE_REPORT_PUNCH_SPAN_DAILY_V1";
    private static final String SNAPSHOT_DIGEST_FORMAT =
            "ATTENDANCE_REPORT_PUNCH_SPAN_SNAPSHOT_V1";

    private final AttendanceReportCalculationMapper mapper;

    public CalculationEngineOrchestrator(
            AttendanceReportCalculationMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(periodState, "periodState");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(dataAsOf, "dataAsOf");

        LocalDate periodStart = period.atDay(1);
        LocalDate periodEndExclusive = period.plusMonths(1).atDay(1);

        List<EmployeeIdentityIntervalRow> identities = requireRows(
                mapper.findEmployeeIdentityIntervals(
                        companyId, periodStart, periodEndExclusive),
                "employee identity intervals");
        Map<String, Map<LocalDate, PunchSpan>> punches = punchSpansByEmployee(
                requireRows(
                        mapper.findActivatedPunchEvents(
                                companyId,
                                startOfDay(periodStart),
                                startOfDay(periodEndExclusive),
                                dataAsOf),
                        "activated punch events"));
        Map<LocalDate, DayType> dayTypes = dayTypes(
                requireRows(
                        mapper.findPublishedCalendarDays(
                                companyId,
                                periodStart,
                                periodEndExclusive,
                                dataAsOf),
                        "published calendar days"));

        Map<String, List<EmployeeIdentityIntervalRow>> byEmployee =
                new HashMap<>();
        for (EmployeeIdentityIntervalRow row : identities) {
            byEmployee.computeIfAbsent(
                            row.employeeId(), key -> new ArrayList<>())
                    .add(row);
        }

        List<VerifiedCalculatedFacts> calculatedFacts = new ArrayList<>();
        for (Map.Entry<String, List<EmployeeIdentityIntervalRow>> entry
                : byEmployee.entrySet()) {
            Map<LocalDate, PunchSpan> employeePunches = punches.getOrDefault(
                    entry.getKey(), Map.of());
            for (LocalDate businessDate = periodStart;
                    businessDate.isBefore(periodEndExclusive);
                    businessDate = businessDate.plusDays(1)) {
                EmployeeIdentityIntervalRow identity = unambiguousIdentity(
                        entry.getValue(), businessDate);
                if (identity == null) {
                    continue;
                }
                calculatedFacts.add(calculatedFacts(
                        companyId,
                        identity,
                        businessDate,
                        dayTypes.getOrDefault(
                                businessDate, weekdayDayType(businessDate)),
                        employeePunches.get(businessDate)));
            }
        }
        calculatedFacts.sort((left, right) -> left.facts().dailyFact().factId()
                .compareTo(right.facts().dailyFact().factId()));

        VerifiedProjectionMetadata metadata = new VerifiedProjectionMetadata(
                companyId,
                period,
                periodState,
                FORMULA_CATALOG_VERSION,
                SOURCE_VERSIONS,
                sourceSnapshotDigest(companyId, period, calculatedFacts),
                dataAsOf,
                principalId);
        return new PublishCommand(
                metadata,
                calculatedFacts,
                // Exception cases are reconciled by the exception pipeline and
                // OA / ledger facts by their own adapters; publishing empty
                // lists here keeps this orchestrator from inventing them.
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * Builds one verified daily fact. The identity versions travel next to the
     * fact so the writer can re-verify them against the same business date.
     */
    private VerifiedCalculatedFacts calculatedFacts(
            String companyId,
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            DayType dayType,
            PunchSpan span) {
        long workedMinutes = span == null ? 0L : span.workedMinutes();
        int missingPunchCount = span == null ? 0 : span.missingPunchCount();
        Instant firstPunchAt = span == null ? null : span.firstPunchAt();
        Instant lastPunchAt = span == null ? null : span.lastPunchAt();
        String factId = identity.employeeId() + ":" + businessDate;

        // Calculate attendance days (simplified for legacy orchestrator)
        int scheduledAttendanceDays = 0; // Unknown without shift snapshot
        int actualAttendanceDays = workedMinutes > 0 ? 1 : 0;

        DailyFact dailyFact = new DailyFact(
                factId,
                companyId,
                identity.employeeId(),
                identity.employeeNumber(),
                identity.employeeName(),
                identity.organizationId(),
                identity.organizationVersionId(),
                identity.organizationName(),
                businessDate,
                dayType,
                UNRESOLVED_SHIFT_LABEL,
                // scheduledMinutes: unknown without a shift snapshot.
                0L,
                // Worked time is reported as confirmed in-schedule work; it is
                // not split into overtime without scheduled segments.
                workedMinutes,
                0L,
                0L,
                0L,
                workedMinutes,
                scheduledAttendanceDays,
                actualAttendanceDays,
                0L,
                0L,
                0L,
                missingPunchCount,
                firstPunchAt,
                lastPunchAt,
                CALCULATION_VERSION_ID,
                rowDigest(
                        companyId,
                        identity,
                        businessDate,
                        dayType,
                        workedMinutes,
                        missingPunchCount,
                        firstPunchAt,
                        lastPunchAt));
        return new VerifiedCalculatedFacts(
                identity.employeeVersionId(),
                identity.employmentAssignmentId(),
                new ProjectionFacts(dailyFact, List.of()));
    }

    /**
     * Returns the occurrence-time identity selected by assignment effective
     * date, or {@code null} when no identity exists or the latest start ties.
     */
    private static EmployeeIdentityIntervalRow unambiguousIdentity(
            List<EmployeeIdentityIntervalRow> candidates,
            LocalDate businessDate) {
        return AttendanceReportCalculationRows.latestEffectiveAssignment(
                candidates, businessDate);
    }

    /**
     * Groups punch instants into per-employee, per-business-date spans. The
     * business date is derived in {@link #BUSINESS_ZONE}, so a punch is
     * attributed to the local calendar day it happened on.
     */
    private static Map<String, Map<LocalDate, PunchSpan>> punchSpansByEmployee(
            List<PunchEventRow> events) {
        Map<String, Map<LocalDate, PunchSpan>> spans = new HashMap<>();
        for (PunchEventRow event : events) {
            if (event.employeeId() == null || event.pointInstant() == null) {
                continue;
            }
            Instant punchAt = event.pointInstant()
                    .truncatedTo(ChronoUnit.MICROS);
            LocalDate businessDate = LocalDate.ofInstant(
                    punchAt, BUSINESS_ZONE);
            spans.computeIfAbsent(
                            event.employeeId(), key -> new HashMap<>())
                    .merge(
                            businessDate,
                            new PunchSpan(punchAt, punchAt, 1),
                            PunchSpan::merge);
        }
        return spans;
    }

    /**
     * Maps published calendar day types onto report day types. A date the
     * company's calendars disagree on falls back to weekday classification
     * rather than picking one calendar arbitrarily.
     */
    private static Map<LocalDate, DayType> dayTypes(List<CalendarDayRow> rows) {
        Map<LocalDate, EnumSet<DayType>> observed = new HashMap<>();
        for (CalendarDayRow row : rows) {
            if (row.businessDate() == null) {
                continue;
            }
            DayType dayType = dayType(row.dayType(), row.businessDate());
            observed.computeIfAbsent(
                            row.businessDate(),
                            key -> EnumSet.noneOf(DayType.class))
                    .add(dayType);
        }
        Map<LocalDate, DayType> resolved = new HashMap<>();
        observed.forEach((businessDate, dayTypes) -> {
            if (dayTypes.size() == 1) {
                resolved.put(businessDate, dayTypes.iterator().next());
            }
        });
        return resolved;
    }

    private static DayType dayType(String calendarDayType, LocalDate date) {
        if (calendarDayType == null) {
            return weekdayDayType(date);
        }
        return switch (calendarDayType) {
            case "WORKDAY" -> DayType.WEEKDAY;
            case "SPECIAL_WORKDAY" -> DayType.ADJUSTED_WORKDAY;
            case "PUBLIC_HOLIDAY" -> DayType.PUBLIC_HOLIDAY;
            // The report distinguishes Saturday from Sunday while the calendar
            // only marks a day as WEEKEND, so the weekday decides which one.
            case "WEEKEND" -> weekendDayType(date);
            default -> weekdayDayType(date);
        };
    }

    private static DayType weekdayDayType(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> DayType.SATURDAY;
            case SUNDAY -> DayType.SUNDAY;
            default -> DayType.WEEKDAY;
        };
    }

    private static DayType weekendDayType(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SUNDAY
                ? DayType.SUNDAY
                : DayType.SATURDAY;
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(BUSINESS_ZONE).toInstant();
    }

    private static <T> List<T> requireRows(List<T> rows, String description) {
        if (rows == null) {
            throw new IllegalStateException(
                    "report calculation mapper returned null "
                            + description);
        }
        return rows;
    }

    /**
     * Digest over the derived content of one daily row. Stable for identical
     * inputs so republishing unchanged data produces an identical projection.
     */
    private static String rowDigest(
            String companyId,
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            DayType dayType,
            long workedMinutes,
            int missingPunchCount,
            Instant firstPunchAt,
            Instant lastPunchAt) {
        return new CanonicalDigest(ROW_DIGEST_FORMAT)
                .add(CALCULATION_VERSION_ID)
                .add(companyId)
                .add(identity.employeeId())
                .add(identity.employeeVersionId())
                .add(identity.employmentAssignmentId())
                .add(identity.organizationId())
                .add(identity.organizationVersionId())
                .add(businessDate.toString())
                .add(dayType.name())
                .add(UNRESOLVED_SHIFT_LABEL)
                .add(Long.toString(workedMinutes))
                .add(Integer.toString(missingPunchCount))
                .add(firstPunchAt == null ? "" : firstPunchAt.toString())
                .add(lastPunchAt == null ? "" : lastPunchAt.toString())
                .finish();
    }

    /**
     * Digest over the assembled source content. It deliberately excludes
     * {@code dataAsOf} so an unchanged snapshot keeps its identity across
     * publications and only genuine data changes produce a new digest.
     */
    private static String sourceSnapshotDigest(
            String companyId,
            YearMonth period,
            List<VerifiedCalculatedFacts> calculatedFacts) {
        CanonicalDigest digest = new CanonicalDigest(SNAPSHOT_DIGEST_FORMAT)
                .add(FORMULA_CATALOG_VERSION)
                .add(companyId)
                .add(period.toString())
                .add(Integer.toString(calculatedFacts.size()));
        SOURCE_VERSIONS.forEach(digest::add);
        calculatedFacts.forEach(value -> digest
                .add(value.employeeVersionId())
                .add(value.employmentAssignmentId())
                .add(value.facts().dailyFact().factId())
                .add(value.facts().dailyFact().resultDigest()));
        return digest.finish();
    }

    /** Accumulated punch extremes for one employee-day. */
    private record PunchSpan(
            Instant firstPunchAt, Instant lastPunchAt, int punchCount) {

        private PunchSpan merge(PunchSpan other) {
            return new PunchSpan(
                    firstPunchAt.isBefore(other.firstPunchAt)
                            ? firstPunchAt
                            : other.firstPunchAt,
                    lastPunchAt.isAfter(other.lastPunchAt)
                            ? lastPunchAt
                            : other.lastPunchAt,
                    punchCount + other.punchCount);
        }

        /**
         * Worked minutes as the span between the first and last punch of the
         * day, floored to whole minutes. A single punch cannot bound a span,
         * so it yields zero worked minutes.
         */
        private long workedMinutes() {
            if (punchCount < 2) {
                return 0L;
            }
            return Math.max(
                    0L,
                    Duration.between(firstPunchAt, lastPunchAt).toMinutes());
        }

        /**
         * An odd punch count leaves one punch unpaired, which is the only
         * missing punch this implementation can assert without a shift.
         */
        private int missingPunchCount() {
            return punchCount % 2 == 0 ? 0 : 1;
        }
    }

    /** Length-prefixed SHA-256 accumulator, unambiguous across field joins. */
    private static final class CanonicalDigest {

        private final MessageDigest digest;

        private CanonicalDigest(String format) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(
                        "SHA-256 digest algorithm unavailable", exception);
            }
            add(format);
        }

        private CanonicalDigest add(String value) {
            byte[] bytes = Objects.requireNonNull(value, "canonical value")
                    .getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bytes.length)
                    .array());
            digest.update(bytes);
            return this;
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
