package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.infrastructure.persistence.PunchLocationMapper;
import com.szsemicon.hr.reporting.infrastructure.map.PunchBasemapClient;
import com.szsemicon.hr.reporting.infrastructure.persistence.PunchLocationMapper.DayPunchRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.PunchLocationMapper.LocationRow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(MapBasemapProperties.class)
public class PunchLocationService {

    private static final Logger log = LoggerFactory.getLogger(PunchLocationService.class);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final AttendanceReportSourceRepository sources;
    private final PunchLocationMapper mapper;
    private final AuditService auditService;
    private final MapBasemapProperties basemap;
    private final Clock clock;
    private final PunchBasemapClient mapClient;

    public PunchLocationService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            AttendanceReportSourceRepository sources,
            PunchLocationMapper mapper,
            AuditService auditService,
            MapBasemapProperties basemap,
            Clock clock,
            PunchBasemapClient mapClient) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.sources = sources;
        this.mapper = mapper;
        this.auditService = auditService;
        this.basemap = basemap;
        this.clock = clock;
        this.mapClient = mapClient;
    }

    public DayPunchPage listDayPunches(
            String companyId, String employeeId, LocalDate businessDate) {
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ);
        if (blank(companyId) || blank(employeeId) || businessDate == null) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "公司、员工和日期必填");
        }
        Instant at = clock.instant();
        String principalId = principals.currentPrincipalId();
        RealtimeAuthorization auth = sources.resolveRealtimeAuthorization(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        companyId,
                        at)
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.FORBIDDEN, "ACCESS_DENIED", "不在授权范围内"));
        if (!auth.companyWide()
                && (auth.employeeIds() == null
                        || !auth.employeeIds().contains(employeeId))) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED", "不在授权范围内");
        }
        boolean self = isSelf(principalId, companyId, employeeId);
        var currentCapabilities = capabilities.currentCapabilities();
        boolean canViewLocation = (self && currentCapabilities.contains(CapabilityCodes.ATTENDANCE_SELF_READ))
                || (currentCapabilities.contains(CapabilityCodes.ATTENDANCE_LOCATION_READ)
                    && employeeInLocationScope(principalId, companyId, employeeId));
        return dayPunches(companyId, employeeId, businessDate, canViewLocation, !self);
    }

    public DayPunchPage listSelfDayPunches(LocalDate businessDate) {
        capabilities.require(CapabilityCodes.ATTENDANCE_SELF_READ);
        if (businessDate == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "日期必填");
        }
        var home = sources.resolvePrincipalHome(principals.currentPrincipalId(),
                        clock.instant().atZone(BUSINESS_ZONE).toLocalDate())
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无法确认本人身份"));
        return dayPunches(home.companyId(), home.employeeId(), businessDate, true, false);
    }

    private DayPunchPage dayPunches(String companyId, String employeeId, LocalDate businessDate,
            boolean canViewLocation, boolean reasonRequired) {
        Instant start = businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant end = businessDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        List<DayPunch> punches = new ArrayList<>();
        for (DayPunchRow row : nullSafe(mapper.listDayPunches(
                companyId, employeeId, start, end))) {
            String method = normalizeMethod(row.method());
            punches.add(new DayPunch(
                    row.rawFactId(),
                    row.punchedAt() == null ? null : row.punchedAt().toString(),
                    method,
                    sourceLabel(method),
                    gpsOrOutWork(method) && canViewLocation,
                    reasonRequired));
        }
        return new DayPunchPage(punches);
    }

    public LocationView viewLocation(String rawFactId, String reason) {
        String principalId = principals.currentPrincipalId();
        if (blank(rawFactId) || rawFactId.length() > 36) {
            auditService.record(principalId, "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT",
                    null, "DENIED", "invalid-fact-id");
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "打卡标识必填");
        }
        LocationRow row = mapper.findLocation(rawFactId);
        if (row == null) {
            auditService.record(principalId, "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT",
                    rawFactId, "DENIED", "not-found-or-inactive");
            throw new ApiProblemException(
                    HttpStatus.NOT_FOUND, "PUNCH_NOT_FOUND", "打卡不存在");
        }
        boolean self = isSelf(principalId, row.companyId(), row.employeeId());
        boolean locationCap = capabilities.currentCapabilities()
                .contains(CapabilityCodes.ATTENDANCE_LOCATION_READ);
        boolean selfCap = capabilities.currentCapabilities()
                .contains(CapabilityCodes.ATTENDANCE_SELF_READ);
        if (self && (selfCap || employeeInLocationScope(principalId, row.companyId(), row.employeeId()))) {
            audit(principalId, row, "ALLOWED", "self");
            return toView(row);
        }
        if (!locationCap) {
            audit(principalId, row, "DENIED", "missing-capability");
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED", "没有位置查看权限");
        }
        if (!employeeInLocationScope(principalId, row.companyId(), row.employeeId())) {
            audit(principalId, row, "DENIED", "out-of-scope");
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED", "不在授权范围内");
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < 2 || trimmed.length() > 500) {
            audit(principalId, row, "DENIED", "invalid-reason");
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "查看原因须为2至500个字符");
        }
        audit(principalId, row, "ALLOWED", trimmed);
        return toView(row);
    }

    private boolean isSelf(String principalId, String companyId, String employeeId) {
        if (blank(employeeId)) {
            return false;
        }
        Optional<AttendanceReportSourceRepository.PrincipalHome> home =
                sources.resolvePrincipalHome(
                        principalId, clock.instant().atZone(BUSINESS_ZONE).toLocalDate());
        return home.isPresent()
                && employeeId.equals(home.get().employeeId())
                && (blank(companyId) || companyId.equals(home.get().companyId()));
    }

    private boolean employeeInLocationScope(
            String principalId, String companyId, String employeeId) {
        if (blank(companyId) || blank(employeeId)) {
            return false;
        }
        return sources.resolveRealtimeAuthorization(
                        principalId,
                        CapabilityCodes.ATTENDANCE_LOCATION_READ,
                        companyId,
                        clock.instant())
                .map(auth -> auth.companyWide()
                        || (auth.employeeIds() != null
                                && auth.employeeIds().contains(employeeId)))
                .orElse(false);
    }

    private LocationView toView(LocationRow row) {
        String method = normalizeMethod(row.method());
        boolean gps = gpsOrOutWork(method);
        boolean validPoint = "VALID".equals(row.coordinateValidationStatus())
                && row.mapLongitude() != null
                && ("IDENTITY".equals(row.coordinateConversionStatus())
                    || "CONVERTED".equals(row.coordinateConversionStatus()))
                && java.util.Set.of("WGS84", "GCJ02", "BD09").contains(
                        Objects.toString(row.sourceCoordinateSystem(), ""))
                && "WGS84".equals(row.mapCoordinateSystem())
                && row.mapLatitude() != null
                && row.mapLongitude().abs().compareTo(java.math.BigDecimal.valueOf(180)) <= 0
                && row.mapLatitude().abs().compareTo(java.math.BigDecimal.valueOf(90)) <= 0;
        String mapImage = gps && validPoint && basemap.configured()
                ? mapClient.renderWgs84(row.mapLongitude(), row.mapLatitude()).orElse(null)
                : null;
        boolean mapPoint = mapImage != null;
        String unavailable;
        if (!gps) {
            unavailable = "NOT_GPS";
        } else if (!validPoint) {
            unavailable = row.coordinateValidationStatus() == null
                    ? "MISSING"
                    : row.coordinateValidationStatus();
        } else if (!basemap.configured()) {
            unavailable = "BASEMAP_UNCONFIGURED";
        } else {
            unavailable = mapPoint ? null : "BASEMAP_UNAVAILABLE";
        }
        return new LocationView(
                row.rawFactId(),
                row.punchedAt() == null ? null : row.punchedAt().toString(),
                method,
                gps ? row.locationSummary() : null,
                mapPoint,
                unavailable,
                mapPoint ? row.mapLongitude() : null,
                mapPoint ? row.mapLatitude() : null,
                mapImage);
    }

    private void audit(
            String actorId, LocationRow row, String result, String reason) {
        auditService.record(
                actorId,
                "PUNCH_LOCATION_READ",
                "RAW_ATTENDANCE_FACT",
                row.rawFactId(),
                result,
                "employeeId=" + row.employeeId() + "; reason=" + reason);
        log.info(
                "punch location view result={} factId={} employeeId={} method={}",
                result,
                row.rawFactId(),
                row.employeeId(),
                normalizeMethod(row.method()));
    }

    private static boolean gpsOrOutWork(String method) {
        return "gps".equals(method) || "out_work".equals(method);
    }

    private static String sourceLabel(String method) {
        return gpsOrOutWork(method) ? "手机" : "考勤机";
    }

    private static String normalizeMethod(String method) {
        return method == null ? "" : method.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static List<DayPunchRow> nullSafe(List<DayPunchRow> rows) {
        return rows == null ? List.of() : rows;
    }

    public record DayPunchPage(List<DayPunch> punches) {
        public DayPunchPage {
            punches = List.copyOf(Objects.requireNonNullElse(punches, List.of()));
        }
    }

    public record DayPunch(
            String rawFactId,
            String punchedAt,
            String method,
            String sourceLabel,
            boolean viewLocationAvailable,
            boolean reasonRequired) {
    }

    public record LocationView(
            String rawFactId,
            String punchedAt,
            String method,
            String locationText,
            boolean mapPointAvailable,
            String mapUnavailableReason,
            java.math.BigDecimal mapLongitude,
            java.math.BigDecimal mapLatitude,
            String mapImageDataUrl) {
    }
}
