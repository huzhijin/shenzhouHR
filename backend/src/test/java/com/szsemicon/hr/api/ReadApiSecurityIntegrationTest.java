package com.szsemicon.hr.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "SHENZHOUHR_DEV_PRINCIPAL_ID=80000000-0000-0000-0000-000000000001"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ReadApiSecurityIntegrationTest.FixedClockConfiguration.class)
class ReadApiSecurityIntegrationTest {

    private static final String DEVELOPMENT_HEADER = "X-Development-Principal";
    private static final String ALLOWED_PRINCIPAL = "80000000-0000-0000-0000-000000000001";
    private static final String PRINCIPAL_WITHOUT_CAPABILITY =
            "80000000-0000-0000-0000-000000000002";
    private static final String ORGANIZATION_SCOPED_PRINCIPAL =
            "80000000-0000-0000-0000-000000000003";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUnauthenticatedRequestsWithTheApiErrorContract() throws Exception {
        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void hidesCapabilityFailuresAsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/employees")
                        .with(user(PRINCIPAL_WITHOUT_CAPABILITY)))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));
    }

    @Test
    void developmentHeaderCannotImpersonateAnotherPrincipal() throws Exception {
        mockMvc.perform(get("/api/v1/employees")
                        .header(DEVELOPMENT_HEADER, ORGANIZATION_SCOPED_PRINCIPAL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void enforcesEmployeeScopeAndStablePaginationInTheRealMapper() throws Exception {
        mockMvc.perform(get("/api/v1/employees")
                        .header(DEVELOPMENT_HEADER, ALLOWED_PRINCIPAL)
                        .queryParam("page", "1")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Bob"));

        mockMvc.perform(get("/api/v1/employees")
                        .with(user(ORGANIZATION_SCOPED_PRINCIPAL))
                        .queryParam("page", "0")
                        .queryParam("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].displayName").value("Bob"))
                .andExpect(jsonPath("$.items[1].displayName").value("Carol"));
    }

    @Test
    void rejectsPageSizesOutsideTheDocumentedBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/employees")
                        .header(DEVELOPMENT_HEADER, ALLOWED_PRINCIPAL)
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void buildsTheVisibleOrganizationHierarchyAtTheEffectiveInstant() throws Exception {
        mockMvc.perform(get("/api/v1/organization-units")
                        .header(DEVELOPMENT_HEADER, ALLOWED_PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("神州总部"))
                .andExpect(jsonPath("$[0].children[0].name").value("制造中心"))
                .andExpect(jsonPath("$[0].children[0].children[0].name").value("封装部"));

        mockMvc.perform(get("/api/v1/organization-units")
                        .with(user(ORGANIZATION_SCOPED_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("制造中心"))
                .andExpect(jsonPath("$[0].children[0].name").value("封装部"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-20T00:00:00Z"),
                    ZoneOffset.UTC);
        }
    }
}
