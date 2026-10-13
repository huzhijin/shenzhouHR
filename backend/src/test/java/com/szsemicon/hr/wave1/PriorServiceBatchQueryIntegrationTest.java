package com.szsemicon.hr.wave1;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceRecord;
import com.szsemicon.hr.people.infrastructure.persistence.MyBatisPeopleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for PriorService batch query with business_date and occurred_at filters.
 * Uses real test employees and validates SQL filtering by business date and knowledge cutoff.
 */
class PriorServiceBatchQueryIntegrationTest extends Wave1IntegrationTestSupport {

    private static final String ALICE_ID = "b0000000-0000-0000-0000-000000000001";
    private static final String BOB_ID = "b0000000-0000-0000-0000-000000000002";

    @Autowired
    private MyBatisPeopleRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void batchQueryFiltersBy_BusinessDate_And_OccurredAt() throws Exception {
        // 登录并seed两个员工的工龄记录
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        // Alice: 2024-01-10生效，早期录入
        seedPriorServiceRecord(session, ALICE_ID, 9.00, LocalDate.of(2024, 1, 10), "Alice early");
        Thread.sleep(100); // 确保occurred_at不同

        // Bob: 2024-06-15生效，较晚录入
        seedPriorServiceRecord(session, BOB_ID, 5.50, LocalDate.of(2024, 6, 15), "Bob later");

        Instant knowledgeCutoff = Instant.now(); // 当前时刻作为knowledge cutoff

        // When: 查询截止到2024-03-31的business_date，两条记录都在知识截止内
        List<PriorServiceRecord> resultMarch = repository.listPriorServiceRecordsBatch(
                List.of(ALICE_ID, BOB_ID),
                LocalDate.of(2024, 3, 31),
                knowledgeCutoff
        );

        // Then: 只返回Alice的记录（Bob的生效日2024-06-15超出business_date截止）
        assertThat(resultMarch).hasSize(1);
        assertThat(resultMarch.get(0).employeeId()).isEqualTo(ALICE_ID);
        assertThat(resultMarch.get(0).amountYears()).isEqualByComparingTo(new BigDecimal("9.00"));

        // When: 查询截止到2024-12-31的business_date
        List<PriorServiceRecord> resultDec = repository.listPriorServiceRecordsBatch(
                List.of(ALICE_ID, BOB_ID),
                LocalDate.of(2024, 12, 31),
                knowledgeCutoff
        );

        // Then: 返回两条记录
        assertThat(resultDec).hasSize(2);
        assertThat(resultDec).extracting(PriorServiceRecord::employeeId)
                .containsExactlyInAnyOrder(ALICE_ID, BOB_ID);
    }

    @Test
    void batchQueryRespects_OccurredAt_KnowledgeCutoff() throws Exception {
        // 登录并seed一条记录，记录其occurred_at
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        seedPriorServiceRecord(session, ALICE_ID, 3.00, LocalDate.of(2024, 5, 1), "Alice May");

        // 从数据库查询实际的occurred_at
        Instant actualOccurredAt = jdbc.queryForObject(
                """
                SELECT occurred_at FROM prior_service_record
                WHERE employee_id = ? AND reason = ?
                ORDER BY occurred_at DESC LIMIT 1
                """,
                Instant.class,
                ALICE_ID,
                "Alice May"
        );

        Instant beforeRecord = actualOccurredAt.minusMillis(100);
        Instant afterRecord = actualOccurredAt.plusMillis(100);

        // When: 使用未来的knowledge cutoff（应该能查到）
        List<PriorServiceRecord> resultFuture = repository.listPriorServiceRecordsBatch(
                List.of(ALICE_ID),
                LocalDate.of(2024, 12, 31),
                afterRecord
        );
        assertThat(resultFuture).isNotEmpty();

        // When: 使用过去的knowledge cutoff（记录的occurred_at在之后，则查不到）
        List<PriorServiceRecord> resultPast = repository.listPriorServiceRecordsBatch(
                List.of(ALICE_ID),
                LocalDate.of(2024, 12, 31),
                beforeRecord
        );
        // 由于beforeRecord在实际occurred_at之前，应该查不到
        assertThat(resultPast).isEmpty();
    }

    @Test
    void batchQuery_ExcludesEmployees_NotInList() throws Exception {
        // seed两个员工
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);
        seedPriorServiceRecord(session, ALICE_ID, 1.00, LocalDate.of(2024, 1, 1), "Alice");
        seedPriorServiceRecord(session, BOB_ID, 2.00, LocalDate.of(2024, 1, 1), "Bob");

