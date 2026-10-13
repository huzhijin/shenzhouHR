package com.szsemicon.hr.identityaccess.interfaces.rest;

import com.szsemicon.hr.identityaccess.interfaces.rest.AuthenticationController.MenuItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationControllerMenuTest {

    @Test
    void menu_firstPasswordChangeRequired_showsOnlyFirstPasswordChangeItem() {
        List<MenuItem> menu = AuthenticationController.menu(true, List.of());
        assertThat(menu)
                .hasSize(1)
                .extracting(MenuItem::key)
                .containsExactly("first-password-change");
    }

    @Test
    void menu_emptyCapabilitiesAfterFirstPasswordChange_showsDevelopmentLog() {
        List<MenuItem> menu = AuthenticationController.menu(false, List.of());
        assertThat(menu)
                .hasSize(1)
                .extracting(MenuItem::key)
                .containsExactly("development-log");
    }

    @Test
    void menu_userWithCapabilitiesAfterFirstPasswordChange_includesDevelopmentLog() {
        List<MenuItem> menu = AuthenticationController.menu(
                false,
                List.of("ATTENDANCE_SELF_READ"));
        assertThat(menu)
                .extracting(MenuItem::key)
                .contains("development-log");
    }

    @Test
    void menu_adminWithManyCapabilitiesAfterFirstPasswordChange_includesDevelopmentLog() {
        List<MenuItem> menu = AuthenticationController.menu(
                false,
                List.of("ATTENDANCE_DASHBOARD_READ", "ACCOUNT:READ", "ROLE:READ"));
        assertThat(menu)
                .extracting(MenuItem::key)
                .contains("development-log");
    }
}
