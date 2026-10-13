package com.szsemicon.hr.people.application.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RosterWorkbookAndPrecheckTest {

    @Test
    void templateHeadersMatchRoster() throws Exception {
        byte[] content = RosterWorkbook.template();
        List<RosterRow> rows;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(content))) {
            var header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("序号");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("入职日期");
            assertThat(header.getLastCellNum()).isEqualTo((short) 10);
        }
        assertThat(RosterNames.HEADERS).containsExactly(
                "序号", "公司名称", "工号", "姓名", "一级部门", "二级部门",
                "三级部门", "组别", "职位", "入职日期");
    }

    @Test
    void parseSkipsTitleRowAndTreatsSlashAsEmpty() throws Exception {
        byte[] content = workbook(
                List.of("江苏神州半导体花名册", "", "", "", "", "", "", "", "", ""),
                List.of("序号", "公司名称", "工号", "姓名", "一级部门", "二级部门", "三级部门", "组别", "职位", "入职日期"),
                List.of("1", "江苏神州", "SZST0743", "张立强", "技术支持中心", "现场服务部", "武汉产品服务组", "/", "", "2026-09-07"));
        List<RosterRow> rows = RosterWorkbook.parse(content);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).employeeNumber()).isEqualTo("SZST0743");
        assertThat(rows.get(0).pathTokens("SZSC")).containsExactly("技术支持中心", "现场服务部", "武汉产品服务组");
    }

    @Test
    void juenengPrefixAndRd1Alias() {
        assertThat(RosterNames.alias("SZJN", "聚能-研发一部")).isEqualTo("研发一部");
        assertThat(RosterNames.namesMatch("SZJN", "RD1", "研发一部")).isTrue();
        assertThat(RosterNames.mapCompanyCode("上海晟州聚能")).contains("SZJN");
        assertThat(RosterNames.isXinyue("江苏芯越半导体科技有限公司")).isTrue();
    }

    @Test
    void precheckAddsHireAndBlocksNameConflict() {
        RosterPrecheck.Company company = new RosterPrecheck.Company("c1", "SZSC", "江苏神州");
        RosterPrecheck.Org root = new RosterPrecheck.Org("root", "c1", null, "江苏神州", "SZSC", "COMPANY", "ACTIVE");
        RosterPrecheck.Org support = new RosterPrecheck.Org(
                "support", "c1", "root", "技术支持中心", "TS", "DEPARTMENT", "ACTIVE");
        RosterPrecheck.Org field = new RosterPrecheck.Org(
                "field", "c1", "support", "现场服务部", "FS", "DEPARTMENT", "ACTIVE");
        RosterPrecheck.Org wuhan = new RosterPrecheck.Org(
                "wuhan", "c1", "field", "武汉产品服务组", "WH", "TEAM", "ACTIVE");
        RosterPrecheck.Snapshot snapshot = RosterPrecheck.snapshot(
                List.of(company),
                List.of(root, support, field, wuhan),
                List.of());
        RosterRow added = new RosterRow(
                2, "1", "江苏神州", "SZST0743", "张立强",
                "技术支持中心", "现场服务部", "武汉产品服务组", "/", "",
                LocalDate.parse("2026-09-07"));
        RosterPrecheck.Result result = RosterPrecheck.run(List.of(added), snapshot);
        assertThat(result.summary().added()).isEqualTo(1);
        assertThat(result.canPublish()).isTrue();
        assertThat(result.diffs().get(0).matchedResourceId()).isEqualTo("wuhan");

        RosterPrecheck.Snapshot withConflict = RosterPrecheck.snapshot(
                List.of(company),
                List.of(root, support, field, wuhan),
                List.of(new RosterPrecheck.Employee("e1", "c1", "SZST0743", "别人", "wuhan")));
        RosterPrecheck.Result conflicted = RosterPrecheck.run(List.of(added), withConflict);
        assertThat(conflicted.summary().conflict()).isEqualTo(1);
        assertThat(conflicted.canPublish()).isFalse();
    }

    @Test
    void septemberHireWorkbookParsesZhangAndJueneng() throws Exception {
        byte[] content = java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/test/resources/roster/september-2026-hires.xlsx"));
        List<RosterRow> rows = RosterWorkbook.parse(content);
        assertThat(rows).hasSize(22);
        RosterRow zhang = rows.stream().filter(row -> "SZST0743".equals(row.employeeNumber())).findFirst().orElseThrow();
        assertThat(zhang.displayName()).isEqualTo("张立强");
        assertThat(zhang.pathTokens("SZSC")).containsExactly("技术支持中心", "现场服务部", "武汉产品服务组");
        assertThat(zhang.title()).isBlank();
        assertThat(rows.stream().map(RosterRow::employeeNumber).toList())
                .contains("SZJN0042", "SZJN0043", "SZJN0044", "SZJN0045", "SZJN0046");
    }

    @Test
    void repeatedGroupNameIsSameNodeNotConflict() {
        RosterPrecheck.Company company = new RosterPrecheck.Company("c1", "SZSC", "江苏神州");
        RosterPrecheck.Org root = new RosterPrecheck.Org("root", "c1", null, "江苏神州", "SZSC", "COMPANY", "ACTIVE");
        RosterPrecheck.Org service = new RosterPrecheck.Org(
                "service", "c1", "root", "服务中心", "SVC", "DEPARTMENT", "ACTIVE");
        RosterPrecheck.Org eng = new RosterPrecheck.Org(
                "eng", "c1", "service", "工程二部", "E2", "DEPARTMENT", "ACTIVE");
        RosterPrecheck.Org rf = new RosterPrecheck.Org(
                "rf", "c1", "eng", "RF-F组", "RFF", "TEAM", "ACTIVE");
        RosterRow row = new RosterRow(
                10, "8", "江苏神州", "SZST0738", "赵士悦",
                "服务中心", "工程二部", "RF-F组", "RF-F组", "RF-F组工程师",
                LocalDate.parse("2026-09-01"));
        assertThat(row.pathTokens("SZSC")).containsExactly("服务中心", "工程二部", "RF-F组");
        RosterPrecheck.Result result = RosterPrecheck.run(
                List.of(row),
                RosterPrecheck.snapshot(List.of(company), List.of(root, service, eng, rf), List.of()));
        assertThat(result.canPublish()).isTrue();
        assertThat(result.summary().conflict()).isZero();
        assertThat(result.diffs().get(0).matchedResourceId()).isEqualTo("rf");
    }

    @Test
    void sameGroupNameUnderAnotherParentDoesNotBlock() {
        RosterPrecheck.Company company = new RosterPrecheck.Company("c2", "SZJN", "上海晟州聚能");
        RosterPrecheck.Org root = new RosterPrecheck.Org("root", "c2", null, "聚能", "SZJN", "COMPANY", "ACTIVE");
        RosterPrecheck.Org rd = new RosterPrecheck.Org("rd", "c2", "root", "RD1", "RD1", "DEPARTMENT", "ACTIVE");
        RosterPrecheck.Org otherTest = new RosterPrecheck.Org(
                "other-test", "c2", "root", "测试组", "T0", "TEAM", "ACTIVE");
        RosterRow row = new RosterRow(
                22, "3", "上海晟州聚能", "SZJN0044", "王明鑫",
                "研发一部", "测试组", "/", "/", "测试助理工程师",
                LocalDate.parse("2026-09-03"));
        RosterPrecheck.Result result = RosterPrecheck.run(
                List.of(row),
                RosterPrecheck.snapshot(List.of(company), List.of(root, rd, otherTest), List.of()));
        assertThat(result.canPublish()).isTrue();
        assertThat(result.summary().conflict()).isZero();
        assertThat(result.diffs().get(0).category()).isEqualTo("ADDED");
    }

    @Test
    void sameNameDifferentNumberIsWarningOnly() {
        RosterPrecheck.Company company = new RosterPrecheck.Company("c2", "SZJN", "上海晟州聚能");
        RosterPrecheck.Org root = new RosterPrecheck.Org("root", "c2", null, "聚能", "SZJN", "COMPANY", "ACTIVE");
        RosterPrecheck.Org rd = new RosterPrecheck.Org("rd", "c2", "root", "RD1", "RD1", "DEPARTMENT", "ACTIVE");
        RosterPrecheck.Org test = new RosterPrecheck.Org("test", "c2", "rd", "测试组", "T", "TEAM", "ACTIVE");
        RosterPrecheck.Snapshot snapshot = RosterPrecheck.snapshot(
                List.of(company),
                List.of(root, rd, test),
                List.of(new RosterPrecheck.Employee("old", "c2", "SZJN0002", "王明鑫", "test")));
        RosterRow row = new RosterRow(
                2, "1", "上海晟州聚能", "SZJN0044", "王明鑫",
                "聚能-研发一部", "测试组", "/", "/", "测试助理工程师",
                LocalDate.parse("2026-09-03"));
        RosterPrecheck.Result result = RosterPrecheck.run(List.of(row), snapshot);
        assertThat(result.summary().added()).isEqualTo(1);
        assertThat(result.issues()).anyMatch(issue -> "SAME_NAME_DIFFERENT_NUMBER".equals(issue.code()));
        assertThat(result.canPublish()).isTrue();
        assertThat(row.pathTokens("SZJN")).containsExactly("研发一部", "测试组");
    }

    private static byte[] workbook(List<String>... rows) throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                List<String> values = rows[rowIndex];
                for (int column = 0; column < values.size(); column++) {
                    row.createCell(column).setCellValue(values.get(column));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
