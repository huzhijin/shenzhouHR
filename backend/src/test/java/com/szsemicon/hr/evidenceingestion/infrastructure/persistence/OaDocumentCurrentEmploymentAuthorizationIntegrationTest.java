package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OaDocumentCurrentEmploymentAuthorizationIntegrationTest {

    private static final String CAPABILITY = "MASTER_DATA:READ";
    private static final String SOURCE_ID =
            "aa000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE_ID =
            "b0000000-0000-0000-0000-000000000002";
    private static final String FORMER_DEPARTMENT_PRINCIPAL =
            "80000000-0000-0000-0000-000000000003";
    private static final String CURRENT_DEPARTMENT_PRINCIPAL =
            "80000000-0000-0000-0000-000000000098";

    @Autowired
    private AttendanceSourceReadMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createMinimalOaReadProjection() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_source (
                    attendance_source_id VARCHAR(36) PRIMARY KEY,
                    company_id VARCHAR(36) NOT NULL,
                    source_type VARCHAR(32) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS normalized_attendance_record (
                    normalized_attendance_record_id VARCHAR(36) PRIMARY KEY,
                    interval_start TIMESTAMP NOT NULL,
                    interval_end TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS employee_match_decision (
                    normalized_attendance_record_id VARCHAR(36) PRIMARY KEY,
                    match_status VARCHAR(32) NOT NULL,
                    employee_id VARCHAR(36),
                    employment_period_id VARCHAR(36)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oa_attendance_document (
                    oa_attendance_document_id VARCHAR(36) PRIMARY KEY,
                    attendance_source_id VARCHAR(36) NOT NULL,
                    normalized_attendance_record_id VARCHAR(36) NOT NULL,
                    source_business_key VARCHAR(128) NOT NULL,
                    source_version VARCHAR(64) NOT NULL,
                    document_type VARCHAR(64) NOT NULL,
                    source_status VARCHAR(32) NOT NULL,
                    knowledge_rank BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """);
    }

    @Test
    void transferRevokesFormerDepartmentForBothCountAndRows() {
        seedDocumentAndTransfer();
        seedCurrentDepartmentPrincipal();

        Instant beforeTransfer =
                Instant.parse("2026-06-20T00:00:00Z");
        assertThat(mapper.countOaDocuments(
                        FORMER_DEPARTMENT_PRINCIPAL,
                        CAPABILITY,
                        SOURCE_ID,
                        beforeTransfer))
                .isOne();
        assertThat(mapper.listOaDocuments(
                        FORMER_DEPARTMENT_PRINCIPAL,
                        CAPABILITY,
                        SOURCE_ID,
                        beforeTransfer,
                        20,
                        0))
                .hasSize(1);

        Instant afterTransfer =
                Instant.parse("2026-07-20T00:00:00Z");
        assertThat(mapper.countOaDocuments(
                        FORMER_DEPARTMENT_PRINCIPAL,
                        CAPABILITY,
                        SOURCE_ID,
                        afterTransfer))
                .isZero();
        assertThat(mapper.listOaDocuments(
                        FORMER_DEPARTMENT_PRINCIPAL,
                        CAPABILITY,
                        SOURCE_ID,
                        afterTransfer,
                        20,
                        0))
                .isEmpty();

        assertThat(mapper.countOaDocuments(
                        CURRENT_DEPARTMENT_PRINCIPAL,
                        CAPABILITY,
                        SOURCE_ID,
                        afterTransfer))
                .isOne();
        assertThat(mapper.listOaDocuments(
                        CURRENT_DEPARTMENT_PRINCIPAL,
                        CAPABILITY,
                        SOURCE_ID,
                        afterTransfer,
                        20,
                        0))
                .hasSize(1);
    }

    private void seedDocumentAndTransfer() {
        jdbc.update(
                """
                INSERT INTO attendance_source (
                    attendance_source_id, company_id, source_type
                ) VALUES (
                    ?, '30000000-0000-0000-0000-000000000001',
                    'OA_ATTENDANCE'
                )
                """,
                SOURCE_ID);
        jdbc.update(
                """
                INSERT INTO normalized_attendance_record (
                    normalized_attendance_record_id,
                    interval_start, interval_end
                ) VALUES (
                    'ab000000-0000-0000-0000-000000000001',
                    TIMESTAMP '2026-06-15 08:00:00',
                    TIMESTAMP '2026-06-15 18:00:00'
                )
                """);
        jdbc.update(
                """
                INSERT INTO employee_match_decision (
                    normalized_attendance_record_id, match_status,
                    employee_id, employment_period_id
                ) VALUES (
                    'ab000000-0000-0000-0000-000000000001',
                    'MATCHED', ?,
                    'c0000000-0000-0000-0000-000000000002'
                )
                """,
                EMPLOYEE_ID);
        jdbc.update(
                """
                INSERT INTO oa_attendance_document (
                    oa_attendance_document_id, attendance_source_id,
                    normalized_attendance_record_id, source_business_key,
                    source_version, document_type, source_status,
                    knowledge_rank, created_at
                ) VALUES (
                    'ac000000-0000-0000-0000-000000000001', ?,
                    'ab000000-0000-0000-0000-000000000001',
                    'OA-TRANSFER-001', '1', 'LEAVE', 'APPROVED',
                    1, TIMESTAMP '2026-06-15 00:00:00'
                )
                """,
                SOURCE_ID);
        jdbc.update(
                """
                UPDATE employment_assignment
                SET effective_to = TIMESTAMP '2026-07-01 00:00:00'
                WHERE assignment_id =
                    'c0000000-0000-0000-0000-000000000002'
                """);
        jdbc.update(
                """
                INSERT INTO employment_assignment (
                    assignment_id, employment_period_id, employee_id,
                    organization_id, effective_from, change_reason
                ) VALUES (
                    'c0000000-0000-0000-0000-000000000098',
                    'c0000000-0000-0000-0000-000000000098',
                    ?, '40000000-0000-0000-0000-000000000001',
                    TIMESTAMP '2026-07-01 00:00:00',
                    'OA current authorization transfer test'
                )
                """,
                EMPLOYEE_ID);
    }

    private void seedCurrentDepartmentPrincipal() {
        jdbc.update(
                """
                INSERT INTO auth_principal (
                    principal_id, status, created_at, row_version
                ) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)
                """,
                CURRENT_DEPARTMENT_PRINCIPAL);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (
                    '90000000-0000-0000-0000-000000000098',
                    'ORGANIZATION', NULL,
                    '40000000-0000-0000-0000-000000000001',
                    TRUE, TIMESTAMP '2020-01-01 00:00:00', NULL
                )
                """);
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to
                ) VALUES (
                    'a0000000-0000-0000-0000-000000000098',
                    ?, '10000000-0000-0000-0000-000000000003',
                    '90000000-0000-0000-0000-000000000098',
                    TIMESTAMP '2020-01-01 00:00:00', NULL
                )
                """,
                CURRENT_DEPARTMENT_PRINCIPAL);
    }
}