        // When: 只查询Alice
        List<PriorServiceRecord> result = repository.listPriorServiceRecordsBatch(
                List.of(ALICE_ID),
                LocalDate.of(2024, 12, 31),
                Instant.now()
        );

        // Then: 只返回Alice，不返回Bob
        assertThat(result).hasSize(1);
        assertThat(result.get(0).employeeId()).isEqualTo(ALICE_ID);
    }

    @Test
    void batchQuery_Excel3240_PreservesStoredAndNormalizes_To3285() throws Exception {
        // seed真实Excel 360 SET记录：stored=3240天，amountYears=NULL，basis=DAYS_PER_YEAR_360
        // normalized应为3285天（3240*365/360，四舍五入）
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        // 直接SQL插入真实Excel 360记录（不能用REST API，因为API优先amountYears会创建365记录）
        String excelRecordId = UUID.randomUUID().toString();
        String requestId = "excel-prior-test:" + ALICE_ID;

        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'OPENING_IMPORT', ?, 'Excel导入9年', '2024-01-01',
                         NULL, NULL, 3240, ?, NOW(),
                         ?, 1, NULL, 'DAYS_PER_YEAR_360', 1)
                """,
                excelRecordId, ALICE_ID, 3240,
                "81000000-0000-0000-0000-000000000001",
                requestId);

        // When: 批量查询
        List<PriorServiceRecord> result = repository.listPriorServiceRecordsBatch(
                List.of(ALICE_ID),
                LocalDate.of(2024, 12, 31),
                Instant.now()
        );

        // Then: stored = 3240, amountYears = null (legacy Excel 360)
        assertThat(result).hasSize(1);
        PriorServiceRecord record = result.get(0);
        assertThat(record.amountDays()).isEqualTo(3240); // stored原始值
        assertThat(record.amountYears()).isNull(); // Excel 360记录没有amountYears
        assertThat(record.conversionBasis()).isEqualTo("DAYS_PER_YEAR_360");

        // 验证GET返回的normalized = 3285（3240*365=1182600 units，1182600/360=3285天，9.00年）
        String getResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + ALICE_ID), session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Integer totalDays = JsonPath.parse(getResponse).read("$.priorService.totalDays", Integer.class);
        Double totalYears = JsonPath.parse(getResponse).read("$.priorService.totalYears", Double.class);
        assertThat(totalDays).isEqualTo(3285); // normalized: 1182600 units / 360 = 3285
        assertThat(totalYears).isEqualTo(9.00); // 3285 / 365 = 9.00
    }

    @Test
    void batchQuery_0_01_Years_PreservesPrecision() throws Exception {
        // seed 0.01年记录
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);
        seedPriorServiceRecord(session, ALICE_ID, 0.01, LocalDate.of(2024, 1, 1), "0.01 years precision test");

        // When: 批量查询
        List<PriorServiceRecord> result = repository.listPriorServiceRecordsBatch(
                List.of(ALICE_ID),
                LocalDate.of(2024, 12, 31),
                Instant.now()
        );

        // Then: 精确保留0.01年
        assertThat(result).hasSize(1);
        assertThat(result.get(0).amountYears()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(result.get(0).amountDays()).isEqualTo(4); // round(0.01 * 365) = 4
    }

    // Helper: seed一条工龄记录
    private void seedPriorServiceRecord(AuthenticatedSession session, String employeeId,
                                       double years, LocalDate businessDate, String reason) throws Exception {
        String etag = getEmployeeEtag(session, employeeId);
        int days = (int) Math.round(years * 365);

        String json = String.format("""
                {
                  "amountYears": %.2f,
                  "amountDays": %d,
                  "businessDate": "%s",
                  "reason": "%s"
                }
                """, years, days, businessDate, reason);

        mockMvc.perform(
                        withSession(post("/api/v1/employees/" + employeeId + "/prior-service-adjustments"), session)
                                .header(HttpHeaders.IF_MATCH, etag)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andExpect(status().isCreated());
    }

    private String getEmployeeEtag(AuthenticatedSession session, String employeeId) throws Exception {
        String response = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long version = JsonPath.parse(response).read("$.rowVersion", Long.class);
        return "\"" + version + "\"";
    }
}
