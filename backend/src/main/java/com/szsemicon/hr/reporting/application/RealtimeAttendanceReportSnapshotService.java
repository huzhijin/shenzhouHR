package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublicationResult;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedTimeAccountFact;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.SourceCutoff;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountFact;
import com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceDashboardWorkbenchMapper;
import com.szsemicon.hr.shared.web.ApiProblemException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Official report reads use a pinned company-month projection. The first
 * complete calculation persists that pin; later GETs reread it until an
 * authorized recalculate replaces it.
 */
@Service
public class RealtimeAttendanceReportSnapshotService {

    private static final Logger log =
            LoggerFactory.getLogger(RealtimeAttendanceReportSnapshotService.class);
    private static final Duration SNAPSHOT_RETENTION = Duration.ofMinutes(30);
    static final Duration FIRST_WAIT = Duration.ofSeconds(45);
    private static final Duration FOLLOW_WAIT = Duration.ofSeconds(45);
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final String SNAPSHOT_PREFIX = "LIVE-";

    private final AttendanceReportCalculationOrchestrator orchestrator;
    private final AttendanceReportSourceRepository repository;
    private final AttendanceReportProjectionPublicationUseCase publisher;
    private final AttendanceDashboardWorkbenchMapper workbenchMapper;
    private final Executor executor;
    private final boolean shutdownExecutor;
    private final Duration firstWait;
    private final MeterRegistry meterRegistry;
    private final Counter cacheHitMetric;
    private final Counter cacheMissMetric;
    private final Counter cacheEvictionMetric;
    private final Counter safeFailureMetric;
    private final Timer calculationTimer;
    private final DistributionSummary factRowCount;
    private final ConcurrentMap<CalculationKey, CacheEntry> cache =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CalculationKey, CompletableFuture<CacheEntry>>
            inflight = new ConcurrentHashMap<>();
    private final Object cacheMaintenanceLock = new Object();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();

    public RealtimeAttendanceReportSnapshotService(
            AttendanceReportCalculationOrchestrator orchestrator,
            AttendanceReportSourceRepository repository,
            MeterRegistry meterRegistry) {
        this(
                orchestrator,
                repository,
                meterRegistry,
                null,
                null,
                Runnable::run,
                FIRST_WAIT,
                false);
    }

    @Autowired
    public RealtimeAttendanceReportSnapshotService(
            AttendanceReportCalculationOrchestrator orchestrator,
            AttendanceReportSourceRepository repository,
            MeterRegistry meterRegistry,
            @Autowired(required = false)
                    AttendanceDashboardWorkbenchMapper workbenchMapper,
            @Autowired(required = false)
                    AttendanceReportProjectionPublicationUseCase publisher) {
        this(
                orchestrator,
                repository,
                meterRegistry,
                workbenchMapper,
                publisher,
                newCalculationExecutor(),
                FIRST_WAIT,
                true);
    }

    RealtimeAttendanceReportSnapshotService(
            AttendanceReportCalculationOrchestrator orchestrator,
            AttendanceReportSourceRepository repository,
            MeterRegistry meterRegistry,
            AttendanceDashboardWorkbenchMapper workbenchMapper,
            Executor executor,
            Duration firstWait) {
        this(
                orchestrator,
                repository,
                meterRegistry,
                workbenchMapper,
                null,
                executor,
                firstWait,
                true);
    }

    RealtimeAttendanceReportSnapshotService(
            AttendanceReportCalculationOrchestrator orchestrator,
            AttendanceReportSourceRepository repository,
            MeterRegistry meterRegistry,
            AttendanceReportProjectionPublicationUseCase publisher) {
        this(
                orchestrator,
                repository,
                meterRegistry,
                null,
                publisher,
                Runnable::run,
                FIRST_WAIT,
                false);
    }

