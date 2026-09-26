package com.szsemicon.hr.reporting.interfaces.rest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.reporting.application.*;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.reporting.infrastructure.map.PunchBasemapClient;
import com.szsemicon.hr.reporting.infrastructure.persistence.PunchLocationMapper;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiExceptionHandler;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PunchLocationController.class, useDefaultFilters = false)
@org.springframework.test.context.ContextConfiguration(classes = PunchLocationControllerTest.Security.class)
@Import({PunchLocationController.class, PunchLocationService.class, ApiExceptionHandler.class, PunchLocationControllerTest.Security.class})
class PunchLocationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CurrentCapabilityService capabilities;
    @MockitoBean CurrentPrincipalProvider principals;
    @MockitoBean AttendanceReportSourceRepository sources;
    @MockitoBean PunchLocationMapper mapper;
    @MockitoBean AuditService audit;
    @MockitoBean PunchBasemapClient maps;

    @Configuration
    @EnableMethodSecurity
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class Security {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-15T02:00:00Z"), ZoneOffset.UTC); }
        @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }

    @BeforeEach
    void prepare() {
        when(principals.currentPrincipalId()).thenReturn("actor");
        when(mapper.findLocation("fact")).thenReturn(new PunchLocationMapper.LocationRow(
                "fact", "company", "employee", Instant.parse("2026-09-15T01:00:00Z"), "gps",
                "private address", "UNKNOWN", "UNKNOWN_SYSTEM", "NOT_APPLICABLE", null, null, null));
    }

    @Test
    void authenticatedUserWithoutLocationOrSelfCapabilityIsDeniedAndAudited() throws Exception {
        mvc.perform(get("/api/v1/checkins/fact/location").with(user("actor").authorities(List.of())))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.locationText").doesNotExist());
        verify(audit).record("actor", "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT", "fact", "DENIED", "employeeId=employee; reason=missing-capability");
    }

    @Test
    void selfOnlySessionCanUseSelfListAndLocationWithoutReason() throws Exception {
        when(capabilities.currentCapabilities()).thenReturn(Set.of("ATTENDANCE_SELF:READ"));
        when(sources.resolvePrincipalHome(anyString(), any())).thenReturn(Optional.of(
                new AttendanceReportSourceRepository.PrincipalHome("employee", "E1", "company", "公司", "org", "部门")));
        when(mapper.listDayPunches(eq("company"), eq("employee"), any(), any()))
                .thenReturn(List.of(new PunchLocationMapper.DayPunchRow("fact", Instant.now(), "gps")));
        var employee = user("actor").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ATTENDANCE_SELF:READ"));
        mvc.perform(get("/api/v1/me/attendance/day-punches").param("businessDate", "2026-09-15")
                        .param("employeeId", "coworker").with(employee))
                .andExpect(status().isOk()).andExpect(jsonPath("$.punches[0].reasonRequired").value(false));
        mvc.perform(get("/api/v1/checkins/fact/location").with(employee))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locationText").value("private address"));
        verify(audit).record("actor", "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT", "fact", "ALLOWED", "employeeId=employee; reason=self");
    }

    @Test
    void managerWithoutReasonIsDeniedAndAuditedThroughHttp() throws Exception {
        when(capabilities.currentCapabilities()).thenReturn(Set.of("ATTENDANCE_LOCATION:READ"));
        when(sources.resolveRealtimeAuthorization(eq("actor"), eq("ATTENDANCE_LOCATION:READ"), eq("company"), any()))
                .thenReturn(Optional.of(new AttendanceReportSourceRepository.RealtimeAuthorization(
                        new AuthorizedScope(ScopeType.COMPANY, "company", "公司", "a".repeat(64)),
                        "company", true, null, Set.of(), Set.of())));
        mvc.perform(get("/api/v1/checkins/fact/location").with(user("actor")))
                .andExpect(status().isBadRequest());
        verify(audit).record("actor", "PUNCH_LOCATION_READ", "RAW_ATTENDANCE_FACT", "fact", "DENIED", "employeeId=employee; reason=invalid-reason");
    }
}
