package com.szsemicon.hr.wave1;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工龄365天/年转换的真实集成测试
 * 使用真实API路径、真实会话、真实seed员工
 */
class PriorService365RealIntegrationTest extends Wave1IntegrationTestSupport {

    private static final String TEST_EMPLOYEE_ID = "b0000000-0000-0000-0000-000000000001"; // Alice from test-data.sql

    @Test
    void adjust_1_50_years_full_chain_post_db_get() throws Exception {
        // 登录获取会话 - 使用username而非principalId
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        // 获取当前员工版本
        String employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + TEST_EMPLOYEE_ID), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);
        String etag = "\"" + currentVersion + "\"";

        // POST: 调整工龄 1.50年 = 548天
        String adjustmentJson = """
                {
                  "amountYears": 1.50,
                  "amountDays": 548,
                  "businessDate": "2026-10-12",
                  "reason": "Test 1.50 years adjustment"
                }
                """;

        String adjustResponse = mockMvc.perform(
                        withSession(post("/api/v1/employees/" + TEST_EMPLOYEE_ID + "/prior-service-adjustments"), session)
                                .header(HttpHeaders.IF_MATCH, etag)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(adjustmentJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountDays").value(548))
                .andExpect(jsonPath("$.amountYears").value(1.50))
                .andExpect(jsonPath("$.resultingTotalDays").value(548))
                .andExpect(jsonPath("$.conversionBasis").value("DAYS_PER_YEAR_365"))
                .andExpect(jsonPath("$.conversionVersion").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String recordId = JsonPath.parse(adjustResponse).read("$.priorServiceRecordId", String.class);

        // DB: 验证数据库持久化
        Map<String, Object> dbRecord = jdbc.queryForMap(
                "SELECT amount_days, amount_years, resulting_total_days, conversion_basis, conversion_version " +
                        "FROM prior_service_record WHERE prior_service_record_id = ?",
                recordId);

        assertThat(dbRecord.get("amount_days")).isEqualTo(548);
        assertThat(dbRecord.get("amount_years")).isEqualTo(new BigDecimal("1.50"));
        assertThat(dbRecord.get("resulting_total_days")).isEqualTo(548);
        assertThat(dbRecord.get("conversion_basis")).isEqualTo("DAYS_PER_YEAR_365");
        assertThat(dbRecord.get("conversion_version")).isEqualTo(1);

        // GET: 验证回读和replay计算
        String getResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + TEST_EMPLOYEE_ID), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer totalDays = JsonPath.parse(getResponse).read("$.priorService.totalDays", Integer.class);
        Double totalYears = JsonPath.parse(getResponse).read("$.priorService.totalYears", Double.class);
        Integer completeMonths = JsonPath.parse(getResponse).read("$.priorService.completeMonths", Integer.class);

        assertThat(totalDays).isEqualTo(548);
        assertThat(totalYears).isEqualTo(1.50);
        assertThat(completeMonths).isEqualTo(18); // 1.50 * 12 = 18

        System.out.println("✓ 1.50年 → 548天完整链路验证通过");
    }

    @Test
    void adjust_9_years_then_deduct_9_years_results_in_zero() throws Exception {
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);
        String employeeId = "b0000000-0000-0000-0000-000000000002"; // Bob

        String employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);

        // 第一次：+9年
        String addJson = """
                {
                  "amountYears": 9.00,
                  "amountDays": 3285,
                  "businessDate": "2026-10-12",
                  "reason": "Add 9 years"
                }
                """;

        mockMvc.perform(
                        withSession(post("/api/v1/employees/" + employeeId + "/prior-service-adjustments"), session)
                                .header(HttpHeaders.IF_MATCH, "\"" + currentVersion + "\"")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultingTotalDays").value(3285));

        // 获取新版本
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);
        Integer afterAdd = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        assertThat(afterAdd).isEqualTo(3285);

        // 第二次：-9年
        String deductJson = """
                {
                  "amountYears": -9.00,
                  "amountDays": -3285,
                  "businessDate": "2026-10-12",
                  "reason": "Deduct 9 years"
                }
                """;

        mockMvc.perform(
                        withSession(post("/api/v1/employees/" + employeeId + "/prior-service-adjustments"), session)
                                .header(HttpHeaders.IF_MATCH, "\"" + currentVersion + "\"")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deductJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultingTotalDays").value(0));

        // 验证最终结果
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer finalTotal = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double finalYears = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);
        Integer finalMonths = JsonPath.parse(employeeResponse).read("$.priorService.completeMonths", Integer.class);

        assertThat(finalTotal).isEqualTo(0);
        assertThat(finalYears).isEqualTo(0.00);
        assertThat(finalMonths).isEqualTo(0);

        System.out.println("✓ 9年 - 9年 = 0天验证通过");
    }

    @Test
    void adjust_8_25_years_results_in_99_complete_months() throws Exception {
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);
        String employeeId = "b0000000-0000-0000-0000-000000000003"; // Carol

        String employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);

        // 调整 8.25年 = 3011天
        String adjustmentJson = """
                {
                  "amountYears": 8.25,
                  "amountDays": 3011,
                  "businessDate": "2026-10-12",
                  "reason": "Test 8.25 years = 99 months"
                }
                """;

        mockMvc.perform(
                        withSession(post("/api/v1/employees/" + employeeId + "/prior-service-adjustments"), session)
                                .header(HttpHeaders.IF_MATCH, "\"" + currentVersion + "\"")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(adjustmentJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountDays").value(3011))
                .andExpect(jsonPath("$.amountYears").value(8.25))
                .andExpect(jsonPath("$.resultingTotalDays").value(3011));

        // 验证 GET 返回精确年数和完整月数
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer totalDays = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double totalYears = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);
        Integer completeMonths = JsonPath.parse(employeeResponse).read("$.priorService.completeMonths", Integer.class);

        assertThat(totalDays).isEqualTo(3011);
        assertThat(totalYears).isEqualTo(8.25);
        assertThat(completeMonths).isEqualTo(99); // 8.25 * 12 = 99

        System.out.println("✓ 8.25年 → 3011天 → 99个完整月验证通过");
    }

    @Test
    void excel_360_import_9_years_then_new_365_deduct_9_years() throws Exception {
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        // 使用Bob，先清理prior_service记录
        String employeeId = "b0000000-0000-0000-0000-000000000002";
        jdbc.update("DELETE FROM prior_service_record WHERE employee_id = ?", employeeId);

        // 模拟真实Excel 360导入记录：requestId后缀=employeeId，amount_years=NULL
        // conversionBasis='DAYS_PER_YEAR_360', conversionVersion=1（迁移后值）
        String excelRecordId = UUID.randomUUID().toString();
        String requestId = "excel-prior-20260930:" + employeeId; // 真实格式

        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'OPENING_IMPORT', ?, 'Excel导入9年', '2026-01-01',
                         NULL, NULL, 3240, ?, NOW(),
                         ?, 1, NULL, 'DAYS_PER_YEAR_360', 1)
                """,
                excelRecordId, employeeId, 3240,
                "81000000-0000-0000-0000-000000000001",
                requestId);

        // 验证Excel导入后的状态
        String employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer totalDaysAfterImport = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double totalYearsAfterImport = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);

        // Excel stored 3240天(1182600 units)，但normalized对外是 1182600/360=3285天
        assertThat(totalDaysAfterImport).isEqualTo(3285);
        assertThat(totalYearsAfterImport).isEqualTo(9.00);

        long currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);

        // 用新365记录减去9年（-3285天）
        String deductJson = """
                {
                  "amountYears": -9.00,
                  "amountDays": -3285,
                  "businessDate": "2026-10-12",
                  "reason": "Deduct 9 years"
                }
                """;

        mockMvc.perform(
                        withSession(post("/api/v1/employees/" + employeeId + "/prior-service-adjustments"), session)
                                .header(HttpHeaders.IF_MATCH, "\"" + currentVersion + "\"")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deductJson))
                .andExpect(status().isCreated());

        // 验证最终结果：9.00年 - 9.00年 = 严格的0天/0.00年
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer finalDays = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double finalYears = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);

        // 严格断言：9.00 - 9.00 = 0，不接受容差
        assertThat(finalDays).isEqualTo(0);
        assertThat(finalYears).isEqualTo(0.00);

        System.out.println("✓ Excel 9.00年(360) - 新365 9.00年 → 严格0验证通过");
    }

    @Test
    void excel_9_years_plus_legacy_10_days_then_deduct_9_years() throws Exception {
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        // 使用Carol，清理prior_service记录
        String employeeId = "b0000000-0000-0000-0000-000000000003";
        jdbc.update("DELETE FROM prior_service_record WHERE employee_id = ?", employeeId);

        // 1. Excel 360导入9年 = 3240天
        String excelRecordId = UUID.randomUUID().toString();
        String requestId = "excel-prior-20260930:" + employeeId;

        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'OPENING_IMPORT', ?, 'Excel导入9年', '2026-01-01',
                         NULL, NULL, 3240, ?, NOW(),
                         ?, 1, NULL, 'DAYS_PER_YEAR_360', 1)
                """,
                excelRecordId, employeeId, 3240,
                "81000000-0000-0000-0000-000000000001",
                requestId);

        // 验证Excel后状态：stored 3240天(1182600 units)，normalized对外 1182600/360=3285天
        String employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class)).isEqualTo(3285);
        assertThat(JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class)).isEqualTo(9.00);

        long currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);

        // 2. 添加历史真实天数记录：+10天（stored total应该是3250，不是3240+10/365*365=3243）
        String legacyRecordId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'ADJUST', ?, '补录10天', '2026-02-01',
                         NULL, NULL, 3250, ?, NOW(),
                         ?, 2, NULL, NULL, NULL)
                """,
                legacyRecordId, employeeId, 10,
                "81000000-0000-0000-0000-000000000001",
                UUID.randomUUID().toString());

        // 验证Excel+legacy后状态：应该能正确读取，不抛异常
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer totalDaysAfterLegacy = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double totalYearsAfterLegacy = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);

        // Excel 3240天(1182600 units) + legacy 10天(3600 units) = 1186200 units
        // Normalized days = 1186200/360 = 3295天
        // Stored值仍为3250，但对外暴露normalized 3295
        assertThat(totalDaysAfterLegacy).isEqualTo(3295);
        // 累计年数：1186200 / 131400 ≈ 9.027...年
        assertThat(totalYearsAfterLegacy).isEqualTo(9.03);

        currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);

        // 3. 用新365记录减去9年
        String deductJson = """
                {
                  "amountYears": -9.00,
                  "amountDays": -3285,
                  "businessDate": "2026-10-12",
                  "reason": "Deduct 9 years"
                }
                """;

        mockMvc.perform(
                        withSession(post("/api/v1/employees/" + employeeId + "/prior-service-adjustments"), session)
                                .header(HttpHeaders.IF_MATCH, "\"" + currentVersion + "\"")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deductJson))
                .andExpect(status().isCreated());

        // 验证最终结果：应该剩余10天（真实天数）
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer finalDays = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double finalYears = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);

        // Excel 9年 + 真实10天 - 新365的9年 = 真实10天
        assertThat(finalDays).isEqualTo(10);
        assertThat(finalYears).isEqualTo(0.03); // 10天/365 ≈ 0.027，四舍五入到0.03

        System.out.println("✓ Excel 9年 + legacy 10天 - 新365 9年 → 剩余10天验证通过");
    }

    @Test
    void three_hundred_days_accumulated_correctly() throws Exception {
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        String employeeId = "b0000000-0000-0000-0000-000000000001";
        jdbc.update("DELETE FROM prior_service_record WHERE employee_id = ?", employeeId);

        // 累积添加300条+1天的记录
        for (int i = 0; i < 300; i++) {
            String recordId = UUID.randomUUID().toString();
            String requestId = UUID.randomUUID().toString();
            // 预期的累计天数：每次+1天
            int expectedTotal = i + 1;

            jdbc.update("""
                    INSERT INTO prior_service_record (
                        prior_service_record_id, employee_id, record_type,
                        amount_days, reason, business_date,
                        source_import_batch_id, reversal_of_record_id,
                        resulting_total_days, actor_id, occurred_at,
                        request_id, row_version,
                        amount_years, conversion_basis, conversion_version
                    ) VALUES (?, ?, 'ADJUST', ?, '补录1天', ?,
                             NULL, NULL, ?, ?, NOW(),
                             ?, ?, NULL, NULL, NULL)
                    """,
                    recordId, employeeId, 1, "2026-01-01",
                    expectedTotal, "81000000-0000-0000-0000-000000000001",
                    requestId, i);
        }

        // 验证最终累计：300条+1天 = 300天，不是296天
        String employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer totalDays = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double totalYears = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);

        assertThat(totalDays).isEqualTo(300);
        assertThat(totalYears).isEqualTo(0.82); // 300/365 = 0.8219...，保留2位=0.82

        System.out.println("✓ 300条+1天 → 300天（非296天）验证通过");
    }

    @Test
    void import_then_rollback_restores_exact_seniority() throws Exception {
        // 测试：导入天数记录后撤销，精确恢复原累计
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        String employeeId = "b0000000-0000-0000-0000-000000000001"; // Alice
        jdbc.update("DELETE FROM prior_service_record WHERE employee_id = ?", employeeId);

        // 初始状态：新365 API添加5.00年
        String employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);

        String initialJson = """
                {
                  "amountYears": 5.00,
                  "amountDays": 1825,
                  "businessDate": "2026-01-01",
                  "reason": "Initial 5 years"
                }
                """;

        mockMvc.perform(
                        withSession(post("/api/v1/employees/" + employeeId + "/prior-service-adjustments"), session)
                                .header(HttpHeaders.IF_MATCH, "\"" + currentVersion + "\"")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(initialJson))
                .andExpect(status().isCreated());

        // 模拟import：添加30天实际天数记录（ACTUAL_DAYS）
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);

        String importRecordId = UUID.randomUUID().toString();
        String importRequestId = "test-import-batch:" + employeeId;

        // 使用共享ledger计算预期累计
        long unitsAfter5Years = 5L * 131400L; // 657000
        long unitsAfter30Days = unitsAfter5Years + 30L * 360L; // 657000 + 10800 = 667800
        long absDays = Math.abs(unitsAfter30Days) / 360L;
        if (Math.abs(unitsAfter30Days) % 360L >= 180L) {
            absDays++;
        }
        int expectedTotal = (int) absDays; // 1855天

        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'OPENING_IMPORT', 30, 'Import 30 days', '2026-02-01',
                         'test-batch-id', NULL, ?, ?, NOW(),
                         ?, 1, NULL, 'ACTUAL_DAYS', 1)
                """,
                importRecordId, employeeId, expectedTotal,
                "81000000-0000-0000-0000-000000000001",
                importRequestId);

        // 验证导入后状态
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer totalAfterImport = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        assertThat(totalAfterImport).isEqualTo(expectedTotal);

        // 模拟rollback：撤销导入记录
        currentVersion = JsonPath.parse(employeeResponse).read("$.rowVersion", Long.class);

        String reversalRecordId = UUID.randomUUID().toString();
        String reversalRequestId = UUID.randomUUID().toString();

        // reversal应该使用精确-30天*360单位
        long reversalUnits = -30L * 360L;
        long finalUnits = unitsAfter30Days + reversalUnits; // 应该恰好回到657000
        long finalAbsDays = Math.abs(finalUnits) / 360L;
        if (Math.abs(finalUnits) % 360L >= 180L) {
            finalAbsDays++;
        }
        int restoredTotal = (int) finalAbsDays; // 应该是1825

        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'REVERSAL', -30, 'Rollback import', '2026-03-01',
                         NULL, ?, ?, ?, NOW(),
                         ?, 2, NULL, 'ACTUAL_DAYS', 1)
                """,
                reversalRecordId, employeeId, importRecordId, restoredTotal,
                "81000000-0000-0000-0000-000000000001",
                reversalRequestId);

        // 验证最终恢复：应该精确回到5.00年
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer finalDays = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double finalYears = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);

        assertThat(finalDays).isEqualTo(1825);
        assertThat(finalYears).isEqualTo(5.00);

        System.out.println("✓ 导入30天ACTUAL_DAYS后rollback → 精确恢复5.00年验证通过");
    }

    @Test
    void corrupted_legacy_stored_total_rejected() throws Exception {
        // 测试：损坏的旧stored值应该被拒绝
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        String employeeId = "b0000000-0000-0000-0000-000000000002"; // Bob
        jdbc.update("DELETE FROM prior_service_record WHERE employee_id = ?", employeeId);

        // 插入第一条legacy记录：10天
        String record1Id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'ADJUST', 10, 'First record', '2026-01-01',
                         NULL, NULL, 10, ?, NOW(),
                         ?, 0, NULL, NULL, NULL)
                """,
                record1Id, employeeId,
                "81000000-0000-0000-0000-000000000001",
                UUID.randomUUID().toString());

        // 插入第二条legacy记录：+5天，但stored值损坏（写成16而非15）
        String record2Id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'ADJUST', 5, 'Corrupted record', '2026-02-01',
                         NULL, NULL, 16, ?, NOW(),
                         ?, 1, NULL, NULL, NULL)
                """,
                record2Id, employeeId,
                "81000000-0000-0000-0000-000000000001",
                UUID.randomUUID().toString());

        // GET应该失败，抛出IllegalStateException包装在ServletException中
        try {
            mockMvc.perform(
                            withSession(get("/api/v1/employees/" + employeeId), session))
                    .andExpect(status().is5xxServerError());
        } catch (Exception e) {
            // 验证异常消息包含integrity violated
            assertThat(e.getCause().getMessage()).contains("Legacy record integrity violated");
        }

        System.out.println("✓ 损坏的legacy stored值（10+5≠16）被拒绝验证通过");
    }

    @Test
    void excel_360_import_9_years_then_rollback_returns_to_zero() throws Exception {
        AuthenticatedSession session = login("wave1_admin_synthetic", adminPassword);

        String employeeId = "b0000000-0000-0000-0000-000000000003"; // Charlie
        jdbc.update("DELETE FROM prior_service_record WHERE employee_id = ?", employeeId);

        // 使用Excel 360导入9年（3240天stored）
        String record1Id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'OPENING_IMPORT', 3240, 'Excel import 9 years', '2026-01-01',
                         'test-batch-excel', NULL, 3240, ?, NOW(),
                         ?, 0, NULL, 'DAYS_PER_YEAR_360', 1)
                """,
                record1Id, employeeId,
                "81000000-0000-0000-0000-000000000001",
                UUID.randomUUID().toString());

        // 验证导入后状态：3285 normalized days
        String employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer totalAfterImport = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        assertThat(totalAfterImport).isEqualTo(3285); // 3240*365/360 四舍五入

        // 创建REVERSAL记录撤销
        String reversalId = UUID.randomUUID().toString();

        // REVERSAL应该使restoredTotal=0
        jdbc.update("""
                INSERT INTO prior_service_record (
                    prior_service_record_id, employee_id, record_type,
                    amount_days, reason, business_date,
                    source_import_batch_id, reversal_of_record_id,
                    resulting_total_days, actor_id, occurred_at,
                    request_id, row_version,
                    amount_years, conversion_basis, conversion_version
                ) VALUES (?, ?, 'REVERSAL', -3240, 'Rollback Excel import', '2026-02-01',
                         NULL, ?, 0, ?, NOW(),
                         ?, 1, NULL, 'DAYS_PER_YEAR_360', 1)
                """,
                reversalId, employeeId, record1Id,
                "81000000-0000-0000-0000-000000000001",
                UUID.randomUUID().toString());

        // 验证rollback后精确归零
        employeeResponse = mockMvc.perform(
                        withSession(get("/api/v1/employees/" + employeeId), session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer finalDays = JsonPath.parse(employeeResponse).read("$.priorService.totalDays", Integer.class);
        Double finalYears = JsonPath.parse(employeeResponse).read("$.priorService.totalYears", Double.class);

        assertThat(finalDays).isEqualTo(0);
        assertThat(finalYears).isEqualTo(0.00);

        System.out.println("✓ Excel 360导入9年（3240→3285）rollback → 精确归零验证通过");
    }

}
