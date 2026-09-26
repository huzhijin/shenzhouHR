package com.szsemicon.hr.companydimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CompanyDataScopeSqlContractTest {

    private static final Path EMPLOYEE_READ = Path.of(
            "src/main/resources/mappers/EmployeeReadMapper.xml");
    private static final Path PEOPLE = Path.of(
            "src/main/resources/mappers/PeopleMapper.xml");
    private static final Path SOURCE_READ = Path.of(
            "src/main/resources/mappers/AttendanceSourceReadMapper.xml");
    private static final Path ATTENDANCE_GROUP = Path.of(
            "src/main/resources/mappers/AttendanceGroupMapper.xml");

    @Test
    void employeeListOrganizationScopeProvesEmployeeAndBothOrganizationsShareCompany()
            throws Exception {
        String visibility = between(
                Files.readString(EMPLOYEE_READ),
                "<sql id=\"visibilityPredicate\">",
                "</sql>");

        assertThat(visibility).contains(
                "assigned_organization.company_id =",
                "scoped_organization.company_id",
                "employee.company_id",
                "scoped_organization.organization_id =",
                "data_scope.organization_id",
                "closure.ancestor_organization_id = data_scope.organization_id",
                "closure.descendant_organization_id = current_assignment.organization_id");
    }

    @Test
    void peopleDetailAndEmploymentScopeRejectCrossCompanyAssignmentPollution()
            throws Exception {
        String xml = Files.readString(PEOPLE);
        String organization = between(
                xml, "<select id=\"canAccessOrganization\"", "</select>");
        String employee = between(
                xml, "<select id=\"canAccessEmployee\"", "</select>");
        String employment = between(
                xml, "<sql id=\"accessibleEmploymentScope\">", "</sql>");

        assertActiveOrganizationPair(
                organization, "scoped", "target");
        assertThat(employee).contains(
                "JOIN employee subject_employee",
                "assigned_organization.company_id =",
                "subject_employee.company_id",
                "scoped_organization.company_id =",
                "subject_employee.company_id");
        assertActiveOrganizationPair(
                employee, "scoped", "assigned");
        assertThat(employment).contains(
                "FROM employee subject_employee",
                "assigned_organization.company_id =",
                "subject_employee.company_id",
                "scoped_organization.organization_id =",
                "scope.organization_id");
        assertActiveOrganizationPair(
                employment, "scoped", "assigned");
        assertThat(count(xml, "<include refid=\"accessibleEmploymentScope\"/>"))
                .isEqualTo(2);
    }

    @Test
    void oaOrganizationAndSelfScopesRemainBoundToTheSourceCompany()
            throws Exception {
        String visibility = between(
                Files.readString(SOURCE_READ),
                "<sql id=\"oaDocumentVisibility\">",
                "</sql>");

        assertThat(count(
                        visibility,
                        "employee.company_id = source.company_id"))
                .as("company, organization and self branches")
                .isEqualTo(3);
        assertThat(visibility).contains(
                "scoped_organization.company_id =",
                "source.company_id",
                "assigned_organization.company_id =",
                "source.company_id",
                "principal.employee_id = match_decision.employee_id");
    }

    @Test
    void assignmentReadsAndWritesRejectCrossCompanyEmployeePollution()
            throws Exception {
        String xml = Files.readString(ATTENDANCE_GROUP);
        for (String statementId : java.util.List.of(
                "findAssignment",
                "findAssignmentSuccessor",
                "findAssignmentByIdempotency",
                "listAssignments",
                "countAssignments",
                "resolveAssignments",
                "hasAssignmentOverlap",
                "findAssignmentsCrossingBoundary",
                "countEffectiveAssignments")) {
            String query = between(
                    xml,
                    "<select id=\"" + statementId + "\"",
                    "</select>");
            assertThat(query).contains(
                    "JOIN attendance_group scoped",
                    "JOIN employee assigned_employee",
                    "assigned_employee.employee_id = assignment.employee_id",
                    "assigned_employee.company_id = scoped.company_id");
        }

        String count = between(
                xml, "<select id=\"countAssignments\"", "</select>");
        assertThat(count).contains(
                "WHERE scoped.attendance_group_id = #{groupId}");

        for (String statementId : java.util.List.of(
                "insertAssignment",
                "insertAssignmentSuccessor")) {
            String write = between(
                    xml,
                    "<insert id=\"" + statementId + "\"",
                    "</insert>");
            assertThat(write).contains(
                    "JOIN attendance_group scoped",
                    "JOIN employee assigned_employee",
                    "assigned_employee.employee_id = #{row.employeeId}",
                    "assigned_employee.company_id = scoped.company_id");
        }

        String rollover = between(
                xml,
                "<insert id=\"insertAssignmentRolloverSuccessor\"",
                "</insert>");
        assertThat(rollover).contains(
                "JOIN attendance_group predecessor_group",
                "JOIN attendance_group successor_group",
                "JOIN employee assigned_employee",
                "predecessor.employee_id = #{row.employeeId}",
                "predecessor_group.attendance_group_id =",
                "successor_group.attendance_group_id",
                "predecessor_group.company_id = successor_group.company_id",
                "assigned_employee.company_id = successor_group.company_id");

        String timeline = between(
                xml,
                "<insert id=\"insertAssignmentTimeline\"",
                "</insert>");
        assertThat(timeline).contains(
                "FROM attendance_group_assignment assignment",
                "JOIN attendance_group scoped",
                "JOIN employee assigned_employee",
                "assigned_employee.company_id = scoped.company_id",
                "assignment.employee_id = #{row.employeeId}");
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length())
                / token.length();
    }

    private static void assertActiveOrganizationPair(
            String sql, String scopedPrefix, String targetPrefix) {
        for (String prefix : java.util.List.of(scopedPrefix, targetPrefix)) {
            assertThat(sql).contains(
                    "organization_identity " + prefix + "_organization",
                    prefix + "_organization.identity_status = 'ACTIVE'",
                    "JOIN organization_current_projection "
                            + prefix + "_projection",
                    prefix + "_projection.organization_id =",
                    prefix + "_organization.organization_id",
                    "JOIN organization_version " + prefix + "_version",
                    prefix + "_version.organization_version_id =",
                    prefix + "_projection.current_version_id",
                    prefix + "_version.organization_id =",
                    prefix + "_version.status = 'ACTIVE'");
        }
        assertThat(count(sql, ".identity_status = 'ACTIVE'"))
                .isEqualTo(2);
        assertThat(count(sql, "_version.status = 'ACTIVE'"))
                .isEqualTo(2);
    }

    private static String between(
            String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        assertThat(from).isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        return value.substring(from, to);
    }
}
