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
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                CapabilityCodes.ATTENDANCE_FEEDBACK_READ,
                CapabilityCodes.LEAVE_SELF_READ,
                CapabilityCodes.ATTENDANCE_SELF_READ));

        assertThat(menu)
                .extracting(AuthenticationController.MenuItem::path)
                .containsExactly(
                        "/workbench",
                        "/me/today",
                        "/me/leave",
                        "/me/feedback",
                        "/attendance/reports");
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
                .containsExactly(
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
}
