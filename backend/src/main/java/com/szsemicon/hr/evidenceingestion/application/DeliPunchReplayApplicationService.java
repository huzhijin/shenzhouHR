package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.EmployeeDeliBindingMapper;
import com.szsemicon.hr.evidenceingestion.port.AttendanceConfigurationResolverPort;
import com.szsemicon.hr.evidenceingestion.port.AttendancePeriodProtectionPort;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DeliPunchReplayApplicationService {

    private static final Logger log =
            LoggerFactory.getLogger(DeliPunchReplayApplicationService.class);
    private static final int MAX_PAGES = 10_000;
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalDate DEFAULT_FROM = LocalDate.parse("2026-08-01");
    static final LocalDate DEFAULT_TO = LocalDate.parse("2026-08-13");

    private final boolean replayEnabled;
    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final EmployeeDeliBindingMapper bindingMapper;
    private final EmployeeDeliBindingSeedService seedService;
    private final DeliPunchPageTransaction pageTransaction;
    private final List<DeliPunchSourcePort> deliSources;
    private final List<AttendanceConfigurationResolverPort> configurationResolvers;
    private final List<AttendancePeriodProtectionPort> periodProtections;
    private final AttendanceReportQueryService reportQueryService;
    private final Clock clock;

    public DeliPunchReplayApplicationService(
            @Value("${shenzhouhr.deli.replay-enabled:false}") boolean replayEnabled,
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            EmployeeDeliBindingMapper bindingMapper,
            EmployeeDeliBindingSeedService seedService,
            DeliPunchPageTransaction pageTransaction,
            List<DeliPunchSourcePort> deliSources,
            List<AttendanceConfigurationResolverPort> configurationResolvers,
            List<AttendancePeriodProtectionPort> periodProtections,
            AttendanceReportQueryService reportQueryService,
            Clock clock) {
        this.replayEnabled = replayEnabled;
        this.capabilities = capabilities;
        this.principals = principals;
        this.bindingMapper = bindingMapper;
        this.seedService = seedService;
        this.pageTransaction = pageTransaction;
        this.deliSources = List.copyOf(deliSources);
        this.configurationResolvers = List.copyOf(configurationResolvers);
        this.periodProtections = List.copyOf(periodProtections);
        this.reportQueryService = reportQueryService;
        this.clock = clock;
    }

    public record QuarantineNote(
            String sourceRecordId,
            String employeeNumber,
            String deliPersonId,
            Instant punchInstant,
            String reason) {
    }

    public record ReplayResult(
            String sourceId,
            String companyId,
            LocalDate fromDate,
            LocalDate toDate,
            int acceptedCount,
            int quarantinedCount,
            int identityReplayedCount,
            int identityMovedCount,
            EmployeeDeliBindingSeedService.SeedResult seed,
            List<QuarantineNote> stillQuarantined,
            boolean recalculated) {
    }

    public ReplayResult replay(
            String sourceId,
            LocalDate fromDate,
            LocalDate toDate,
            boolean throughToday,
            boolean seedBindings,
            boolean recalculate) {
        if (!replayEnabled) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "DELI_REPLAY_DISABLED",
                    "得力身份回放默认关闭，确认窗口后再打开 shenzhouhr.deli.replay-enabled",
                    false);
        }
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_RUN);
        try {
            return replayUnchecked(
                    sourceId, fromDate, toDate, throughToday, seedBindings, recalculate);
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (DeliPunchSourcePort.FetchException exception) {
            throw new ApiProblemException(
                    HttpStatus.BAD_GATEWAY,
                    exception.safeCode(),
                    "得力目录或打卡读取失败：" + exception.safeCode(),
                    exception.retryable());
        } catch (AttendanceSourceSyncFailure exception) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    exception.safeCode(),
                    "回放写入失败：" + exception.safeCode(),
                    false);
        } catch (RuntimeException exception) {
            log.error("Deli identity replay failed", exception);
            String detail = exception.getClass().getSimpleName();
            if (exception.getMessage() != null
                    && exception.getMessage().length() <= 180) {
                detail = detail + ": " + exception.getMessage();
            }
            throw new ApiProblemException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "DELI_IDENTITY_REPLAY_FAILED",
                    "回放失败：" + detail,
                    false);
        }
    }

    private ReplayResult replayUnchecked(
            String sourceId,
            LocalDate fromDate,
            LocalDate toDate,
            boolean throughToday,
            boolean seedBindings,
            boolean recalculate) {
        String principalId = principals.currentPrincipalId();
        String resolvedSource = sourceId == null || sourceId.isBlank()
                ? bindingMapper.findActiveDeliSourceId()
                : sourceId;
        if (resolvedSource == null) {
            throw new ApiProblemException(
                    HttpStatus.NOT_FOUND,
                    "DELI_ACTIVE_SOURCE_NOT_FOUND",
                    "没有可用的得力数据源",
                    false);
        }
        LocalDate from = fromDate == null ? DEFAULT_FROM : fromDate;
        LocalDate to = throughToday
                ? clock.instant().atZone(BUSINESS_ZONE).toLocalDate()
                : (toDate == null ? DEFAULT_TO : toDate);
        Instant windowStart = from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant windowEndExclusive =
                to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        DeliPunchSourcePort source = productionSource();
        List<DeliPunchSourcePort.EmployeeDirectoryPerson> directoryPeople;
        try {
            directoryPeople = source.fetchEmployeeDirectoryPeople(resolvedSource);
        } catch (DeliPunchSourcePort.FetchException exception) {
            log.warn(
                    "Deli directory people fetch failed, falling back to empno map code={}",
                    exception.safeCode());
            Map<String, String> fallback = source.fetchEmployeeDirectory(
                    resolvedSource);
            directoryPeople = fallback.entrySet().stream()
                    .map(entry -> new DeliPunchSourcePort.EmployeeDirectoryPerson(
                            entry.getKey(), entry.getValue(), null))
                    .toList();
        }
        Map<String, String> directory = new java.util.LinkedHashMap<>();
        for (var person : directoryPeople) {
            if (person.userId() != null
                    && person.employeeNum() != null
                    && !person.employeeNum().isBlank()) {
                directory.put(person.userId(), person.employeeNum());
            }
        }
        EmployeeDeliBindingSeedService.SeedResult seed = seedBindings
                ? seedService.seedPeople(resolvedSource, directoryPeople)
                : new EmployeeDeliBindingSeedService.SeedResult(
                        List.of(), List.of(), List.of());
        var job = new AttendanceSourceSyncModels.SourceJobStart(
                UUID.randomUUID().toString(),
                resolvedSource,
                Objects.requireNonNull(
                        bindingMapper.findActiveDeliCompanyId(resolvedSource),
                        "companyId"),
                bindingMapper.findActiveDeliDisplayName(resolvedSource),
                bindingMapper.findActiveDeliSecretName(resolvedSource),
                coalesce(
                        bindingMapper.findActiveDeliTimeZone(resolvedSource),
                        "Asia/Shanghai"),
                coalesce(bindingMapper.findActiveDeliPageSize(resolvedSource), 500),
                60,
                1,
                "0");
        var configurationResolver = configurationResolvers.getFirst();
        var periodProtection = periodProtections.getFirst();
        int accepted = 0;
        int quarantined = 0;
        int replayed = 0;
        int moved = 0;
        List<QuarantineNote> stillQuarantined = new ArrayList<>();
        StreamCounts checkin = replayStream(
                source,
                job,
                principalId,
                directory,
                DeliPunchSourcePort.FetchSettings.MODULE_CHECKIN,
                windowStart,
                windowEndExclusive,
                configurationResolver,
                periodProtection,
                stillQuarantined);
        accepted += checkin.accepted;
        quarantined += checkin.quarantined;
        replayed += checkin.replayed;
        moved += checkin.moved;
        StreamCounts kq = replayStream(
                source,
                job,
                principalId,
                directory,
                DeliPunchSourcePort.FetchSettings.MODULE_KQ,
                windowStart,
                windowEndExclusive,
                configurationResolver,
                periodProtection,
                stillQuarantined);
        accepted += kq.accepted;
        quarantined += kq.quarantined;
        replayed += kq.replayed;
        moved += kq.moved;
        boolean didRecalc = false;
        if (recalculate && moved + replayed > 0) {
            capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
            Set<YearMonth> months = new HashSet<>();
            months.add(YearMonth.from(from));
            months.add(YearMonth.from(to));
            for (YearMonth month : months) {
                reportQueryService.recalculate(month, job.companyId());
            }
            didRecalc = true;
        }
        for (QuarantineNote note : stillQuarantined) {
            log.warn(
                    "Deli identity replay still quarantined sourceRecordId={} empno={} personId={} reason={} at={}",
                    note.sourceRecordId(),
                    note.employeeNumber(),
                    note.deliPersonId(),
                    note.reason(),
                    note.punchInstant());
        }
        return new ReplayResult(
                resolvedSource,
                job.companyId(),
                from,
                to,
                accepted,
                quarantined,
                replayed,
                moved,
                seed,
                List.copyOf(stillQuarantined),
                didRecalc);
    }

    private StreamCounts replayStream(
            DeliPunchSourcePort source,
            AttendanceSourceSyncModels.SourceJobStart job,
            String principalId,
            Map<String, String> directory,
            String apiModule,
            Instant windowStart,
            Instant windowEndExclusive,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection,
            List<QuarantineNote> stillQuarantined) {
        var settings = new DeliPunchSourcePort.FetchSettings(
                job.pageSize(),
                ZoneId.of(job.sourceTimeZone()),
                directory,
                apiModule,
                true);
        String cursor = "0";
        Set<String> seen = new HashSet<>();
        seen.add(cursor);
        int pageNumber = 0;
        int accepted = 0;
        int quarantined = 0;
        int replayed = 0;
        int moved = 0;
        while (pageNumber < MAX_PAGES) {
            var page = source.fetchPage(job.sourceId(), cursor, settings);
            if (page.records().isEmpty()
                    && page.nextCursor().equals(page.inputCursor())) {
                break;
            }
            List<DeliPunchSourcePort.DeliPunchRecord> inWindow = new ArrayList<>();
            boolean afterWindow = false;
            for (var record : page.records()) {
                Instant at = record.punchInstant();
                if (!at.isBefore(windowEndExclusive)) {
                    afterWindow = true;
                } else if (!at.isBefore(windowStart)) {
                    inWindow.add(record);
                }
            }
            if (!inWindow.isEmpty()) {
                pageNumber++;
                var toCommit = new DeliPunchSourcePort.DeliPage(
                        inWindow,
                        page.inputCursor(),
                        page.nextCursor(),
                        page.pageDigest());
                var outcome = pageTransaction.commitReplayPage(
                        job,
                        principalId,
                        "REPLAY:" + job.jobId(),
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        pageNumber,
                        toCommit,
                        configurationResolver,
                        periodProtection,
                        DeliPunchSourcePort.FetchSettings.MODULE_KQ.equals(
                                apiModule));
                accepted += outcome.acceptedCount();
                quarantined += outcome.quarantinedCount();
                replayed += outcome.identityReplayedCount();
                moved += outcome.identityMovedCount();
                for (var note : outcome.stillQuarantined()) {
                    if (stillQuarantined.size() < 500) {
                        stillQuarantined.add(new QuarantineNote(
                                note.sourceRecordId(),
                                note.employeeNumber(),
                                note.deliPersonId(),
                                note.punchInstant(),
                                note.reason()));
                    }
                }
            }
            if (afterWindow && inWindow.isEmpty()) {
                break;
            }
            if (page.nextCursor() == null
                    || page.nextCursor().equals(cursor)
                    || !seen.add(page.nextCursor())) {
                break;
            }
            cursor = page.nextCursor();
        }
        return new StreamCounts(accepted, quarantined, replayed, moved);
    }

    private DeliPunchSourcePort productionSource() {
        List<DeliPunchSourcePort> production = deliSources.stream()
                .filter(DeliPunchSourcePort::productionIntegration)
                .toList();
        if (production.size() != 1) {
            throw new ApiProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DELI_SOURCE_UNAVAILABLE",
                    "得力生产适配器未就绪",
                    false);
        }
        return production.getFirst();
    }

    private static String coalesce(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int coalesce(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private record StreamCounts(
            int accepted, int quarantined, int replayed, int moved) {
    }
}
