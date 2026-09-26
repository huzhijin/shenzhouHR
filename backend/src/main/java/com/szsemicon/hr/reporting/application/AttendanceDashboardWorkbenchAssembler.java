package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.AuthorizedScope;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardSnapshot;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.ExceptionItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.ExceptionSummary;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.PrincipalHome;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceDashboardWorkbenchMapper;
import com.szsemicon.hr.reporting.infrastructure.persistence.DashboardWorkbenchRows;
import com.szsemicon.hr.reporting.infrastructure.persistence.DashboardWorkbenchRows.PunchRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.DashboardWorkbenchRows.RosterRow;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AttendanceDashboardWorkbenchAssembler {

    private static final Logger log =
            LoggerFactory.getLogger(AttendanceDashboardWorkbenchAssembler.class);
    static final ZoneId ZONE = AttendanceDashboardService.BUSINESS_ZONE;
    private static final LocalTime LATE_AFTER = LocalTime.of(9, 0);
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);
    private static final Duration SHARED_TTL = Duration.ofMinutes(15);
    static final Duration ASSEMBLE_BUDGET = Duration.ofSeconds(20);
    static final int MAX_PUBLISHED_EXCEPTIONS = 500;

    private final AttendanceReportSourceRepository reportSources;
    private final AttendanceDashboardWorkbenchMapper mapper;
    private final Clock clock;
    private final Duration assembleBudget;
    private final Executor executor;
    private final boolean shutdownExecutor;
    private final ConcurrentMap<String, Cached<CompanyDayBundle>> companyDayCache =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Cached<List<PunchRow>>> punchCache =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Cached<Assembled>> assembledCache =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Assembled>> inflight =
            new ConcurrentHashMap<>();

    @Autowired
    public AttendanceDashboardWorkbenchAssembler(
            AttendanceReportSourceRepository reportSources,
            AttendanceDashboardWorkbenchMapper mapper) {
        this(
                reportSources,
                mapper,
                Clock.systemUTC(),
                ASSEMBLE_BUDGET,
                newWorkbenchExecutor(),
                true);
    }

    AttendanceDashboardWorkbenchAssembler(
            AttendanceReportSourceRepository reportSources,
            AttendanceDashboardWorkbenchMapper mapper,
            Clock clock,
            Duration assembleBudget) {
        this(reportSources, mapper, clock, assembleBudget, Runnable::run, false);
    }

    AttendanceDashboardWorkbenchAssembler(
            AttendanceReportSourceRepository reportSources,
            AttendanceDashboardWorkbenchMapper mapper,
            Clock clock,
            Duration assembleBudget,
            Executor executor) {
        this(reportSources, mapper, clock, assembleBudget, executor, true);
    }

    private AttendanceDashboardWorkbenchAssembler(
            AttendanceReportSourceRepository reportSources,
            AttendanceDashboardWorkbenchMapper mapper,
            Clock clock,
            Duration assembleBudget,
            Executor executor,
            boolean shutdownExecutor) {
        this.reportSources = Objects.requireNonNull(
                reportSources, "reportSources");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.assembleBudget = Objects.requireNonNull(
                assembleBudget, "assembleBudget");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.shutdownExecutor = shutdownExecutor;
        if (assembleBudget.isNegative() || assembleBudget.isZero()) {
            throw new IllegalArgumentException(
                    "assembleBudget must be positive");
        }
    }

    @PreDestroy
    void shutdown() {
        if (shutdownExecutor && executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    private static ExecutorService newWorkbenchExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "workbench-assemble-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public Optional<Assembled> assemble(
            String principalId,
            YearMonth period,
            LocalDate businessDate,
            Instant authorizationTime,
            boolean employeeDetailsAuthorized) {
        return assemble(
                principalId,
                period,
                businessDate,
                false,
                authorizationTime,
                employeeDetailsAuthorized);
    }

    public Optional<Assembled> assemble(
            String principalId,
            YearMonth period,
            LocalDate businessDate,
            boolean dayWindow,
            Instant authorizationTime,
            boolean employeeDetailsAuthorized) {
        LocalDate today = authorizationTime.atZone(ZONE).toLocalDate();
        boolean afterNoon = !authorizationTime.atZone(ZONE)
                .toLocalTime()
                .isBefore(LocalTime.NOON);
        String cacheKey = principalId
                + "|"
                + period
                + "|"
                + businessDate
                + "|"
                + dayWindow
                + "|"
                + afterNoon
                + "|"
                + employeeDetailsAuthorized;
        Cached<Assembled> cached = assembledCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(clock.instant())) {
            return Optional.of(cached.value());
        }
        CompletableFuture<Assembled> flight = inflight.computeIfAbsent(
                cacheKey,
                key -> CompletableFuture.supplyAsync(
                        () -> assembleNow(
                                principalId,
                                period,
                                businessDate,
                                dayWindow,
                                authorizationTime,
                                employeeDetailsAuthorized,
                                key),
                        executor));
        flight.whenComplete((ignored, error) ->
                inflight.remove(cacheKey, flight));
        try {
            return Optional.of(flight.get(
                    assembleBudget.toMillis(),
                    TimeUnit.MILLISECONDS));
        } catch (TimeoutException timeout) {
            Cached<Assembled> published = assembledCache.get(cacheKey);
            if (published != null) {
                log.warn(
                        "workbench returning first company after {}",
                        assembleBudget);
                return Optional.of(published.value());
            }
            log.warn(
                    "workbench hard deadline {} before first company",
                    assembleBudget);
            return Optional.of(placeholder(
                    businessDate, authorizationTime, List.of()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Cached<Assembled> published = assembledCache.get(cacheKey);
            return Optional.of(published == null
                    ? placeholder(businessDate, authorizationTime, List.of())
                    : published.value());
        } catch (ExecutionException failed) {
            log.error("workbench assemble failed", failed.getCause());
            Cached<Assembled> published = assembledCache.get(cacheKey);
            if (published != null) {
                return Optional.of(published.value());
            }
            return Optional.of(placeholder(
                    businessDate, authorizationTime, List.of()));
        }
    }

    private Assembled assembleNow(
            String principalId,
            YearMonth period,
            LocalDate businessDate,
            boolean dayWindow,
            Instant authorizationTime,
            boolean employeeDetailsAuthorized,
            String cacheKey) {
        Instant deadline = clock.instant().plus(assembleBudget);
        Optional<PrincipalHome> home;
        try {
            home = reportSources.resolvePrincipalHome(
                    principalId, businessDate);
        } catch (RuntimeException failed) {
            log.warn("workbench could not resolve principal home", failed);
            home = Optional.empty();
        }
        List<CompanyOption> companies = new ArrayList<>();
        if (home.isPresent()) {
            companies.add(new CompanyOption(
                    home.orElseThrow().companyId(),
                    home.orElseThrow().companyName()));
        }
        if (clock.instant().isBefore(deadline)) {
            addAuthorizedCompanies(
                    companies,
                    principalId,
                    CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                    period,
                    authorizationTime);
            if (companies.isEmpty()) {
                addAuthorizedCompanies(
                        companies,
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        period,
                        authorizationTime);
            }
        }
        if (companies.isEmpty()) {
            Assembled empty = placeholder(
                    businessDate, authorizationTime, List.of());
            assembledCache.put(
                    cacheKey,
                    new Cached<>(empty, clock.instant().plus(SHARED_TTL)));
            return empty;
        }
        companies = new ArrayList<>(orderedCompanies(companies, home));
        LocalDate today = authorizationTime.atZone(ZONE).toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        boolean afterNoon = !authorizationTime.atZone(ZONE)
                .toLocalTime()
                .isBefore(LocalTime.NOON);
        Instant windowStart = yesterday.atStartOfDay(ZONE).toInstant();
        Instant windowEnd = (afterNoon ? today : yesterday)
                .plusDays(1)
                .atStartOfDay(ZONE)
                .toInstant();
        List<ScopedPerson> people = new ArrayList<>();
        Map<String, List<Instant>> punchesByEmployee = new HashMap<>();
        boolean multiCompany = companies.size() > 1;
        int authorizedRoster = 0;
        Assembled latest = placeholder(businessDate, authorizationTime, companies);
        for (CompanyOption company : companies) {
            if (clock.instant().isAfter(deadline)) {
                log.warn(
                        "workbench stopping further companies after {}",
                        assembleBudget);
                break;
            }
            Optional<RealtimeAuthorization> authorization;
            try {
                authorization = resolveCompanyAuthorization(
                        principalId,
                        company.companyId(),
                        authorizationTime);
            } catch (RuntimeException failed) {
                log.error(
                        "workbench could not authorize company {}",
                        company.companyId(),
                        failed);
                continue;
            }
            if (authorization.isEmpty()) {
                continue;
            }
            CompanyDayBundle day;
            try {
                day = sharedCompanyDay(
                        company.companyId(),
                        businessDate,
                        windowStart,
                        windowEnd,
                        authorizationTime);
            } catch (RuntimeException failed) {
                log.error(
                        "workbench could not load company {}",
                        company.companyId(),
                        failed);
                continue;
            }
            Map<String, RosterRow> visible = new LinkedHashMap<>();
            for (RosterRow row : day.roster()) {
                if (!visible(authorization.orElseThrow(), row)) {
                    continue;
                }
                visible.putIfAbsent(row.employeeId(), row);
            }
            authorizedRoster += visible.size();
            for (RosterRow row : visible.values()) {
                String leafName = safeText(row.organizationName(), "未分配组织");
                String reportDepartment = reportDepartmentName(
                        row.organizationId(), leafName, day.departments());
                String organizationName = multiCompany
                        ? organizationLabel(reportDepartment, company.companyName())
                        : reportDepartment;
                people.add(new ScopedPerson(
                        row.employeeId(),
                        safeText(row.employeeNumber(), row.employeeId()),
                        safeText(row.employeeName(), "未命名"),
                        safeText(row.organizationId(), "unassigned"),
                        organizationName,
                        company,
                        day.punchExemptEmployeeIds().contains(row.employeeId())));
                List<Instant> matched = day.punchesByEmployeeId()
                        .get(row.employeeId());
                if (matched != null && !matched.isEmpty()) {
                    punchesByEmployee.put(row.employeeId(), matched);
                }
            }
            latest = toAssembled(
                    principalId,
                    period,
                    dayWindow,
                    home,
                    companies,
                    people,
                    punchesByEmployee,
                    authorizedRoster,
                    yesterday,
                    today,
                    afterNoon,
                    authorizationTime,
                    employeeDetailsAuthorized);
            assembledCache.put(
                    cacheKey,
                    new Cached<>(latest, clock.instant().plus(SHARED_TTL)));
        }
        if (people.isEmpty()) {
            assembledCache.put(
                    cacheKey,
                    new Cached<>(latest, clock.instant().plus(SHARED_TTL)));
        }
        return latest;
    }

    private CompanyDayBundle sharedCompanyDay(
            String companyId,
            LocalDate businessDate,
            Instant windowStart,
            Instant windowEnd,
            Instant now) {
        return cached(
                companyDayCache,
                companyId + "|" + businessDate,
                now,
                () -> {
                    List<RosterRow> roster = nullSafe(
                            mapper.listRoster(companyId, businessDate));
                    Map<String, String> rosterByNumber = new HashMap<>();
                    Set<String> rosterIds = new HashSet<>();
                    Map<String, List<Instant>> punchesByEmployee =
                            new HashMap<>();
                    for (RosterRow row : roster) {
                        rosterIds.add(row.employeeId());
                        if (row.employeeNumber() != null) {
                            rosterByNumber.putIfAbsent(
                                    row.employeeNumber(), row.employeeId());
                        }
                    }
                    for (PunchRow punch : todayPunches(windowStart, windowEnd, now)) {
                        String matchedId = rosterIds.contains(punch.employeeId())
                                ? punch.employeeId()
                                : rosterByNumber.get(punch.employeeNumber());
                        if (matchedId == null) {
                            continue;
                        }
                        punchesByEmployee.computeIfAbsent(
                                        matchedId, ignored -> new ArrayList<>())
                                .add(punch.pointInstant());
                    }
                    return new CompanyDayBundle(
                            roster,
                            Map.copyOf(punchesByEmployee),
                            punchExemptEmployeeIds(companyId, businessDate),
                            departmentPaths(companyId));
                });
    }

    private List<PunchRow> todayPunches(
            Instant windowStart, Instant windowEnd, Instant now) {
        return cached(
                punchCache,
                windowStart + "|" + windowEnd,
                now,
                () -> nullSafe(mapper.listPunchPoints(windowStart, windowEnd)));
    }

    private Assembled toAssembled(
            String principalId,
            YearMonth period,
            boolean dayWindow,
            Optional<PrincipalHome> home,
            List<CompanyOption> companies,
            List<ScopedPerson> people,
            Map<String, List<Instant>> punchesByEmployee,
            int authorizedRoster,
            LocalDate yesterday,
            LocalDate today,
            boolean afterNoon,
            Instant authorizationTime,
            boolean employeeDetailsAuthorized) {
        List<ScopedPerson> ordered = new ArrayList<>(people);
        ordered.sort(Comparator
                .comparing(ScopedPerson::organizationName)
                .thenComparing(ScopedPerson::employeeNumber));
        LocalDate boardDate = afterNoon ? today : yesterday;
        LocalDate fromDate = dayWindow ? boardDate : period.atDay(1);
        List<ExceptionFact> facts = new ArrayList<>();
        for (ScopedPerson person : ordered) {
            facts.addAll(classify(
                    person,
                    yesterday,
                    punchesOn(punchesByEmployee, person, yesterday),
                    true));
            if (afterNoon) {
                facts.addAll(classifyTodayMorning(
                        person,
                        today,
                        punchesOn(punchesByEmployee, person, today)));
            }
        }
        facts.addAll(negativeLeaveFacts(ordered, yesterday));
        if (!dayWindow) {
            facts = mergeFacts(
                    facts,
                    monthSnapshotFacts(
                            principalId,
                            companies,
                            period,
                            fromDate,
                            boardDate,
                            authorizationTime));
        }
        List<TodayPunch> todayPunches = new ArrayList<>();
        int punchedToday = 0;
        int completeToday = 0;
        LocalDate punchBoardDate = today;
        for (ScopedPerson person : ordered) {
            List<Instant> boardPunches = punchesOn(
                    punchesByEmployee, person, punchBoardDate);
            if (boardPunches.isEmpty()) {
                continue;
            }
            punchedToday++;
            if (boardPunches.size() >= 2) {
                completeToday++;
            }
            if (todayPunches.size() < 20 && employeeDetailsAuthorized) {
                todayPunches.add(new TodayPunch(
                        person.employeeNumber(),
                        person.employeeName(),
                        person.organizationName(),
                        CLOCK.format(boardPunches.getFirst()),
                        CLOCK.format(boardPunches.getLast()),
                        boardPunches.size()));
            }
        }
        CompanyOption selected = home
                .flatMap(value -> companies.stream()
                        .filter(company -> company.companyId()
                                .equals(value.companyId()))
                        .findFirst())
                .orElse(companies.getFirst());
        List<ExceptionFact> windowFacts = facts.stream()
                .filter(fact -> !fact.businessDate().isBefore(fromDate)
                        && !fact.businessDate().isAfter(boardDate))
                .filter(AttendanceReportCalculator::actionableException)
                .filter(fact -> !AttendanceReportCalculator
                        .mutedWuhanDalianAugust(fact))
                .toList();
        List<ExceptionItem> items = employeeDetailsAuthorized
                ? windowFacts.stream()
                        .filter(AttendanceReportCalculator::actionableException)
                        .filter(fact -> !AttendanceReportCalculator
                                .mutedWuhanDalianAugust(fact))
                        .sorted(exceptionOrder())
                        .limit(MAX_PUBLISHED_EXCEPTIONS)
                        .map(AttendanceDashboardWorkbenchAssembler::toItem)
                        .toList()
                : List.of();
        DashboardSnapshot snapshot = AttendanceDashboardService
                .snapshotFromFacts(
                        selected.companyId(),
                        "LIVE-WB-" + boardDate,
                        List.of("WORKBENCH-PUNCH:V1"),
                        authorizationTime,
                        "OPEN",
                        scope(home.orElse(null), companies, ordered),
                        windowFacts,
                        fromDate,
                        boardDate,
                        items);
        return new Assembled(
                selected,
                companies,
                snapshot,
                metrics(
                        authorizedRoster,
                        punchedToday,
                        completeToday,
                        snapshot.summary()),
                todayPunches);
    }

    private List<ExceptionFact> monthSnapshotFacts(
            String principalId,
            List<CompanyOption> companies,
            YearMonth period,
            LocalDate fromDate,
            LocalDate boardDate,
            Instant authorizationTime) {
        List<ExceptionFact> facts = new ArrayList<>();
        for (CompanyOption company : companies) {
            Optional<ReportSourceSnapshot> loaded;
            try {
                loaded = loadMonthSnapshot(
                        principalId,
                        company.companyId(),
                        period,
                        authorizationTime);
            } catch (RuntimeException failed) {
                log.warn(
                        "workbench could not load month exceptions for {}",
                        company.companyId(),
                        failed);
                continue;
            }
            if (loaded == null || loaded.isEmpty()) {
                continue;
            }
            for (ExceptionFact fact : nullSafe(
                    loaded.orElseThrow().exceptionFacts())) {
                if (fact.businessDate().isBefore(fromDate)
                        || fact.businessDate().isAfter(boardDate)
                        || (fact.state() != ExceptionState.OPEN
                                && fact.state() != ExceptionState.PENDING_EVIDENCE
                                && fact.state() != ExceptionState.PENDING_REVIEW)) {
                    continue;
                }
                facts.add(fact);
            }
        }
        return facts;
    }

    private Optional<ReportSourceSnapshot> loadMonthSnapshot(
            String principalId,
            String companyId,
            YearMonth period,
            Instant authorizationTime) {
        ReportFilter filter = new ReportFilter(
                period, companyId, null, null, null);
        Optional<ReportSourceSnapshot> loaded =
                reportSources.loadAuthorizedSnapshot(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        filter,
                        authorizationTime);
        if (loaded != null && loaded.isPresent()) {
            return loaded;
        }
        loaded = reportSources.loadAuthorizedSnapshot(
                principalId,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                authorizationTime);
        return loaded == null ? Optional.empty() : loaded;
    }

    private static List<ExceptionFact> mergeFacts(
            List<ExceptionFact> primary,
            List<ExceptionFact> extra) {
        List<ExceptionFact> merged = new ArrayList<>(primary);
        Set<String> keys = new HashSet<>();
        for (ExceptionFact fact : primary) {
            keys.add(factKey(fact));
        }
        for (ExceptionFact fact : extra) {
            if (keys.add(factKey(fact))) {
                merged.add(fact);
            }
        }
        return merged;
    }

    private static String factKey(ExceptionFact fact) {
        return fact.employeeId()
                + "|"
                + fact.businessDate()
                + "|"
                + fact.exceptionType();
    }

    private static Assembled placeholder(
            LocalDate businessDate,
            Instant authorizationTime,
            List<CompanyOption> companies) {
        CompanyOption selected = companies.isEmpty()
                ? new CompanyOption("current-scope", "当前授权范围")
                : companies.getFirst();
        List<CompanyOption> visible = companies.isEmpty()
                ? List.of(selected)
                : companies;
        DashboardSnapshot snapshot = AttendanceDashboardService
                .snapshotFromFacts(
                        selected.companyId(),
                        "LIVE-WB-" + businessDate,
                        List.of("WORKBENCH-PUNCH:V1"),
                        authorizationTime,
                        "OPEN",
                        scope(null, visible, List.of()),
                        List.of(),
                        businessDate,
                        List.of());
        return new Assembled(
                selected,
                visible,
                snapshot,
                metrics(0, 0, 0, snapshot.summary()),
                List.of());
    }

    private void addAuthorizedCompanies(
            List<CompanyOption> companies,
            String principalId,
            String capabilityCode,
            YearMonth period,
            Instant authorizationTime) {
        try {
            for (var company : reportSources.listAuthorizedCompanies(
                    principalId,
                    capabilityCode,
                    period,
                    authorizationTime)) {
                CompanyOption option = new CompanyOption(
                        company.companyId(), company.companyName());
                if (companies.stream().noneMatch(existing ->
                        existing.companyId().equals(option.companyId()))) {
                    companies.add(option);
                }
            }
        } catch (RuntimeException failed) {
            log.error("workbench could not list companies", failed);
        }
    }

    private Optional<RealtimeAuthorization> resolveCompanyAuthorization(
            String principalId,
            String companyId,
            Instant authorizationTime) {
        Optional<RealtimeAuthorization> authorization =
                reportSources.resolveRealtimeAuthorization(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        companyId,
                        authorizationTime);
        if (authorization.isPresent()) {
            return authorization;
        }
        return reportSources.resolveRealtimeAuthorization(
                principalId,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                companyId,
                authorizationTime);
    }

    private static List<CompanyOption> orderedCompanies(
            List<CompanyOption> companies,
            Optional<PrincipalHome> home) {
        if (companies.size() < 2) {
            return companies;
        }
        String preferredId = home.map(PrincipalHome::companyId)
                .orElseGet(() -> preferredCompanyId(companies));
        if (preferredId == null) {
            return companies;
        }
        List<CompanyOption> ordered = new ArrayList<>(companies.size());
        for (CompanyOption company : companies) {
            if (company.companyId().equals(preferredId)) {
                ordered.add(company);
            }
        }
        for (CompanyOption company : companies) {
            if (!company.companyId().equals(preferredId)) {
                ordered.add(company);
            }
        }
        return ordered;
    }

    private static String preferredCompanyId(List<CompanyOption> companies) {
        for (String marker :
                AttendanceDashboardService.PREFERRED_COMPANY_NAME_MARKERS) {
            for (CompanyOption company : companies) {
                if (company.companyName() != null
                        && company.companyName().contains(marker)) {
                    return company.companyId();
                }
            }
        }
        return null;
    }

    private static <T> T cached(
            ConcurrentMap<String, Cached<T>> cache,
            String key,
            Instant now,
            java.util.function.Supplier<T> loader) {
        Cached<T> existing = cache.get(key);
        if (existing != null && existing.expiresAt().isAfter(now)) {
            return existing.value();
        }
        T value = loader.get();
        cache.put(key, new Cached<>(value, now.plus(SHARED_TTL)));
        return value;
    }

    private record Cached<T>(T value, Instant expiresAt) {
    }

    private Set<String> punchExemptEmployeeIds(
            String companyId, LocalDate businessDate) {
        Instant asOf = businessDate.atStartOfDay(ZONE).toInstant();
        try {
            return Set.copyOf(nullSafe(
                    mapper.listPunchExemptEmployeeIds(companyId, asOf)));
        } catch (RuntimeException failed) {
            log.warn("workbench could not load punch exemptions", failed);
            return Set.of();
        }
    }

    private Map<String, String> departmentPaths(String companyId) {
        try {
            Map<String, String> paths =
                    reportSources.reportDepartmentPaths(companyId);
            return paths == null ? Map.of() : paths;
        } catch (RuntimeException failed) {
            log.warn("workbench could not load department paths", failed);
            return Map.of();
        }
    }

    private static String reportDepartmentName(
            String organizationId,
            String fallback,
            Map<String, String> departments) {
        if (organizationId == null || departments == null || departments.isEmpty()) {
            return fallback;
        }
        String display = departments.get(organizationId);
        return display == null || display.isBlank() ? fallback : display;
    }

    private record CompanyDayBundle(
            List<RosterRow> roster,
            Map<String, List<Instant>> punchesByEmployeeId,
            Set<String> punchExemptEmployeeIds,
            Map<String, String> departments) {
    }

    private static boolean visible(
            RealtimeAuthorization authorization, RosterRow row) {
        return authorization.companyWide()
                || Objects.equals(
                        authorization.principalEmployeeId(),
                        row.employeeId())
                || (authorization.employeeIds().contains(row.employeeId())
                        && authorization.organizationIds()
                                .contains(row.organizationId()));
    }

    private static List<Instant> punchesOn(
            Map<String, List<Instant>> punchesByEmployee,
            ScopedPerson person,
            LocalDate day) {
        List<Instant> punches = punchesByEmployee.get(person.employeeId());
        if (punches == null || punches.isEmpty()) {
            return List.of();
        }
        List<Instant> onDay = new ArrayList<>();
        for (Instant punch : punches) {
            if (day.equals(punch.atZone(ZONE).toLocalDate())) {
                onDay.add(punch);
            }
        }
        onDay.sort(Comparator.naturalOrder());
        return onDay;
    }

    private static List<ExceptionFact> classify(
            ScopedPerson person,
            LocalDate day,
            List<Instant> punches,
            boolean includeOffDutyMiss) {
        if (person.punchExempt()) {
            return List.of();
        }
        boolean weekday = day.getDayOfWeek() != DayOfWeek.SATURDAY
                && day.getDayOfWeek() != DayOfWeek.SUNDAY;
        if (punches.isEmpty()) {
            if (!weekday) {
                return List.of();
            }
            List<ExceptionFact> missing = new ArrayList<>();
            missing.add(exception(
                    person,
                    day,
                    "MISSING_ON_DUTY",
                    ExceptionSeverity.WARNING,
                    240,
                    "上班漏签"));
            if (includeOffDutyMiss) {
                missing.add(exception(
                        person,
                        day,
                        "MISSING_OFF_DUTY",
                        ExceptionSeverity.WARNING,
                        240,
                        "下班漏签"));
            }
            return List.copyOf(missing);
        }
        Instant first = punches.getFirst();
        String firstClock = CLOCK.format(first);
        if (punches.size() == 1) {
            LocalTime firstTime = first.atZone(ZONE).toLocalTime();
            boolean morning = firstTime.isBefore(LocalTime.NOON);
            if (morning && !includeOffDutyMiss) {
                return List.of();
            }
            return List.of(exception(
                    person,
                    day,
                    morning ? "MISSING_OFF_DUTY" : "MISSING_ON_DUTY",
                    ExceptionSeverity.WARNING,
                    0,
                    morning
                            ? "上班 " + firstClock + "，下班漏签"
                            : "上班漏签，下班 " + firstClock));
        }
        LocalTime firstTime = first.atZone(ZONE).toLocalTime();
        if (firstTime.isAfter(LATE_AFTER)) {
            long minutes = java.time.Duration.between(
                    LATE_AFTER, firstTime).toMinutes();
            return List.of(exception(
                    person,
                    day,
                    "LATE",
                    ExceptionSeverity.WARNING,
                    minutes,
                    "迟到 " + firstClock));
        }
        Instant last = punches.getLast();
        LocalTime lastTime = last.atZone(ZONE).toLocalTime();
        if (includeOffDutyMiss && lastTime.isBefore(LocalTime.of(17, 30))) {
            return List.of(exception(
                    person,
                    day,
                    "EARLY_DEPARTURE",
                    ExceptionSeverity.WARNING,
                    java.time.Duration.between(
                            lastTime, LocalTime.of(17, 30)).toMinutes(),
                    "早退 " + CLOCK.format(last)));
        }
        return List.of();
    }

    private static List<ExceptionFact> classifyTodayMorning(
            ScopedPerson person,
            LocalDate day,
            List<Instant> punches) {
        if (person.punchExempt()) {
            return List.of();
        }
        boolean weekday = day.getDayOfWeek() != DayOfWeek.SATURDAY
                && day.getDayOfWeek() != DayOfWeek.SUNDAY;
        if (!weekday) {
            return List.of();
        }
        if (punches.isEmpty()) {
            return List.of(exception(
                    person,
                    day,
                    "MISSING_ON_DUTY",
                    ExceptionSeverity.WARNING,
                    240,
                    "早上漏签"));
        }
        Instant first = punches.getFirst();
        LocalTime firstTime = first.atZone(ZONE).toLocalTime();
        if (firstTime.isAfter(LATE_AFTER)) {
            long minutes = java.time.Duration.between(
                    LATE_AFTER, firstTime).toMinutes();
            return List.of(exception(
                    person,
                    day,
                    "LATE",
                    ExceptionSeverity.WARNING,
                    minutes,
                    "迟到 " + CLOCK.format(first)));
        }
        return List.of();
    }

    private List<ExceptionFact> negativeLeaveFacts(
            List<ScopedPerson> people,
            LocalDate businessDate) {
        if (people.isEmpty()) {
            return List.of();
        }
        Map<String, ScopedPerson> visible = new LinkedHashMap<>();
        for (ScopedPerson person : people) {
            visible.putIfAbsent(person.employeeId(), person);
        }
        Set<String> companyIds = new HashSet<>();
        for (ScopedPerson person : people) {
            companyIds.add(person.company().companyId());
        }
        List<ExceptionFact> facts = new ArrayList<>();
        int year = businessDate.getYear();
        for (String companyId : companyIds) {
            List<DashboardWorkbenchRows.NegativeLeaveRow> rows;
            try {
                rows = nullSafe(mapper.listNegativeLeaveBalances(
                        companyId, year, businessDate));
            } catch (RuntimeException failed) {
                log.warn("workbench could not load negative leave balances", failed);
                continue;
            }
            for (DashboardWorkbenchRows.NegativeLeaveRow row : rows) {
                ScopedPerson person = visible.get(row.employeeId());
                if (person == null) {
                    continue;
                }
                facts.add(negativeLeaveFact(person, businessDate, row));
            }
        }
        return facts;
    }

    private static ExceptionFact negativeLeaveFact(
            ScopedPerson person,
            LocalDate businessDate,
            DashboardWorkbenchRows.NegativeLeaveRow row) {
        boolean timeOff = "TIME_OFF".equals(row.accountType());
        String leaveLabel = timeOff ? "调休" : "年假";
        String exceptionType = timeOff
                ? "NEGATIVE_TIME_OFF_BALANCE"
                : "NEGATIVE_ANNUAL_LEAVE_BALANCE";
        java.math.BigDecimal hours = row.balanceHours() == null
                ? java.math.BigDecimal.ZERO
                : row.balanceHours();
        java.math.BigDecimal days = hours.divide(
                java.math.BigDecimal.valueOf(8),
                2,
                java.math.RoundingMode.HALF_UP);
        long minutes = hours.abs()
                .multiply(java.math.BigDecimal.valueOf(60))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValue();
        return exception(
                person,
                businessDate,
                exceptionType,
                ExceptionSeverity.ERROR,
                minutes,
                leaveLabel + "余额 " + days.toPlainString()
                        + " 天（" + hours.toPlainString() + " 小时）");
    }

    private static ExceptionFact exception(
            ScopedPerson person,
            LocalDate day,
            String type,
            ExceptionSeverity severity,
            long minutes,
            String evidence) {
        String reference = (day + ":" + person.employeeId() + ":" + type);
        if (reference.length() > 128) {
            reference = reference.substring(0, 128);
        }
        return new ExceptionFact(
                reference,
                person.employeeId(),
                person.employeeNumber(),
                person.employeeName(),
                person.organizationId(),
                person.organizationName(),
                day,
                type,
                severity,
                ExceptionState.OPEN,
                minutes,
                evidence,
                "WORKBENCH-PUNCH:V1");
    }

    private static ExceptionItem toItem(ExceptionFact fact) {
        return new ExceptionItem(
                fact.caseId(),
                fact.employeeNumber(),
                fact.employeeName(),
                fact.organizationName(),
                fact.businessDate(),
                fact.exceptionType(),
                fact.severity().name(),
                fact.state().name(),
                fact.minutes(),
                fact.safeEvidenceSummary());
    }

    private static Comparator<ExceptionFact> exceptionOrder() {
        return Comparator
                .comparing((ExceptionFact fact) -> switch (fact.severity()) {
                    case ERROR -> 0;
                    case WARNING -> 1;
                    case INFO -> 2;
                })
                .thenComparing(ExceptionFact::employeeNumber);
    }

    private static AuthorizedScope scope(
            PrincipalHome home,
            List<CompanyOption> companies,
            List<ScopedPerson> people) {
        String label;
        ScopeType type;
        String reference;
        if (home != null && companies.size() == 1) {
            type = ScopeType.ORGANIZATION;
            reference = home.organizationId();
            label = "工号" + home.employeeNumber()
                    + " · " + home.organizationName();
        } else if (companies.size() > 1) {
            type = ScopeType.COMPANY;
            reference = home == null
                    ? companies.getFirst().companyId()
                    : home.organizationId();
            label = home == null
                    ? "多家公司授权汇总"
                    : "工号" + home.employeeNumber() + " · 多家公司授权";
        } else {
            type = ScopeType.COMPANY;
            reference = companies.getFirst().companyId();
            label = companies.getFirst().companyName();
        }
        if (label.length() > 100) {
            label = "按工号组织架构";
        }
        return new AuthorizedScope(
                type,
                reference,
                label,
                "b".repeat(64));
    }

    private static List<WorkbenchMetric> metrics(
            int roster,
            int punchedToday,
            int completeToday,
            ExceptionSummary summary) {
        String attendance = roster == 0
                ? "—"
                : punchedToday + " / " + roster;
        String exceptionRate = roster == 0
                ? "—"
                : String.format(
                        java.util.Locale.ROOT,
                        "%.1f%%",
                        summary.affectedEmployeeCount() * 100.0 / roster);
        return List.of(
                new WorkbenchMetric(
                        "attendance-rate", "今日已打卡", attendance, false),
                new WorkbenchMetric(
                        "exception-rate", "异常率", exceptionRate, false),
                new WorkbenchMetric(
                        "confirmed-work",
                        "完整打卡",
                        completeToday + " 人",
                        false),
                new WorkbenchMetric(
                        "recognized-overtime",
                        "认可加班",
                        null,
                        true),
                new WorkbenchMetric(
                        "leave", "请假", null, true),
                new WorkbenchMetric(
                        "unsettled-periods", "未月结期间", "0", false),
                new WorkbenchMetric(
                        "freshness", "数据状态", "刚刚更新", false));
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String organizationLabel(
            String organizationName, String companyName) {
        String label = organizationName + " · " + companyName;
        return label.length() <= 200 ? label : organizationName;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ScopedPerson(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            CompanyOption company,
            boolean punchExempt) {
    }

    public record Assembled(
            CompanyOption selected,
            List<CompanyOption> companies,
            DashboardSnapshot snapshot,
            List<WorkbenchMetric> metrics,
            List<TodayPunch> todayPunches) {
    }

    public record WorkbenchMetric(
            String key,
            String label,
            String displayValue,
            boolean suppressed) {
    }

    public record TodayPunch(
            String employeeNumber,
            String employeeName,
            String organizationName,
            String firstPunchAt,
            String lastPunchAt,
            long punchCount) {
    }
}
