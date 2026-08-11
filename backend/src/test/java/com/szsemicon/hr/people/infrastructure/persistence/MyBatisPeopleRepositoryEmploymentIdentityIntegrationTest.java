package com.szsemicon.hr.people.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.people.domain.PeopleModels.EmploymentPeriod;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
class MyBatisPeopleRepositoryEmploymentIdentityIntegrationTest {

    private static final String EMPLOYEE_ID =
            "b0000000-0000-0000-0000-000000000001";
    private static final String ORGANIZATION_ID =
            "40000000-0000-0000-0000-000000000002";

    @Autowired
    private PeopleRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional
    void createsStableIdentityBeforeTheFirstEmploymentVersion() {
        String periodId = "f1000000-0000-0000-0000-000000000001";
        String assignmentId = "f2000000-0000-0000-0000-000000000001";

        repository.saveEmploymentPeriodVersion(
                period(periodId, assignmentId, 0),
                true);

        assertThat(jdbc.queryForMap(
                """
                SELECT employee_id, company_id
                FROM employment_period_identity
                WHERE employment_period_id = ?
                """,
                periodId))
                .containsEntry("employee_id", EMPLOYEE_ID)
                .containsEntry(
                        "company_id",
                        "30000000-0000-0000-0000-000000000001");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM employment_assignment
                WHERE assignment_id = ? AND employment_period_id = ?
                """,
                Long.class,
                assignmentId,
                periodId)).isEqualTo(1L);
    }

    @Test
    @Transactional
    void addingASecondVersionDoesNotCreateASecondStableIdentity() {
        String periodId = "c0000000-0000-0000-0000-000000000001";

        repository.closeEmploymentPeriodVersion(periodId, 0);
        repository.saveEmploymentPeriodVersion(
                period(
                        periodId,
                        "f2000000-0000-0000-0000-000000000002",
                        1),
                false);

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM employment_period_identity
                WHERE employment_period_id = ?
                """,
                Long.class,
                periodId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM employment_assignment
                WHERE employment_period_id = ?
                """,
                Long.class,
                periodId)).isEqualTo(2L);
    }

    @Test
    void rollsBackTheStableIdentityWhenTheAssignmentInsertFails() {
        String periodId = "f1000000-0000-0000-0000-000000000003";

        assertThatThrownBy(() -> repository.saveEmploymentPeriodVersion(
                        period(
                                periodId,
                                "c0000000-0000-0000-0000-000000000001",
                                0),
                        true))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM employment_period_identity
                WHERE employment_period_id = ?
                """,
                Long.class,
                periodId)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM employment_assignment
                WHERE employment_period_id = ?
                """,
                Long.class,
                periodId)).isZero();
    }

    private static EmploymentPeriod period(
            String periodId,
            String assignmentId,
            long rowVersion) {
        return new EmploymentPeriod(
                periodId,
                assignmentId,
                EMPLOYEE_ID,
                ORGANIZATION_ID,
                null,
                LocalDate.of(2026, 8, 1),
                null,
                null,
                "ACTIVE",
                null,
                rowVersion,
                "synthetic employment identity integration test",
                null,
                Instant.parse("2026-08-11T00:00:00Z"));
    }
}
