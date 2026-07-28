package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "SHENZHOUHR_DEV_PRINCIPAL_ID=81000000-0000-0000-0000-000000000001")
class Wave3RuntimeHttpAcceptanceTest {

    private static final String PRINCIPAL_ID =
            "81000000-0000-0000-0000-000000000001";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedRuntimePrincipal() {
        jdbc.update(
                "INSERT INTO auth_principal (principal_id, status) VALUES (?, 'ACTIVE')",
                PRINCIPAL_ID);
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, NULL)
                """,
                "a1000000-0000-0000-0000-000000000091",
                PRINCIPAL_ID,
                "11000000-0000-0000-0000-000000000001",
                "90000000-0000-0000-0000-000000000001",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void probesActualHttpSocketWithVersionedPathsAndWave2Regression() throws Exception {
        HttpResponse<String> unauthenticated =
                get("/api/v1/attendance-setup/locations?page=0&size=1", false);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.body()).contains("AUTHENTICATION_REQUIRED");

        HttpResponse<String> locations =
                get("/api/v1/attendance-setup/locations?page=0&size=1", true);
        assertThat(locations.statusCode()).isEqualTo(200);
        assertThat(locations.headers().firstValue("X-Correlation-ID")).isPresent();

        HttpResponse<String> policyCatalog =
                get("/api/v1/attendance-setup/policy-catalog", true);
        assertThat(policyCatalog.statusCode()).isEqualTo(200);
        assertThat(policyCatalog.body())
                .contains("MEAL_DEDUCTION")
                .contains("LATE_GRACE")
                .contains("MONTHLY_LATE_EXEMPTION");

        HttpResponse<String> wrongPrefix =
                get("/api/attendance-setup/locations?page=0&size=1", true);
        assertThat(wrongPrefix.statusCode()).isEqualTo(404);

        HttpResponse<String> wave2Employees =
                get("/api/v1/employees?page=0&size=1", true);
        assertThat(wave2Employees.statusCode()).isEqualTo(200);
        assertThat(wave2Employees.headers().firstValue("X-Correlation-ID")).isPresent();
    }

    private HttpResponse<String> get(String path, boolean authenticated)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (authenticated) {
            request.header("X-Development-Principal", PRINCIPAL_ID);
        }
        return HttpClient.newHttpClient().send(
                request.build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
