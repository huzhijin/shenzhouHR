package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.*;
import com.szsemicon.hr.reporting.infrastructure.map.PunchBasemapClient;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.reporting.infrastructure.persistence.PunchLocationMapper;
import com.szsemicon.hr.reporting.infrastructure.persistence.PunchLocationMapper.DayPunchRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.PunchLocationMapper.LocationRow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PunchLocationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
    private static final String PRINCIPAL = "principal-1";
    private static final String COMPANY = "41000000-0000-0000-0000-000000000003";
    private static final String EMPLOYEE = "emp-dalian-1";
    private static final String FACT = "raw-gps-1";

    @Mock
    private CurrentCapabilityService capabilities;
    @Mock
    private CurrentPrincipalProvider principals;
    @Mock
    private AttendanceReportSourceRepository sources;
    @Mock
    private PunchLocationMapper mapper;
    @Mock
    private AuditService auditService;

    @Mock
    private PunchBasemapClient mapClient;

    private final MapBasemapProperties basemap = new MapBasemapProperties();
    private PunchLocationService service;

    @BeforeEach
    void setUp() {
        service = new PunchLocationService(
                capabilities,
                principals,
                sources,
                mapper,
                auditService,
                basemap,
                Clock.fixed(NOW, ZoneOffset.UTC), mapClient);
        org.mockito.Mockito.lenient()
                .when(principals.currentPrincipalId())
                .thenReturn(PRINCIPAL);
        org.mockito.Mockito.lenient()
                .when(sources.resolvePrincipalHome(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void dayPunchesLabelGpsAsPhoneAndHideLocationWithoutCapability() {
        when(sources.resolveRealtimeAuthorization(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        COMPANY,
                        NOW))
                .thenReturn(Optional.of(companyWide()));
        when(capabilities.currentCapabilities())
                .thenReturn(Set.of(CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ));
        Instant punchAt = Instant.parse("2026-09-15T00:32:00Z");
        when(mapper.listDayPunches(
                        COMPANY,
                        EMPLOYEE,
                        LocalDate.parse("2026-09-15")
                                .atStartOfDay(PunchLocationService.BUSINESS_ZONE)
                                .toInstant(),
                        LocalDate.parse("2026-09-16")
                                .atStartOfDay(PunchLocationService.BUSINESS_ZONE)
                                .toInstant()))
                .thenReturn(List.of(
                        new DayPunchRow(FACT, punchAt, "gps"),
                        new DayPunchRow("raw-fp-1", punchAt.plusSeconds(3600), "fp")));

        var page = service.listDayPunches(
                COMPANY, EMPLOYEE, LocalDate.parse("2026-09-15"));

        assertThat(page.punches()).hasSize(2);
        assertThat(page.punches().getFirst().sourceLabel()).isEqualTo("手机");
        assertThat(page.punches().getFirst().viewLocationAvailable()).isFalse();
        assertThat(page.punches().get(1).sourceLabel()).isEqualTo("考勤机");
        assertThat(page.punches().getFirst().punchedAt()).doesNotContain("121.");
    }

    @Test
    void dayPunchesShowViewLocationWhenManagerHasLocationCapability() {
        when(sources.resolveRealtimeAuthorization(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        COMPANY,
                        NOW))
                .thenReturn(Optional.of(companyWide()));
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                CapabilityCodes.ATTENDANCE_LOCATION_READ));
        when(sources.resolveRealtimeAuthorization(PRINCIPAL, CapabilityCodes.ATTENDANCE_LOCATION_READ,
                COMPANY, NOW)).thenReturn(Optional.of(companyWide()));
        when(mapper.listDayPunches(
                        org.mockito.ArgumentMatchers.eq(COMPANY),
                        org.mockito.ArgumentMatchers.eq(EMPLOYEE),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new DayPunchRow(FACT, Instant.parse("2026-09-15T00:32:00Z"), "gps")));

        var page = service.listDayPunches(
                COMPANY, EMPLOYEE, LocalDate.parse("2026-09-15"));

        assertThat(page.punches().getFirst().viewLocationAvailable()).isTrue();
    }

    @Test
    void unknownSystemReturnsAddressWithoutMapPoint() {
        when(capabilities.currentCapabilities())
                .thenReturn(Set.of(CapabilityCodes.ATTENDANCE_LOCATION_READ));
        when(sources.resolveRealtimeAuthorization(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_LOCATION_READ,
                        COMPANY,
                        NOW))
                .thenReturn(Optional.of(companyWide()));
        when(mapper.findLocation(FACT)).thenReturn(gpsRow("UNKNOWN_SYSTEM", null, null));

        var view = service.viewLocation(FACT, "核对大连手机打卡");

        assertThat(view.locationText()).isEqualTo("大连演示地址");
        assertThat(view.mapPointAvailable()).isFalse();
        assertThat(view.mapUnavailableReason()).isEqualTo("UNKNOWN_SYSTEM");
        assertThat(view.mapLongitude()).isNull();
        assertThat(view.mapLatitude()).isNull();
        verify(auditService).record(
                PRINCIPAL,
                "PUNCH_LOCATION_READ",
                "RAW_ATTENDANCE_FACT",
                FACT,
                "ALLOWED", "employeeId=emp-dalian-1; reason=核对大连手机打卡");
    }

    @Test
    void managerWithoutLocationCapabilityIsDenied() {
        when(capabilities.currentCapabilities())
                .thenReturn(Set.of(CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ));
        when(mapper.findLocation(FACT)).thenReturn(gpsRow("UNKNOWN_SYSTEM", null, null));
        when(sources.resolvePrincipalHome(
                        PRINCIPAL, LocalDate.parse("2026-09-15")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewLocation(FACT, "核对"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(ex -> ((ApiProblemException) ex).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(auditService).record(
                PRINCIPAL,
                "PUNCH_LOCATION_READ",
                "RAW_ATTENDANCE_FACT",
                FACT,
                "DENIED", "employeeId=emp-dalian-1; reason=missing-capability");
    }

    @Test
    void employeeCannotViewCoworkerLocation() {
        when(capabilities.currentCapabilities())
                .thenReturn(Set.of(CapabilityCodes.ATTENDANCE_SELF_READ));
        when(mapper.findLocation(FACT)).thenReturn(gpsRow("UNKNOWN_SYSTEM", null, null));
        when(sources.resolvePrincipalHome(
                        PRINCIPAL, LocalDate.parse("2026-09-15")))
                .thenReturn(Optional.of(new AttendanceReportSourceRepository.PrincipalHome(
                        "emp-other",
                        "SZST0001",
                        COMPANY,
                        "江苏神州",
                        "org-1",
                        "总部")));

        assertThatThrownBy(() -> service.viewLocation(FACT, null))
                .isInstanceOf(ApiProblemException.class)
                .extracting(ex -> ((ApiProblemException) ex).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reportScopeCannotExpandLocationScope() {
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_LOCATION_READ, CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ));
        when(mapper.findLocation(FACT)).thenReturn(gpsRow("UNKNOWN_SYSTEM", null, null));
        when(sources.resolveRealtimeAuthorization(PRINCIPAL, CapabilityCodes.ATTENDANCE_LOCATION_READ,
                COMPANY, NOW)).thenReturn(Optional.of(new RealtimeAuthorization(
                        new AuthorizedScope(ScopeType.SELF, "self", "本人", "a".repeat(64)), COMPANY, false, "self", Set.of("self"), Set.of())));
        assertThatThrownBy(() -> service.viewLocation(FACT, "核对"))
                .isInstanceOf(ApiProblemException.class);
        verify(sources, never()).resolveRealtimeAuthorization(PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ, COMPANY, NOW);
        verify(auditService).record(PRINCIPAL, "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT",
                FACT, "DENIED", "employeeId=emp-dalian-1; reason=out-of-scope");
    }

    @Test
    void selfCanListAndViewWithoutReportOrLocationCapabilityAndWithoutReason() {
        when(capabilities.currentCapabilities()).thenReturn(Set.of(CapabilityCodes.ATTENDANCE_SELF_READ));
        when(sources.resolvePrincipalHome(PRINCIPAL, LocalDate.parse("2026-09-15")))
                .thenReturn(Optional.of(new AttendanceReportSourceRepository.PrincipalHome(
                        EMPLOYEE, "E1", COMPANY, "公司", "org", "部门")));
        when(mapper.listDayPunches(eq(COMPANY), eq(EMPLOYEE), any(), any()))
                .thenReturn(List.of(new DayPunchRow(FACT, NOW, "gps")));
        when(mapper.findLocation(FACT)).thenReturn(gpsRow("UNKNOWN_SYSTEM", null, null));
        var punches = service.listSelfDayPunches(LocalDate.parse("2026-09-15")).punches();
        assertThat(punches.getFirst().viewLocationAvailable()).isTrue();
        assertThat(punches.getFirst().reasonRequired()).isFalse();
        assertThat(service.viewLocation(FACT, null).locationText()).isEqualTo("大连演示地址");
        verify(capabilities).require(CapabilityCodes.ATTENDANCE_SELF_READ);
        verify(auditService).record(PRINCIPAL, "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT",
                FACT, "ALLOWED", "employeeId=emp-dalian-1; reason=self");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "字"})
    void missingOrShortReasonIsAudited(String reason) {
        manager();
        when(mapper.findLocation(FACT)).thenReturn(gpsRow("UNKNOWN_SYSTEM", null, null));
        assertThatThrownBy(() -> service.viewLocation(FACT, reason))
                .isInstanceOf(ApiProblemException.class);
        verify(auditService).record(PRINCIPAL, "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT",
                FACT, "DENIED", "employeeId=emp-dalian-1; reason=invalid-reason");
        verifyNoInteractions(mapClient);
    }

    @Test
    void fullLengthReasonIsRetainedAndOverlongReasonIsRejected() {
        manager();
        when(mapper.findLocation(FACT)).thenReturn(gpsRow("UNKNOWN_SYSTEM", null, null));
        String reason = "核".repeat(500);
        service.viewLocation(FACT, reason);
        verify(auditService).record(PRINCIPAL, "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT",
                FACT, "ALLOWED", "employeeId=" + EMPLOYEE + "; reason=" + reason);
        assertThatThrownBy(() -> service.viewLocation(FACT, reason + "对"))
                .isInstanceOf(ApiProblemException.class);
        verify(auditService).record(PRINCIPAL, "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT",
                FACT, "DENIED", "employeeId=" + EMPLOYEE + "; reason=invalid-reason");
    }

    @Test
    void missingOrInactiveFactIsAudited() {
        assertThatThrownBy(() -> service.viewLocation(FACT, "核对"))
                .isInstanceOf(ApiProblemException.class);
        verify(auditService).record(PRINCIPAL, "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT",
                FACT, "DENIED", "not-found-or-inactive");
    }

    @Test
    void validConvertedPointRendersMapOnlyWithConfiguredProvider() {
        manager();
        when(mapper.findLocation(FACT)).thenReturn(validRow("WGS84", "CONVERTED", "WGS84"));
        assertThat(service.viewLocation(FACT, "核对").mapUnavailableReason())
                .isEqualTo("BASEMAP_UNCONFIGURED");
        verifyNoInteractions(mapClient);
        basemap.setBasemapProvider("AMAP");
        basemap.setBasemapKey("synthetic-key");
        when(mapClient.renderWgs84(any(), any())).thenReturn(Optional.of("data:image/png;base64,test"));
        var view = service.viewLocation(FACT, "核对");
        assertThat(view.mapPointAvailable()).isTrue();
        assertThat(view.mapImageDataUrl()).startsWith("data:image/png;base64,");
    }

    @Test
    void failedMapFetchFallsBackToAddressWithoutCoordinates() {
        manager();
        basemap.setBasemapProvider("AMAP");
        basemap.setBasemapKey("synthetic-key");
        when(mapper.findLocation(FACT)).thenReturn(validRow("WGS84", "IDENTITY", "WGS84"));
        when(mapClient.renderWgs84(any(), any())).thenReturn(Optional.empty());
        var view = service.viewLocation(FACT, "核对");
        assertThat(view.mapPointAvailable()).isFalse();
        assertThat(view.locationText()).isEqualTo("大连演示地址");
        assertThat(view.mapLongitude()).isNull();
        assertThat(view.mapImageDataUrl()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"UNKNOWN,IDENTITY,WGS84", "WGS84,FAILED,WGS84", "WGS84,IDENTITY,GCJ02"})
    void invalidCoordinateProvenanceNeverCallsProvider(String source, String conversion, String mapSystem) {
        manager();
        basemap.setBasemapProvider("AMAP");
        basemap.setBasemapKey("synthetic-key");
        when(mapper.findLocation(FACT)).thenReturn(validRow(source, conversion, mapSystem));
        assertThat(service.viewLocation(FACT, "核对").mapPointAvailable()).isFalse();
        verifyNoInteractions(mapClient);
    }

    private void manager() {
        when(capabilities.currentCapabilities()).thenReturn(Set.of(CapabilityCodes.ATTENDANCE_LOCATION_READ));
        when(sources.resolveRealtimeAuthorization(PRINCIPAL, CapabilityCodes.ATTENDANCE_LOCATION_READ,
                COMPANY, NOW)).thenReturn(Optional.of(companyWide()));
    }

    private static LocationRow validRow(String source, String conversion, String mapSystem) {
        return new LocationRow(FACT, COMPANY, EMPLOYEE, NOW, "gps", "大连演示地址", source,
                "VALID", conversion, new java.math.BigDecimal("121.614"),
                new java.math.BigDecimal("38.914"), mapSystem);
    }

    private static RealtimeAuthorization companyWide() {
        return new RealtimeAuthorization(
                new AuthorizedScope(
                        ScopeType.COMPANY, COMPANY, "公司", "a".repeat(64)),
                COMPANY,
                true,
                null,
                Set.of(),
                Set.of());
    }

    private static LocationRow gpsRow(
            String validation, java.math.BigDecimal lon, java.math.BigDecimal lat) {
        return new LocationRow(
                FACT,
                COMPANY,
                EMPLOYEE,
                Instant.parse("2026-09-15T00:32:00Z"),
                "gps",
                "大连演示地址",
                "UNKNOWN",
                validation,
                "NOT_APPLICABLE",
                lon,
                lat, null);
    }
}