    private RealtimeAttendanceReportSnapshotService(
            AttendanceReportCalculationOrchestrator orchestrator,
            AttendanceReportSourceRepository repository,
            MeterRegistry meterRegistry,
            AttendanceDashboardWorkbenchMapper workbenchMapper,
            AttendanceReportProjectionPublicationUseCase publisher,
            Executor executor,
            Duration firstWait,
            boolean shutdownExecutor) {
        this.orchestrator = Objects.requireNonNull(
                orchestrator, "orchestrator");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = publisher;
        this.workbenchMapper = workbenchMapper;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.shutdownExecutor = shutdownExecutor;
        this.firstWait = Objects.requireNonNull(firstWait, "firstWait");
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

    @PreDestroy
    void shutdown() {
        if (shutdownExecutor && executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    private static ExecutorService newCalculationExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "report-calc-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
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

        ReportFilter resolvedFilter = new ReportFilter(
                requestedFilter.period(),
                companyId,
                requestedFilter.organizationId(),
                requestedFilter.employeeId(),
                requestedFilter.status());
        Optional<ReportSourceSnapshot> pinned = loadPinnedSnapshot(
                principalId,
                capabilityCode,
                resolvedFilter,
                expectedSnapshotVersion,
                authorizationTime);
        if (pinned.isPresent()) {
            recordCacheHit();
            return pinned;
        }
        if (repository.hasCommittedSourceEvidence(
                companyId, requestedFilter.period(), authorizationTime)) {
            throw pinNotReady();
        }
        return Optional.of(emptySnapshot(
                authorization, resolvedFilter, authorizationTime));
    }

    private static ApiProblemException pinNotReady() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_REPORT_PIN_NOT_READY",
                "该月核算尚未完成",
                true);
    }

    private static ReportSourceSnapshot emptySnapshot(
            RealtimeAuthorization authorization,
            ReportFilter filter,
            Instant authorizationTime) {
        return new ReportSourceSnapshot(
                authorization.scope(),
                filter,
                "UNPINNED-EMPTY",
                "OPEN",
                authorizationTime,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public void materializeCompanyMonth(
            String companyId,
            YearMonth period,
            Instant authorizationTime) {
        currentEntry(companyId, period, "SYSTEM", authorizationTime);
    }

    public ReportSourceSnapshot recalculate(
            String principalId,
            String capabilityCode,
            ReportFilter requestedFilter,
            Instant authorizationTime,
            RecalcWindow window) {
        if (window == null || window == RecalcWindow.MONTH) {
            return recalculate(
                    principalId,
                    capabilityCode,
                    requestedFilter,
                    authorizationTime);
        }
        Objects.requireNonNull(requestedFilter, "requestedFilter");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        String companyId = resolveCompanyId(
                principalId,
                capabilityCode,
                requestedFilter,
                authorizationTime);
        if (companyId == null) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                    "当前用户没有该报表范围的访问权限");
        }
        RealtimeAuthorization authorization =
                repository.resolveRealtimeAuthorization(
                                principalId,
                                capabilityCode,
                                companyId,
                                authorizationTime)
                        .orElseThrow(() -> new ApiProblemException(
                                HttpStatus.FORBIDDEN,
                                "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                                "当前用户没有该报表范围的访问权限"));
        LocalDate today = authorizationTime
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate();
        RecalcWindow.DateSpan span = window.resolve(
                today, requestedFilter.period());
        ReportSourceSnapshot last = null;
        for (YearMonth month : window.months(span)) {
            if (shouldSkipClosed(companyId, month, authorizationTime)) {
                continue;
            }
            LocalDate writeStart = span.writeStart(month);
            LocalDate writeEndExclusive = span.writeEndExclusive(month);
            if (!writeEndExclusive.isAfter(writeStart)) {
                continue;
            }
            evictCompanyMonth(companyId, month);
            CacheEntry entry = calculate(
                    companyId,
                    month,
                    principalId,
                    authorizationTime,
                    writeStart,
                    writeEndExclusive);
            cache.put(new CalculationKey(companyId, month, 0L), entry);
            ReportFilter resolvedFilter = new ReportFilter(
                    month,
                    companyId,
                    requestedFilter.organizationId(),
                    requestedFilter.employeeId(),
                    requestedFilter.status());
            last = toSnapshot(entry, authorization, resolvedFilter);
        }
        if (last == null) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_REPORT_PERIOD_CLOSED",
                    "所选重算窗口内没有未关闭的月份",
                    false);
        }
        return last;
    }

