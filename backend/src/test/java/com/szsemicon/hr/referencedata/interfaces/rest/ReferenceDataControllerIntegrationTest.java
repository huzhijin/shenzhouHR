package com.szsemicon.hr.referencedata.interfaces.rest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReferenceDataControllerIntegrationTest {

    private static final String MANAGEMENT_PRINCIPAL =
            "80000000-0000-0000-0000-000000000001";
    private static final String PRINCIPAL_WITHOUT_MANAGEMENT_READ =
            "80000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void addInactiveCompany() {
        jdbc.update(
                """
                INSERT INTO company (
                    company_id, code, name, status, created_at
                ) VALUES (?, ?, ?, 'INACTIVE', CURRENT_TIMESTAMP)
                """,
                "30000000-0000-0000-0000-000000000099",
                "WAVE2-LE-099",
                "停用公司");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/reference-data/companies"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code")
                        .value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsAuthenticatedPrincipalWithoutManagementReadCapability()
            throws Exception {
        mockMvc.perform(get("/api/v1/reference-data/companies")
                        .with(user(PRINCIPAL_WITHOUT_MANAGEMENT_READ)))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void returnsOnlyActiveCompaniesForManagementReader() throws Exception {
        mockMvc.perform(get("/api/v1/reference-data/companies")
                        .with(user(MANAGEMENT_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].companyId")
                        .value("30000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$[0].code").value("WAVE2-LE-001"))
                .andExpect(jsonPath("$[0].name")
                        .value("WAVE-2 合成公司一"))
                .andExpect(jsonPath("$[1].companyId")
                        .value("30000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$[1].code").value("WAVE2-LE-002"))
                .andExpect(jsonPath("$[1].name")
                        .value("WAVE-2 合成公司二"))
                .andExpect(jsonPath("$[0].status").doesNotExist());
    }
}
