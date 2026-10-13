package com.szsemicon.hr.developmentlog.interfaces.rest;

import com.szsemicon.hr.developmentlog.application.DevelopmentLogQueryService;
import com.szsemicon.hr.shared.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DevelopmentLogController.class, useDefaultFilters = false)
@org.springframework.test.context.ContextConfiguration(classes = DevelopmentLogControllerTest.Security.class)
@Import({DevelopmentLogController.class, ApiExceptionHandler.class, DevelopmentLogControllerTest.Security.class})
class DevelopmentLogControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DevelopmentLogQueryService queryService;

    @Configuration
    @EnableMethodSecurity
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class Security {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }

    @Test
    void returnsEmptyListWhenNoDeployments() throws Exception {
        when(queryService.listDeployments(any())).thenReturn(List.of());

        mvc.perform(get("/api/v1/access/development-log").with(user("test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void returnsDeploymentList() throws Exception {
        var entry = new DevelopmentLogQueryService.DeploymentEntry(
                "deploy-1",
                Instant.parse("2026-10-12T10:00:00Z"),
                "v1.0.0",
                "a".repeat(64),
                "Initial Release",
                "First deployment",
                "- Feature A",
                true,
                null,
                null,
                null
        );

        when(queryService.listDeployments(any())).thenReturn(List.of(entry));

        mvc.perform(get("/api/v1/access/development-log").with(user("test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deploymentId").value("deploy-1"))
                .andExpect(jsonPath("$[0].version").value("v1.0.0"))
                .andExpect(jsonPath("$[0].title").value("Initial Release"))
                .andExpect(jsonPath("$[0].isCurrent").value(true));
    }

    @Test
    void returnsForbiddenWhenNotAuthenticated() throws Exception {
        mvc.perform(get("/api/v1/access/development-log"))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportsSearchParameter() throws Exception {
        when(queryService.listDeployments("bugfix")).thenReturn(List.of());

        mvc.perform(get("/api/v1/access/development-log").param("search", "bugfix").with(user("test")))
                .andExpect(status().isOk());
    }
}
