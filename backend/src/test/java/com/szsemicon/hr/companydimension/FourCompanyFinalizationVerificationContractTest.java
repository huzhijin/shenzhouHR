package com.szsemicon.hr.companydimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FourCompanyFinalizationVerificationContractTest {

    private static final Path VERIFICATION = Path.of(
            "../deploy/mysql/verify-four-company-finalization.sql");

    @Test
    void verifierIsReadOnlyFailClosedAndCoversTheFourCompanyBoundary() throws Exception {
        String sql = Files.readString(VERIFICATION);
        String executable = sql.replaceAll("(?m)--.*$", "").toUpperCase();

        assertThat(sql).contains(
                "'SZSZ'", "'SZJN'", "'SZSC'", "'SZXY'",
                "上海昇州半导体科技有限公司",
                "上海晟州聚能半导体科技有限公司",
                "江苏神州半导体科技股份有限公司",
                "江苏芯越半导体科技有限公司",
                "2 AS organization_count, 1 AS employee_count",
                "17, 35", "130, 571", "7, 8",
                "DEFAULT_LOCATION",
                "default_location_revision_count >= 1",
                "default_location_current_timeline_count = 1",
                "seasonal_shift_version_count = 3",
                "seasonal_schedule_count = 1",
                "seasonal_schedule_revision_count = 1",
                "calendar_version_count = 2",
                "calendar_day_count = 365",
                "default_group_revision_count >= 1",
                "default_group_current_timeline_count = 1",
                "attendance_rule_count = 8",
                "explicit_policy_binding_count = 3",
                "annual_leave_policy_count = 1",
                "annual_leave_policy_lifecycle_event",
                "default_provisioning_count = 1",
                "active_admin_hr_count = 1",
                "active_admin_system_count = 1",
                "LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE",
                "W3_BASELINE_LEGAL_ENTITY",
                "current_employee_group_resolution_mismatch",
                "organization_parent_company_mismatch",
                "organization_closure_company_mismatch",
                "group_configuration_company_mismatch",
                "policy_binding_company_mismatch",
                "attendance_policy_snapshot_field_mismatch",
                "attendance_policy_snapshot_digest_mismatch",
                "archive.published_import_batch_count",
                "archive.import_publication_count",
                "archive.import_file_count",
                "archive.import_diff_count",
                "archive.import_issue_count",
                "security.archive_active_admin_grant_count",
                "security.four_company_hr_system_grant_count",
                "location_digest_mismatch",
                "attendance_group_digest_mismatch",
                "shift_version_digest_mismatch",
                "calendar_version_digest_mismatch",
                "calendar_day_digest_mismatch",
                "policy_binding_digest_mismatch",
                "default_provisioning_digest_mismatch",
                "shared_physical_location_count",
                "active_company_location_availability_count",
                "shared_location_head_count",
                "shared_location_revision_chain_mismatch",
                "company_location_availability_cardinality",
                "shared_location_projection_mismatch",
                "shared_location_revision_digest_mismatch",
                "annual_policy_source_mapping_count",
                "SET_VAR(group_concat_max_len=4000000)",
                "default_yangzhou_location_count",
                "precreated_city_location_count",
                "named_employee_assignment_count",
                "special_group_employee_count",
                "special_policy_binding_count",
                "unapproved_city_specific_shift_count",
                "fixed_city_shift_exact_version_count",
                "shanghai_matches_yangzhou_version_count",
                "赵俊君", "SZJN0012", "DEFAULT_ATTENDANCE",
                "张静", "SZST0442", "SHANGHAI_ATTENDANCE",
                "overall_status");

        assertThat(executable).contains("WITH", "SELECT");
        assertThat(executable).doesNotContain(
                "INSERT ", "UPDATE ", "DELETE ", "REPLACE ",
                "CREATE ", "ALTER ", "DROP ", "TRUNCATE ",
                "CALL ", "LOCK ", "SET ");
    }
}