    public void materializeCompanyMonthWindow(
            String companyId,
            YearMonth period,
            RecalcWindow window,
            Instant authorizationTime) {
        LocalDate today = authorizationTime
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate();
        RecalcWindow.DateSpan span = window.resolve(today, period);
        LocalDate writeStart = span.writeStart(period);
        LocalDate writeEndExclusive = span.writeEndExclusive(period);
        if (!writeEndExclusive.isAfter(writeStart)) {
            return;
        }
        if (shouldSkipClosed(companyId, period, authorizationTime)) {
            return;
        }
        evictCompanyMonth(companyId, period);
        CacheEntry entry = calculate(
                companyId,
                period,
                "SYSTEM",
                authorizationTime,
                writeStart,
                writeEndExclusive);
        cache.put(new CalculationKey(companyId, period, 0L), entry);
    }

    public ReportSourceSnapshot recalculateEmployee(
            String principalId,
            String capabilityCode,
            ReportFilter requestedFilter,
            Instant authorizationTime,
            String employeeId,
            java.time.LocalDate writeStart,
            java.time.LocalDate writeEndExclusive) {
        Objects.requireNonNull(requestedFilter, "requestedFilter");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(writeStart, "writeStart");
        Objects.requireNonNull(writeEndExclusive, "writeEndExclusive");
        String companyId = resolveCompanyId(
                principalId,
                capabilityCode,
                requestedFilter,
                authorizationTime);
        if (companyId == null) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                    "当前用户没有该报表范围的访问权限");
        }
        RealtimeAuthorization authorization =
                repository.resolveRealtimeAuthorization(
                                principalId,
                                capabilityCode,
                                companyId,
                                authorizationTime)
                        .orElseThrow(() -> new ApiProblemException(
                                HttpStatus.FORBIDDEN,
                                "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                                "当前用户没有该报表范围的访问权限"));
        if (shouldSkipClosed(companyId, requestedFilter.period(), authorizationTime)) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_REPORT_PERIOD_CLOSED",
                    "所选月份已关闭，无法调整",
                    false);
        }
        evictCompanyMonth(companyId, requestedFilter.period());
        CacheEntry entry = calculate(
                companyId,
                requestedFilter.period(),
                principalId,
                authorizationTime,
                writeStart,
                writeEndExclusive,
                employeeId);
        evictCompanyMonth(companyId, requestedFilter.period());
        ReportFilter resolvedFilter = new ReportFilter(
                requestedFilter.period(),
                companyId,
                requestedFilter.organizationId(),
                requestedFilter.employeeId(),
                requestedFilter.status());
        return toSnapshot(entry, authorization, resolvedFilter);
    }

    public ReportSourceSnapshot recalculateEmployees(
            String principalId,
            String capabilityCode,
            ReportFilter requestedFilter,
            Instant authorizationTime,
            java.util.Collection<String> employeeIds,
            java.time.LocalDate writeStart,
            java.time.LocalDate writeEndExclusive) {
        Objects.requireNonNull(requestedFilter, "requestedFilter");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        Objects.requireNonNull(employeeIds, "employeeIds");
        Objects.requireNonNull(writeStart, "writeStart");
        Objects.requireNonNull(writeEndExclusive, "writeEndExclusive");
        if (employeeIds.size() == 1) {
            return recalculateEmployee(
                    principalId,
                    capabilityCode,
                    requestedFilter,
                    authorizationTime,
                    employeeIds.iterator().next(),
                    writeStart,
                    writeEndExclusive);
        }
        String companyId = resolveCompanyId(
                principalId,
                capabilityCode,
                requestedFilter,
                authorizationTime);
        if (companyId == null) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                    "当前用户没有该报表范围的访问权限");
        }
        RealtimeAuthorization authorization =
                repository.resolveRealtimeAuthorization(
                                principalId,
                                capabilityCode,
                                companyId,
                                authorizationTime)
                        .orElseThrow(() -> new ApiProblemException(
                                HttpStatus.FORBIDDEN,
                                "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                                "当前用户没有该报表范围的访问权限"));
        if (shouldSkipClosed(companyId, requestedFilter.period(), authorizationTime)) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_REPORT_PERIOD_CLOSED",
                    "所选月份已关闭，无法调整",
                    false);
        }
        evictCompanyMonth(companyId, requestedFilter.period());
        CacheEntry entry = calculate(
                companyId,
                requestedFilter.period(),
                principalId,
                authorizationTime,
                writeStart,
                writeEndExclusive,
                employeeIds);
        evictCompanyMonth(companyId, requestedFilter.period());
        ReportFilter resolvedFilter = new ReportFilter(
                requestedFilter.period(),
                companyId,
                requestedFilter.organizationId(),
                requestedFilter.employeeId(),
                requestedFilter.status());
        return toSnapshot(entry, authorization, resolvedFilter);
    }

    public ReportSourceSnapshot recalculate(
            String principalId,
            String capabilityCode,
            ReportFilter requestedFilter,
            Instant authorizationTime) {
        Objects.requireNonNull(requestedFilter, "requestedFilter");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        String companyId = resolveCompanyId(
                principalId,
                capabilityCode,
                requestedFilter,
                authorizationTime);
        if (companyId == null) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                    "当前用户没有该报表范围的访问权限");
        }
        RealtimeAuthorization authorization =
                repository.resolveRealtimeAuthorization(
                                principalId,
                                capabilityCode,
                                companyId,
                                authorizationTime)
                        .orElseThrow(() -> new ApiProblemException(
                                HttpStatus.FORBIDDEN,
                                "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                                "当前用户没有该报表范围的访问权限"));
        CalculationKey key = new CalculationKey(
                companyId, requestedFilter.period(), 0L);
        if (!inflight.containsKey(key)) {
            evictCompanyMonth(companyId, requestedFilter.period());
        }
        ReportFilter resolvedFilter = new ReportFilter(
                requestedFilter.period(),
                companyId,
                requestedFilter.organizationId(),
                requestedFilter.employeeId(),
                requestedFilter.status());
        try {
            CacheEntry entry = currentEntry(
                    companyId,
                    requestedFilter.period(),
                    principalId,
                    authorizationTime);
            return toSnapshot(entry, authorization, resolvedFilter);
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            safeFailureMetric.increment();
            throw calculationUnavailable(exception);
        }
    }

    public boolean sourcesNewerThanPin(
            ReportSourceSnapshot snapshot, Instant asOf) {
        if (snapshot == null || snapshot.sourceVersions().isEmpty()) {
            return false;
        }
        List<SourceCutoff> cutoffs =
                repository.listCommittedSourceCutoffs(asOf);
        if (cutoffs == null || cutoffs.isEmpty()) {
            return false;
        }
        return cutoffs.stream().anyMatch(cutoff -> {
            Instant pinnedCutoff = pinnedCutoff(
                    snapshot.sourceVersions(), cutoff.sourceType());
            return pinnedCutoff != null
                    && cutoff.committedAt() != null
                    && cutoff.committedAt().isAfter(pinnedCutoff);
        });
    }

    private Optional<ReportSourceSnapshot> loadPinnedSnapshot(
            String principalId,
            String capabilityCode,
            ReportFilter filter,
            String expectedSnapshotVersion,
            Instant authorizationTime) {
        Optional<ReportSourceSnapshot> pinned =
                repository.loadAuthorizedSnapshot(
                        principalId,
                        capabilityCode,
                        filter,
                        authorizationTime);
        if (pinned == null || pinned.isEmpty()) {
            return Optional.empty();
        }
        ReportSourceSnapshot snapshot = pinned.orElseThrow();
        if (expectedSnapshotVersion != null
                && !expectedSnapshotVersion.equals(
                        snapshot.projectionVersion())) {
            throw snapshotChanged();
        }
        return Optional.of(snapshot);
    }

    private static boolean isLiveCapability(String capabilityCode) {
        return CapabilityCodes.ATTENDANCE_SELF_READ.equals(capabilityCode);
    }

    private static ApiProblemException snapshotChanged() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_REPORT_SNAPSHOT_CHANGED",
                "考勤输入快照已更新，请返回第一页重新加载",
                true);
    }

    private void evictCompanyMonth(String companyId, YearMonth period) {
        cache.entrySet().removeIf(entry ->
                entry.getKey().companyId().equals(companyId)
                        && entry.getKey().period().equals(period));
        inflight.entrySet().removeIf(entry ->
                entry.getKey().companyId().equals(companyId)
                        && entry.getKey().period().equals(period));
    }

    private static Instant pinnedCutoff(
            List<String> sourceVersions, String sourceType) {
        String prefix = "SOURCE." + sourceType + ":";
        Instant latest = null;
        for (String version : sourceVersions) {
            if (version == null || !version.startsWith(prefix)) {
                continue;
            }
            int digestSeparator = version.lastIndexOf(':');
            if (digestSeparator <= prefix.length()) {
                continue;
            }
            String cutoff = version.substring(prefix.length(), digestSeparator);
            if ("UNSYNCED".equals(cutoff)) {
                continue;
            }
            try {
                Instant parsed = Instant.parse(cutoff);
                if (latest == null || parsed.isAfter(latest)) {
                    latest = parsed;
                }
            } catch (RuntimeException ignored) {
                // Pin markers that are not ISO instants are not comparable.
            }
        }
        return latest;
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
        Optional<CacheEntry> fresh = findFresh(companyId, period, requestedAt);
        if (fresh.isPresent()) {
            recordCacheHit();
            return fresh.orElseThrow();
        }
        prune(requestedAt);
        // Company-month is the cache identity. Do not hold the map lock while
        // the 557-person month engine runs; other report tabs were stalling.
        CalculationKey key = new CalculationKey(companyId, period, 0L);
        boolean[] started = {false};
        CompletableFuture<CacheEntry> flight = inflight.computeIfAbsent(
                key,
                ignored -> {
                    started[0] = true;
                    recordCacheMiss();
                    return CompletableFuture.supplyAsync(
                            () -> {
                                CacheEntry calculated = calculate(
                                        companyId,
                                        period,
                                        principalId,
                                        requestedAt);
                                cache.put(key, calculated);
                                return calculated;
                            },
                            executor);
                });
        flight.whenComplete((ignored, error) -> inflight.remove(key, flight));
        try {
            CacheEntry result;
            if (flight.isDone()) {
                result = flight.get();
                if (!started[0]) {
                    recordCacheHit();
                }
            } else if (!started[0]) {
                log.warn(
                        "report engine already running, waiting for shared result");
                result = flight.get(
                        FOLLOW_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                result = flight.get(
                        firstWait.toMillis(), TimeUnit.MILLISECONDS);
            }
            prune(requestedAt);
            return result;
        } catch (TimeoutException timeout) {
            CacheEntry completed = flight.getNow(null);
            if (completed != null) {
                prune(requestedAt);
                return completed;
            }
            log.warn(
                    "month calculation still running after wait company={} period={}",
                    companyId,
                    period);
            throw calculationUnavailable(new IllegalStateException(
                    "Timeout waiting for shared month calculation"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CacheEntry completed = flight.getNow(null);
            if (completed != null) {
                return completed;
            }
            throw calculationUnavailable(new IllegalStateException(
                    "Interrupted waiting for shared month calculation"));
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null
                    ? failed
                    : failed.getCause();
            if (cause instanceof ApiProblemException problem) {
                safeFailureMetric.increment();
                throw problem;
            }
            safeFailureMetric.increment();
            throw calculationUnavailable(
                    cause instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException(cause));
        }
    }

    private CacheEntry sharedProvisional(
            CalculationKey key,
            String companyId,
            YearMonth period,
            String principalId,
            Instant requestedAt) {
        CacheEntry existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        CacheEntry created = provisional(
                companyId, period, principalId, requestedAt);
        CacheEntry raced = cache.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    private CacheEntry provisional(
            String companyId,
            YearMonth period,
            String principalId,
            Instant requestedAt) {
        if (workbenchMapper == null) {
            throw calculationUnavailable(new IllegalStateException(
                    "full month calculation exceeded the first-page wait"));
        }
        PublishCommand command = RealtimeReportProvisionalAssembler.assemble(
                workbenchMapper,
                companyId,
                period,
                principalId,
                requestedAt);
        return new CacheEntry(
                command,
                SNAPSHOT_PREFIX + command.metadata().sourceSnapshotDigest(),
                requestedAt,
                true);
    }

    private boolean shouldSkipClosed(
            String companyId,
            YearMonth period,
            Instant authorizationTime) {
        try {
            Optional<ReportSourceSnapshot> pinned =
                    repository.loadAuthorizedSnapshot(
                            "SYSTEM",
                            CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                            new ReportFilter(
                                    period, companyId, null, null, null),
                            authorizationTime);
            if (pinned == null || pinned.isEmpty()) {
                return false;
            }
            String state = pinned.orElseThrow().periodState();
            return "CLOSED".equals(state) || "FROZEN".equals(state);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private CacheEntry calculate(
            String companyId,
            YearMonth period,
            String principalId,
            Instant dataAsOf) {
        return calculate(companyId, period, principalId, dataAsOf, null, null);
    }

    private CacheEntry calculate(
            String companyId,
            YearMonth period,
            String principalId,
            Instant dataAsOf,
            java.time.LocalDate writeStart,
            java.time.LocalDate writeEndExclusive) {
        return calculate(
                companyId, period, principalId, dataAsOf,
                writeStart, writeEndExclusive, (String) null);
    }

    private CacheEntry calculate(
            String companyId,
            YearMonth period,
            String principalId,
            Instant dataAsOf,
            java.time.LocalDate writeStart,
            java.time.LocalDate writeEndExclusive,
            java.util.Collection<String> employeeIds) {
        if (employeeIds == null || employeeIds.size() <= 1) {
            String employeeId = employeeIds == null || employeeIds.isEmpty()
                    ? null
                    : employeeIds.iterator().next();
            return calculate(
                    companyId,
                    period,
                    principalId,
                    dataAsOf,
                    writeStart,
                    writeEndExclusive,
                    employeeId);
        }
        dataAsOf = dataAsOf.truncatedTo(ChronoUnit.MICROS);
        Timer.Sample sample = Timer.start(meterRegistry);
        log.info(
                "employee-set calculation starting company={} period={} employees={} start={} end={}",
                companyId,
                period,
                employeeIds.size(),
                writeStart,
                writeEndExclusive);
        try {
            PublishCommand command = orchestrator.assemble(
                    companyId,
                    period,
                    PeriodState.OPEN,
                    principalId,
                    dataAsOf,
                    writeStart,
                    writeEndExclusive,
                    employeeIds);
            if (!companyId.equals(command.metadata().companyId())
                    || !period.equals(command.metadata().period())
                    || !dataAsOf.equals(command.metadata().dataAsOf())) {
                throw new IllegalStateException(
                        "calculation result does not match its requested scope");
            }
            factRowCount.record(factRowCount(command));
            String snapshotVersion = SNAPSHOT_PREFIX
                    + command.metadata().sourceSnapshotDigest();
            CacheEntry calculated = new CacheEntry(
                    command, snapshotVersion, dataAsOf, false);
            long persistStarted = System.nanoTime();
            if (publisher != null) {
                PublicationResult published = publisher.publish(
                        command,
                        writeStart,
                        writeEndExclusive,
                        employeeIds);
                if (published != null
                        && published.projectionVersion() != null) {
                    snapshotVersion = published.projectionVersion();
                    calculated = new CacheEntry(
                            command, snapshotVersion, dataAsOf, false);
                }
            }
            log.info(
                    "recalc-persist company={} period={} window=employee-set "
                            + "employees={} persistMs={} version={}",
                    companyId,
                    period,
                    employeeIds.size(),
                    (System.nanoTime() - persistStarted) / 1_000_000L,
                    snapshotVersion);
            log.info(
                    "employee-set calculation finished company={} period={} employees={} version={}",
                    companyId,
                    period,
                    employeeIds.size(),
                    snapshotVersion);
            return calculated;
        } catch (RuntimeException failed) {
            log.error(
                    "employee-set calculation failed company={} period={}",
                    companyId,
                    period,
                    failed);
            throw failed;
        } finally {
            sample.stop(calculationTimer);
        }
    }

    private CacheEntry calculate(
            String companyId,
            YearMonth period,
            String principalId,
            Instant dataAsOf,
            java.time.LocalDate writeStart,
            java.time.LocalDate writeEndExclusive,
            String employeeId) {
        dataAsOf = dataAsOf.truncatedTo(ChronoUnit.MICROS);
        Timer.Sample sample = Timer.start(meterRegistry);
        if (employeeId == null || employeeId.isBlank()) {
            log.info("month calculation starting company={} period={}",
                    companyId, period);
        } else {
            log.info(
                    "employee-day calculation starting company={} period={} employee={} start={} end={}",
                    companyId,
                    period,
                    employeeId,
                    writeStart,
                    writeEndExclusive);
        }
        try {
            PublishCommand command =
                    (employeeId == null || employeeId.isBlank())
                            && writeStart == null
                            && writeEndExclusive == null
                    ? orchestrator.assemble(
                            companyId,
                            period,
                            PeriodState.OPEN,
                            principalId,
                            dataAsOf)
                    : orchestrator.assemble(
                            companyId,
                            period,
                            PeriodState.OPEN,
                            principalId,
                            dataAsOf,
                            writeStart,
                            writeEndExclusive,
                            employeeId);
            if (!companyId.equals(command.metadata().companyId())
                    || !period.equals(command.metadata().period())
                    || !dataAsOf.equals(command.metadata().dataAsOf())) {
                throw new IllegalStateException(
                        "calculation result does not match its requested scope");
            }
            factRowCount.record(factRowCount(command));
            String snapshotVersion = SNAPSHOT_PREFIX
                    + command.metadata().sourceSnapshotDigest();
            CacheEntry calculated = new CacheEntry(
                    command, snapshotVersion, dataAsOf, false);
            long persistStarted = System.nanoTime();
            if (publisher != null) {
                PublicationResult published =
                        writeStart == null
                                && writeEndExclusive == null
                                && (employeeId == null || employeeId.isBlank())
                        ? publisher.publish(command)
                        : publisher.publish(
                                command,
                                writeStart,
                                writeEndExclusive,
                                employeeId);
                if (published != null
                        && published.projectionVersion() != null) {
                    snapshotVersion = published.projectionVersion();
                    calculated = new CacheEntry(
                            command, snapshotVersion, dataAsOf, false);
                }
            }
            String windowType = employeeId == null || employeeId.isBlank()
                    ? (writeStart == null && writeEndExclusive == null
                            ? "full-month"
                            : "window")
                    : "employee-set";
            log.info(
                    "recalc-persist company={} period={} window={} "
                            + "employees={} persistMs={} version={}",
                    companyId,
                    period,
                    windowType,
                    employeeId == null || employeeId.isBlank() ? 0 : 1,
                    (System.nanoTime() - persistStarted) / 1_000_000L,
                    snapshotVersion);
            if (employeeId == null || employeeId.isBlank()) {
                log.info(
                        "month calculation finished company={} period={} version={}",
                        companyId,
                        period,
                        snapshotVersion);
            } else {
                log.info(
                        "employee-day calculation finished company={} period={} employee={} version={}",
                        companyId,
                        period,
                        employeeId,
                        snapshotVersion);
            }
            return calculated;
        } catch (RuntimeException failed) {
            log.error(
                    "month calculation failed company={} period={}",
                    companyId,
                    period,
                    failed);
            throw failed;
        } finally {
            sample.stop(calculationTimer);
        }
    }

    private Optional<CacheEntry> findFresh(
            String companyId,
            YearMonth period,
            Instant now) {
        Instant oldest = now.minus(SNAPSHOT_RETENTION);
        return cache.entrySet().stream()
                .filter(entry -> entry.getKey().companyId().equals(companyId))
                .filter(entry -> entry.getKey().period().equals(period))
                .map(Map.Entry::getValue)
                .filter(entry -> !entry.createdAt().isBefore(oldest))
                .max(Comparator
                        .comparing((CacheEntry entry) -> !entry.provisional())
                        .thenComparing(CacheEntry::createdAt));
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
        return findFresh(companyId, period, now)
                .filter(entry -> entry.snapshotVersion()
                        .equals(expectedSnapshotVersion));
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
        Set<String> organizationSubtree = organizationSubtree(filter);
        List<DailyFact> dailyFacts = command.calculatedFacts().stream()
                .map(fact -> fact.facts().dailyFact())
                .filter(fact -> visible(
                        authorization,
                        filter,
                        organizationSubtree,
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
                            organizationSubtree,
                            daily.companyId(),
                            value.employeeId(),
                            value.organizationId()))
                    .forEach(exceptions::add);
        });
        command.currentExceptionFacts().stream()
                .filter(value -> visible(
                        authorization,
                        filter,
                        organizationSubtree,
                        value.companyId(),
                        value.fact().employeeId(),
                        value.fact().organizationId()))
                .map(value -> value.fact())
                .forEach(exceptions::add);

        List<OaDocumentFact> oaFacts = command.oaDocumentFacts().stream()
                .filter(value -> visible(
                        authorization,
                        filter,
                        organizationSubtree,
                        value.companyId(),
                        value.employeeId(),
                        value.organizationId()))
                .map(value -> {
                    DailyFact matched = identity(
                            identityByEmployeeOrganization,
                            identityByEmployee,
                            value.employeeId(),
                            value.organizationId());
                    return matched == null
                            ? null
                            : toOaDocumentFact(value, matched);
                })
                .filter(Objects::nonNull)
                .toList();
        List<TimeAccountFact> timeAccounts = command.timeAccountFacts().stream()
                .filter(value -> visible(
                        authorization,
                        filter,
                        organizationSubtree,
                        value.companyId(),
                        value.employeeId(),
                        value.organizationId()))
                .map(value -> {
                    DailyFact matched = identity(
                            identityByEmployeeOrganization,
                            identityByEmployee,
                            value.employeeId(),
                            value.organizationId());
                    return matched == null
                            ? null
                            : toTimeAccountFact(value, matched);
                })
                .filter(Objects::nonNull)
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
                timeAccounts,
                repository.listWorkWindows(
                        authorization.companyId(),
                        filter.period(),
                        command.metadata().dataAsOf()));
    }

    private Set<String> organizationSubtree(ReportFilter filter) {
        if (filter.organizationId() == null) {
            return Set.of();
        }
        Set<String> subtree = new HashSet<>();
        subtree.add(filter.organizationId());
        Set<String> listed = repository.listOrganizationSubtree(
                filter.organizationId());
        if (listed != null) {
            subtree.addAll(listed);
        }
        return subtree;
    }

    private static boolean visible(
            RealtimeAuthorization authorization,
            ReportFilter filter,
            Set<String> organizationSubtree,
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
        boolean organizationAllows = filter.organizationId() == null
                || filter.organizationId().equals(organizationId)
                || organizationSubtree.contains(organizationId);
        return scopeAllows
                && (filter.employeeId() == null
                        || filter.employeeId().equals(employeeId))
                && organizationAllows;
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
                value.sourceVersion(),
                value.sourceOrigin());
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
        String message = exception.getMessage() == null
                ? ""
                : exception.getMessage();
        String code = failClosedCode(message);
        String detail;
        if (message.contains("Timeout")
                || message.contains("timeout")
                || message.contains("Statement cancelled")) {
            detail = "报表计算超时，请稍后重试";
        } else if (message.isBlank()) {
            detail = "当前考勤输入尚不能安全计算，请检查来源同步、人员匹配、班次和单据";
        } else {
            detail = "当前考勤输入尚不能安全计算："
                    + firstNonBlankLine(message);
        }
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                code,
                detail,
                true);
    }

    static String firstNonBlankLine(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse(message.trim());
    }

    static String failClosedCode(String message) {
        if (message.contains("OA_LEAVE_REVOCATION_UNRESOLVED")) {
            return "OA_LEAVE_REVOCATION_UNRESOLVED";
        }
        if (message.contains("Timeout")
                || message.contains("timeout")
                || message.contains("Statement cancelled")) {
            return "ATTENDANCE_REPORT_QUERY_TIMEOUT";
        }
        if (message.contains("Required attendance source is not synchronized")
                || message.contains("Required attendance source is not active")
                || message.contains("attendance source versions are unavailable")) {
            return "ATTENDANCE_SOURCE_NOT_READY";
        }
        if (message.contains("identity is missing or ambiguous")
                || message.contains("No unambiguous occurrence-time identity")
                || message.contains("Employee identity")) {
            return "ATTENDANCE_IDENTITY_AMBIGUOUS";
        }
        if (message.contains("attendance-group")
                || message.contains("Attendance policy")
                || message.contains("attendance policy")) {
            return "ATTENDANCE_POLICY_AMBIGUOUS";
        }
        if (message.contains("shift")
                || message.contains("Shift")) {
            return "ATTENDANCE_SHIFT_AMBIGUOUS";
        }
        if (message.contains("Calendar authority")
                || message.contains("calendar")) {
            return "ATTENDANCE_CALENDAR_AMBIGUOUS";
        }
        if (message.contains("QUARANTINED")
                || message.contains("quarantine")) {
            return "ATTENDANCE_SOURCE_QUARANTINED";
        }
        return "ATTENDANCE_REPORT_REALTIME_CALCULATION_UNAVAILABLE";
    }

    private record CalculationKey(
            String companyId, YearMonth period, long cutoffEpochSecond) {
    }

    private record CacheEntry(
            PublishCommand command,
            String snapshotVersion,
            Instant createdAt,
            boolean provisional) {
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
