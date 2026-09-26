package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DepartmentPathNamesTest {

    @Test
    void concatenatesFromFirstLevelThroughLeaf() {
        DepartmentPathNames.Levels levels = DepartmentPathNames.fromRootToLeaf(
                List.of("服务中心", "工程一部", "MATCH组", "MATCH1组"));
        assertThat(levels.levelOne()).isEqualTo("服务中心");
        assertThat(levels.levelTwo()).isEqualTo("工程一部");
        assertThat(levels.levelThree()).isEqualTo("MATCH组");
        assertThat(levels.group()).isEqualTo("MATCH1组");
        assertThat(levels.segments()).containsExactly(
                "服务中心", "工程一部", "MATCH组", "MATCH1组");
        assertThat(DepartmentPathNames.visibleDepartment(levels.reportDepartment()))
                .isEqualTo("服务中心-工程一部-MATCH组-MATCH1组");
        assertThat(levels.reportDepartment())
                .contains("服务中心-" + DepartmentPathNames.SEGMENT_BREAK + "工程一部");
        assertThat(DepartmentPathNames.excelWrappedDepartment(levels.reportDepartment()))
                .isEqualTo("服务中心-\n工程一部-\nMATCH组-\nMATCH1组");
    }

    @Test
    void includesCenterForEngineeringTwoRfB() {
        DepartmentPathNames.Levels levels = DepartmentPathNames.fromRootToLeaf(
                List.of("服务中心", "工程二部", "RF-B组"));
        assertThat(DepartmentPathNames.visibleDepartment(levels.reportDepartment()))
                .isEqualTo("服务中心-工程二部-RF-B组");
        assertThat(levels.reportDepartment())
                .doesNotContain("RF-" + DepartmentPathNames.SEGMENT_BREAK);
        assertThat(DepartmentPathNames.excelWrappedDepartment(levels.reportDepartment()))
                .isEqualTo("服务中心-\n工程二部-\nRF-B组");
    }

    @Test
    void parsesReportDepartmentBackIntoLevels() {
        String path = DepartmentPathNames.fromRootToLeaf(
                List.of("服务中心", "工程二部", "RF-B组")).reportDepartment();
        DepartmentPathNames.Levels parsed = DepartmentPathNames.fromReportDepartment(path);
        assertThat(parsed.levelOne()).isEqualTo("服务中心");
        assertThat(parsed.levelTwo()).isEqualTo("工程二部");
        assertThat(parsed.levelThree()).isEqualTo("RF-B组");
    }

    @Test
    void firstLevelOnlyUsesTheCenterName() {
        assertThat(DepartmentPathNames.fromRootToLeaf(List.of("销售中心"))
                .reportDepartment())
                .isEqualTo("销售中心");
    }

    @Test
    void walksGraphSkippingCompany() {
        Map<String, DepartmentPathNames.ParentName> graph = Map.of(
                "leaf", new DepartmentPathNames.ParentName(
                        "match", "MATCH1组", "TEAM"),
                "match", new DepartmentPathNames.ParentName(
                        "eng", "MATCH组", "DEPARTMENT"),
                "eng", new DepartmentPathNames.ParentName(
                        "svc", "工程一部", "DEPARTMENT"),
                "svc", new DepartmentPathNames.ParentName(
                        "co", "服务中心", "DEPARTMENT"),
                "co", new DepartmentPathNames.ParentName(
                        null, "江苏神州半导体科技股份有限公司", "COMPANY"));
        assertThat(DepartmentPathNames.visibleDepartment(
                DepartmentPathNames.reportDepartment(
                        "leaf", "MATCH1组", graph)))
                .isEqualTo("服务中心-工程一部-MATCH组-MATCH1组");
    }

    @Test
    void concatenatesClosureAncestorsFromCenterToLeaf() {
        List<DepartmentPathNames.AncestorName> rows = List.of(
                new DepartmentPathNames.AncestorName(
                        "leaf", "江苏神州半导体科技股份有限公司", "COMPANY"),
                new DepartmentPathNames.AncestorName(
                        "leaf", "服务中心", "DEPARTMENT"),
                new DepartmentPathNames.AncestorName(
                        "leaf", "工程二部", "DEPARTMENT"),
                new DepartmentPathNames.AncestorName(
                        "leaf", "RF-B组", "TEAM"));
        assertThat(DepartmentPathNames.visibleDepartment(
                DepartmentPathNames.reportDepartmentsFromAncestors(rows)
                        .get("leaf")))
                .isEqualTo("服务中心-工程二部-RF-B组");
    }

    @Test
    void displayDepartmentsPrefersTheLongerClosurePath() {
        Map<String, DepartmentPathNames.ParentName> graph = Map.of(
                "leaf", new DepartmentPathNames.ParentName(
                        null, "DC组", "DEPARTMENT"));
        List<DepartmentPathNames.AncestorName> rows = List.of(
                new DepartmentPathNames.AncestorName(
                        "leaf", "江苏神州半导体科技股份有限公司", "COMPANY"),
                new DepartmentPathNames.AncestorName(
                        "leaf", "服务中心", "DEPARTMENT"),
                new DepartmentPathNames.AncestorName(
                        "leaf", "工程一部", "DEPARTMENT"),
                new DepartmentPathNames.AncestorName(
                        "leaf", "DC组", "DEPARTMENT"));
        assertThat(DepartmentPathNames.visibleDepartment(
                DepartmentPathNames.displayDepartments(graph, rows).get("leaf")))
                .isEqualTo("服务中心-工程一部-DC组");
    }
}
