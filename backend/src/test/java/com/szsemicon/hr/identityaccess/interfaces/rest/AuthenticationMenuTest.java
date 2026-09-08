package com.szsemicon.hr.identityaccess.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthenticationMenuTest {

    @Test
    void workbenchIsFirstAndReportUsesTheCanonicalRoute() {
        var menu = AuthenticationController.menu(List.of(
                "POLICY:READ",
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ));

        assertThat(menu.getFirst())
                .isEqualTo(new AuthenticationController.MenuItem(
                        "workbench", "考勤工作台", "/workbench"));
        assertThat(menu)
                .contains(new AuthenticationController.MenuItem(
                        "attendance-reports",
                        "考勤报表",
                        "/attendance/reports"));
    }

    @Test
    void queryCapabilityShowsAllNineQueryPagesAndKeepsOfficialReport() {
        var menu = AuthenticationController.menu(List.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ));

        assertThat(menu)
                .extracting(AuthenticationController.MenuItem::path)
                .contains(
                        "/attendance/reports",
                        "/attendance/queries/exceptions",
                        "/attendance/queries/leave",
                        "/attendance/queries/overtime",
                        "/attendance/queries/overtime-daily",
                        "/attendance/queries/finance-overtime",
                        "/attendance/queries/overtime-fee-daily",
                        "/attendance/queries/overtime-voluntary-daily",
                        "/attendance/queries/overtime-comp-daily",
                        "/attendance/queries/absence-stat",
                        "/attendance/queries/leave-stat",
                        "/attendance/queries/daily-journal",
                        "/attendance/queries/leave-summary",
                        "/attendance/queries/makeup",
                        "/attendance/queries/time-off-daily",
                        "/attendance/queries/work-hours",
                        "/attendance/queries/late",
                        "/attendance/queries/missed-punch",
                        "/attendance/queries/missed-punch-stat",
                        "/attendance/queries/attendance-rate",
                        "/attendance/queries/annual-leave",
                        "/attendance/queries/annual-leave-stat",
                        "/attendance/queries/time-off",
                        "/attendance/queries/time-off-stat",
                        "/attendance/queries/matrix");
    }

    @Test
    void technicalCapabilitiesAloneDoNotExposeBusinessDashboard() {
        var menu = AuthenticationController.menu(List.of(
                "ACCOUNT:READ",
                "ROLE:READ",
                "OPERATIONS:READ"));

        assertThat(menu)
                .extracting(AuthenticationController.MenuItem::path)
                .doesNotContain("/workbench", "/attendance/reports");
    }

    @Test
    void employeeSelfMenuStartsWithPersonalWorkbench() {
        var menu = AuthenticationController.menu(List.of(
                CapabilityCodes.ATTENDANCE_FEEDBACK_READ,
                CapabilityCodes.LEAVE_SELF_READ,
                CapabilityCodes.ATTENDANCE_SELF_READ));

        assertThat(menu)
                .extracting(AuthenticationController.MenuItem::path)
                .contains(
                        "/workbench",
                        "/me/today",
                        "/me/leave");
        assertThat(menu)
                .extracting(AuthenticationController.MenuItem::path)
                .doesNotContain(
                        "/attendance/reports",
                        "/attendance/queries/exceptions",
                        "/attendance/queries/makeup");
        assertThat(menu)
                .extracting(AuthenticationController.MenuItem::path)
                .doesNotContain("/me/feedback");
        assertThat(menu.getFirst())
                .isEqualTo(new AuthenticationController.MenuItem(
                        "personal-workbench",
                        "我的考勤工作台",
                        "/workbench"));
    }

    @Test
    void managementWorkbenchRemainsAheadOfSelfAndReportMenus() {
        var menu = AuthenticationController.menu(List.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                CapabilityCodes.ATTENDANCE_SELF_READ,
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ));

        assertThat(menu)
                .extracting(AuthenticationController.MenuItem::path)
                .contains(
                        "/workbench",
                        "/me/today",
                        "/attendance/reports");
        assertThat(menu.getFirst())
                .isEqualTo(new AuthenticationController.MenuItem(
                        "workbench", "考勤工作台", "/workbench"));
        assertThat(menu)
                .filteredOn(item -> item.path().equals("/workbench"))
                .hasSize(1);
    }

    @Test
    void managementMenusUsePlainBusinessLabels() {
        var menu = AuthenticationController.menu(List.of(
                "POLICY:READ",
                "ACCOUNT:READ",
                "ROLE:READ",
                "AUDIT:READ",
                "PEOPLE_IMPORT:READ",
                "ORGANIZATION:READ",
                "EMPLOYEE:READ",
                "ATTENDANCE_SETUP:READ",
                "ATTENDANCE_SOURCE:READ",
                "PAPER_OVERTIME:MANAGE",
                "ATTENDANCE_PUNCH_IMPORT:READ"));

        assertThat(menu).contains(
                new AuthenticationController.MenuItem(
                        "rules", "规则设置", "/rules"),
                new AuthenticationController.MenuItem(
                        "audit", "操作记录", "/access/audit"),
                new AuthenticationController.MenuItem(
                        "people-import", "导入人员", "/people/import"),
                new AuthenticationController.MenuItem(
                        "people-organization", "部门与组织", "/people/organization"),
                new AuthenticationController.MenuItem(
                        "people-employees", "员工", "/people/employees"),
                new AuthenticationController.MenuItem(
                        "attendance-groups", "考勤组", "/rules/attendance-groups"),
                new AuthenticationController.MenuItem(
                        "attendance-shifts", "班次", "/rules/shifts"),
                new AuthenticationController.MenuItem(
                        "attendance-policies", "考勤规则", "/rules/attendance-policy"),
                new AuthenticationController.MenuItem(
                        "attendance-sources-online", "考勤机数据", "/sources/online"),
                new AuthenticationController.MenuItem(
                        "attendance-sources-oa", "OA 单据", "/sources/oa"),
                new AuthenticationController.MenuItem(
                        "attendance-source-jobs", "同步记录", "/sources/jobs"),
                new AuthenticationController.MenuItem(
                        "attendance-punch-imports", "导入打卡文件", "/sources/attendance-excel"));
        assertThat(menu).noneMatch(item ->
                "/attendance/paper-overtime".equals(item.path()));
    }
}
