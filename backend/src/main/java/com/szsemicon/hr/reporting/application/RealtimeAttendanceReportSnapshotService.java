package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedTimeAccountFact;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountFact;
import com.szsemicon.hr.shared.web.ApiProblemException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Builds the report read model directly from the deterministic calculation
 * engine. No report projection is read or written on this path.
 */
@Service
public class RealtimeAttendanceReportSnapshotService {

    private static final long CACHE_BUCKET_SECONDS = 30;
    private static final Duration SNAPSHOT_RETENTION = Duration.ofMinutes(5);
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final String SNAPSHOT_PREFIX = "LIVE-";

    private final AttendanceReportCalculationOrchestrator orchestrator;
    private final AttendanceReportSourceRepository repository;
    private final MeterRegistry meterRegistry;
    private final Counter cacheHitMetric;
    private final Counter cacheMissMetric;
    private final Counter cacheEvictionMetric;
    private final Counter safeFailureMetric;
    private final Timer calculationTimer;
    private final DistributionSummary factRowCount;
    private final ConcurrentMap<CalculationKey, CacheEntry> cache =
            new ConcurrentHashMap<>();
    private final Object cacheMaintenanceLock = new Object();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();

    public RealtimeAttendanceReportSnapshotService(
            AttendanceReportCalculationOrchestrator orchestrator,
            AttendanceReportSourceRepository repository,
            MeterRegistry meterRegistry) {
        this.orchestrator = Objects.requireNonNull(
                orchestrator, "orchestrator");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry, "meterRegistry");
        this.cacheHitMetric = cacheRequestMetric(meterRegistry, "hit");
        this.cacheMissMetric = cacheRequestMetric(meterRegistry, "miss");
        this.cacheEvictionMetric = Counter.builder(
                        "attendance.report.realtime.cache.evictions")
                .tag("path", "realtime")
                .register(meterRegistry);
        this.safeFailureMetric = Counter.builder(
                        "attendance.report.realtime.safe.failures")
                .tag("path", "realtime")
                .register(meterRegistry);
        this.calculationTimer = Timer.builder(
                        "attendance.report.realtime.calculation.duration")
                .tag("path", "realtime")
                .register(meterRegistry);
        this.factRowCount = DistributionSummary.builder(
                        "attendance.report.realtime.fact.rows")
                .tag("path", "realtime")
                .register(meterRegistry);
    }

    public Optional<ReportSourceSnapshot> loadAuthorizedSnapshot(
            String principalId,
            String capabilityCode,
            ReportFilter requestedFilter,
            String expectedSnapshotVersion,
            Instant authorizationTime) {
        Objects.requireNonNull(requestedFilter, "requestedFilter");
        Objects.requireNonNull(authorizationTime, "authorizationTime");

        String companyId = resolveCompanyId(
                principalId,
                capabilityCode,
                requestedFilter,
                authorizationTime);
        if (companyId == null) {
            return Optional.empty();
        }
        Optional<RealtimeAuthorization> resolvedAuthorization =
                repository.resolveRealtimeAuthorization(
                        principalId,
                        capabilityCode,
                        companyId,
                        authorizationTime);
        if (resolvedAuthorization.isEmpty()) {
            return Optional.empty();
        }
        RealtimeAuthorization authorization =
                resolvedAuthorization.orElseThrow();
        if (!companyId.equals(authorization.companyId())) {
            return Optional.empty();
        }

        Optional<CacheEntry> retained = findRetained(
                companyId,
                requestedFilter.period(),
                expectedSnapshotVersion,
                authorizationTime);
        CacheEntry entry;
        if (retained.isPresent()) {
            recordCacheHit();
            entry = retained.orElseThrow();
        } else {
            entry = currentEntry(
                    companyId,
                    requestedFilter.period(),
                    principalId,
                    authorizationTime);
        }
        ReportFilter resolvedFilter = new ReportFilter(
                requestedFilter.period(),
                companyId,
                requestedFilter.organizationId(),
                requestedFilter.employeeId(),
                requestedFilter.status());
        try {
            return Optional.of(toSnapshot(
                    entry,
                    authorization,
                    resolvedFilter));
        } catch (ApiProblemException exception) {
            safeFailureMetric.increment();
            throw exception;
        } catch (RuntimeException exception) {
            safeFailureMetric.increment();
            throw calculationUnavailable(exception);
        }
    }

    private String resolveCompanyId(
            String principalId,
            String capabilityCode,
            ReportFilter filter,
            Instant authorizationTime) {
        List<AttendanceReportSourceRepository.CompanyOption> companies =
                repository.listAuthorizedCompanies(
                        principalId,
                        capabilityCode,
                        filter.period(),
                        authorizationTime);
        if (filter.companyId() != null) {
            return companies.stream()
                    .map(AttendanceReportSourceRepository.CompanyOption::companyId)
                    .filter(filter.companyId()::equals)
                    .findFirst()
                    .orElse(null);
        }
        return companies.size() == 1
                ? companies.getFirst().companyId()
                : null;
    }

    private CacheEntry currentEntry(
            String companyId,
            YearMonth period,
            String principalId,
            Instant requestedAt) {
        long cutoffEpoch = Math.floorDiv(
                        requestedAt.getEpochSecond(), CACHE_BUCKET_SECONDS)
                * CACHE_BUCKET_SECONDS;
        CalculationKey key = new CalculationKey(
                companyId, period, cutoffEpoch);
        CacheEntry result;
        AtomicBoolean created = new AtomicBoolean();
        try {
            result = cache.computeIfAbsent(
                    key,
                    ignored -> {
                        created.set(true);
                        recordCacheMiss();
                        return calculate(
                                companyId,
                                period,
                                principalId,
                                requestedAt);
                    });
        } catch (ApiProblemException exception) {
            safeFailureMetric.increment();
            throw exception;
        } catch (RuntimeException exception) {
            safeFailureMetric.increment();
            throw calculationUnavailable(exception);
        }
        if (!created.get()) {
            recordCacheHit();
        }
        prune(requestedAt);
        return result;
    }

    private CacheEntry calculate(
            String companyId,
            YearMonth period,
            String principalId,
            Instant dataAsOf) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PublishCommand command = orchestrator.assemble(
                    companyId,
                    period,
                    PeriodState.OPEN,
                    principalId,
                    dataAsOf);
            if (!companyId.equals(command.metadata().companyId())
                    || !period.equals(command.metadata().period())
                    || !dataAsOf.equals(command.metadata().dataAsOf())) {
                throw new IllegalStateException(
                        "calculation result does not match its requested scope");
            }
            factRowCount.record(factRowCount(command));
            String snapshotVersion = SNAPSHOT_PREFIX
                    + command.metadata().sourceSnapshotDigest();
            return new CacheEntry(command, snapshotVersion, dataAsOf);
        } finally {
            sample.stop(calculationTimer);
        }
    }

    private Optional<CacheEntry> findRetained(
            String companyId,
            YearMonth period,
            String expectedSnapshotVersion,
            Instant now) {
        if (expectedSnapshotVersion == null
                || !expectedSnapshotVersion.startsWith(SNAPSHOT_PREFIX)) {
            return Optional.empty();
        }
        Instant oldest = now.minus(SNAPSHOT_RETENTION);
        return cache.entrySet().stream()
                .filter(entry -> entry.getKey().companyId().equals(companyId))
                .filter(entry -> entry.getKey().period().equals(period))
                .map(Map.Entry::getValue)
                .filter(entry -> entry.createdAt().compareTo(oldest) >= 0)
                .filter(entry -> entry.snapshotVersion()
                        .equals(expectedSnapshotVersion))
                .findFirst();
    }

    private void prune(Instant now) {
        synchronized (cacheMaintenanceLock) {
            Instant oldest = now.minus(SNAPSHOT_RETENTION);
            cache.entrySet().stream()
                    .filter(entry -> entry.getValue()
                            .createdAt().isBefore(oldest))
                    .toList()
                    .forEach(entry -> evict(
                            entry.getKey(), entry.getValue()));
            while (cache.size() > MAX_CACHE_ENTRIES) {
                cache.entrySet().stream()
                        .min(Comparator.comparing(
                                entry -> entry.getValue().createdAt()))
                        .ifPresent(entry -> evict(
                                entry.getKey(), entry.getValue()));
            }
        }
    }

    private void evict(CalculationKey key, CacheEntry entry) {
        if (cache.remove(key, entry)) {
            cacheEvictions.increment();
            cacheEvictionMetric.increment();
        }
    }

    private void recordCacheHit() {
        cacheHits.increment();
        cacheHitMetric.increment();
    }

    private void recordCacheMiss() {
        cacheMisses.increment();
        cacheMissMetric.increment();
    }

    private static Counter cacheRequestMetric(
            MeterRegistry meterRegistry, String result) {
        return Counter.builder("attendance.report.realtime.cache.requests")
                .tag("path", "realtime")
                .tag("result", result)
                .register(meterRegistry);
    }

    private static long factRowCount(PublishCommand command) {
        long calculatedExceptions = command.calculatedFacts().stream()
                .mapToLong(value -> value.facts().exceptionFacts().size())
                .sum();
        return command.calculatedFacts().size()
                + calculatedExceptions
                + command.currentExceptionFacts().size()
                + command.oaDocumentFacts().size()
                + command.timeAccountFacts().size();
    }

    CacheMetrics cacheMetrics() {
        return new CacheMetrics(
                cache.size(),
                MAX_CACHE_ENTRIES,
                cacheHits.sum(),
                cacheMisses.sum(),
                cacheEvictions.sum());
    }

    private ReportSourceSnapshot toSnapshot(
            CacheEntry entry,
            RealtimeAuthorization authorization,
            ReportFilter filter) {
        PublishCommand command = entry.command();
        List<DailyFact> dailyFacts = command.calculatedFacts().stream()
                .map(fact -> fact.facts().dailyFact())
                .filter(fact -> visible(
                        authorization,
                        filter,
                        fact.companyId(),
                        fact.employeeId(),
                        fact.organizationId()))
                .toList();
        Map<EmployeeOrganization, DailyFact> identityByEmployeeOrganization =
                new HashMap<>();
        Map<String, DailyFact> identityByEmployee = new HashMap<>();
        for (DailyFact fact : dailyFacts) {
            identityByEmployeeOrganization.merge(
                    new EmployeeOrganization(
                            fact.employeeId(), fact.organizationId()),
                    fact,
                    RealtimeAttendanceReportSnapshotService::latest);
            identityByEmployee.merge(
                    fact.employeeId(),
                    fact,
                    RealtimeAttendanceReportSnapshotService::latest);
        }
        List<ExceptionFact> exceptions = new ArrayList<>();
        command.calculatedFacts().forEach(fact -> {
            DailyFact daily = fact.facts().dailyFact();
            if (!authorization.companyId().equals(daily.companyId())) {
                return;
            }
            fact.facts().exceptionFacts().stream()
                    .filter(value -> visible(
                            authorization,
                            filter,
                            daily.companyId(),
                            value.employeeId(),
                            value.organizationId()))
                    .forEach(exceptions::add);
        });
        command.currentExceptionFacts().stream()
                .filter(value -> visible(
                        authorization,
                        filter,
                        value.companyId(),
                        value.fact().employeeId(),
                        value.fact().organizationId()))
                .map(value -> value.fact())
                .forEach(exceptions::add);

        List<OaDocumentFact> oaFacts = command.oaDocumentFacts().stream()
                .filter(value -> visible(
                        authorization,
                        filter,
                        value.companyId(),
                        value.employeeId(),
                        value.organizationId()))
                .map(value -> toOaDocumentFact(
                        value,
                        identity(
                                identityByEmployeeOrganization,
                                identityByEmployee,
                                value.employeeId(),
                                value.organizationId())))
                .toList();
        List<TimeAccountFact> timeAccounts = command.timeAccountFacts().stream()
                .filter(value -> visible(
                        authorization,
                        filter,
                        value.companyId(),
                        value.employeeId(),
                        value.organizationId()))
                .map(value -> toTimeAccountFact(
                        value,
                        identity(
                                identityByEmployeeOrganization,
                                identityByEmployee,
                                value.employeeId(),
                                value.organizationId())))
                .toList();

        return new ReportSourceSnapshot(
                authorization.scope(),
                filter,
                entry.snapshotVersion(),
                PeriodState.OPEN.name(),
                command.metadata().dataAsOf(),
                command.metadata().sourceVersions(),
                dailyFacts,
                oaFacts,
                List.copyOf(exceptions),
                timeAccounts);
    }

    private static boolean visible(
            RealtimeAuthorization authorization,
            ReportFilter filter,
            String companyId,
            String employeeId,
            String organizationId) {
        boolean scopeAllows = authorization.companyId().equals(companyId)
                && (authorization.companyWide()
                || Objects.equals(
                        authorization.principalEmployeeId(), employeeId)
                || (authorization.employeeIds().contains(employeeId)
                        && authorization.organizationIds()
                                .contains(organizationId)));
        return scopeAllows
                && (filter.employeeId() == null
                        || filter.employeeId().equals(employeeId))
                && (filter.organizationId() == null
                        || filter.organizationId().equals(organizationId));
    }

    private static DailyFact identity(
            Map<EmployeeOrganization, DailyFact> byEmployeeOrganization,
            Map<String, DailyFact> byEmployee,
            String employeeId,
            String organizationId) {
        DailyFact result = byEmployeeOrganization.get(
                new EmployeeOrganization(employeeId, organizationId));
        if (result == null) {
            result = byEmployee.get(employeeId);
        }
        if (result == null) {
            throw new IllegalStateException(
                    "calculated report fact has no employee identity");
        }
        return result;
    }

    private static DailyFact latest(DailyFact left, DailyFact right) {
        return left.businessDate().compareTo(right.businessDate()) >= 0
                ? left
                : right;
    }

    private static OaDocumentFact toOaDocumentFact(
            VerifiedOaDocumentFact value, DailyFact identity) {
        Instant start = value.temporalShape() == OaTemporalShape.POINT
                ? value.pointInstant()
                : value.intervalStart();
        Instant end = value.temporalShape() == OaTemporalShape.POINT
                ? value.pointInstant().plusNanos(1)
                : value.intervalEndExclusive();
        return new OaDocumentFact(
                value.oaAttendanceDocumentId(),
                value.employeeId(),
                identity.employeeNumber(),
                identity.employeeName(),
                value.organizationId(),
                identity.organizationName(),
                value.documentType(),
                value.leaveTypeCode(),
                start,
                end,
                value.recognizedMinutes(),
                value.sourceStatus(),
                value.sourceVersion());
    }

    private static TimeAccountFact toTimeAccountFact(
            VerifiedTimeAccountFact value, DailyFact identity) {
        return new TimeAccountFact(
                value.accountId(),
                value.employeeId(),
                identity.employeeNumber(),
                identity.employeeName(),
                value.organizationId(),
                identity.organizationName(),
                value.accountType(),
                value.openingHours(),
                value.grantedHours(),
                value.overtimeCreditHours(),
                value.manualIncreaseHours(),
                value.usedHours(),
                value.expiredHours(),
                value.returnedHours(),
                value.manualDeductionHours(),
                value.ledgerVersion());
    }

    private static ApiProblemException calculationUnavailable(
            RuntimeException exception) {
        String message = exception.getMessage();
        String code = message != null
                        && message.contains(
                                "OA_LEAVE_REVOCATION_UNRESOLVED")
                ? "OA_LEAVE_REVOCATION_UNRESOLVED"
                : "ATTENDANCE_REPORT_REALTIME_CALCULATION_UNAVAILABLE";
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                code,
                "当前考勤输入尚不能安全计算，请检查来源同步、人员匹配、班次和单据",
                true);
    }

    private record CalculationKey(
            String companyId, YearMonth period, long cutoffEpochSecond) {
    }

    private record CacheEntry(
            PublishCommand command,
            String snapshotVersion,
            Instant createdAt) {
    }

    private record EmployeeOrganization(
            String employeeId, String organizationId) {
    }

    record CacheMetrics(
            int entries,
            int maximumEntries,
            long hits,
            long misses,
            long evictions) {
    }
}
