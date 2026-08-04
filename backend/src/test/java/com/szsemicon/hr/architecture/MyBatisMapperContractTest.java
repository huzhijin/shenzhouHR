package com.szsemicon.hr.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MyBatisMapperContractTest {

    @Test
    void parsesEveryMapperAndRegistersTheFirstStageQueries() throws Exception {
        Configuration configuration = new Configuration();
        List<String> mapperResources = List.of(
                "mappers/CapabilityMapper.xml",
                "mappers/AuthenticationMapper.xml",
                "mappers/CompanyReferenceMapper.xml",
                "mappers/OrganizationReadMapper.xml",
                "mappers/EmployeeReadMapper.xml",
                "mappers/PeopleMapper.xml",
                "mappers/PolicyMapper.xml",
                "mappers/AttendanceGroupMapper.xml",
                "mappers/ShiftMapper.xml",
                "mappers/CalendarMapper.xml",
                "mappers/AttendancePolicyMapper.xml",
                "mappers/AttendancePolicyLifecycleMapper.xml",
                "mappers/AttendanceSetupIdempotencyMapper.xml",
                "mappers/AttendanceEvidenceMapper.xml",
                "mappers/AttendanceConfigurationAuthorityMapper.xml",
                "mappers/AttendancePeriodProtectionMapper.xml",
                "mappers/AttendanceSourceReadMapper.xml",
                "mappers/PunchImportReadMapper.xml");

        for (String resource : mapperResources) {
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(
                                input,
                                configuration,
                                resource,
                                configuration.getSqlFragments())
                        .parse();
            }
        }

        assertThat(configuration.getMappedStatementNames())
                .contains(
                        "com.szsemicon.hr.authorization.infrastructure.persistence.CapabilityMapper.findActiveCodes",
                        "com.szsemicon.hr.referencedata.infrastructure.persistence.CompanyReferenceMapper.findActive",
                        "com.szsemicon.hr.organization.infrastructure.persistence.OrganizationReadMapper.findCurrentVisibleTo",
                        "com.szsemicon.hr.employee.infrastructure.persistence.EmployeeReadMapper.countVisibleTo",
                        "com.szsemicon.hr.employee.infrastructure.persistence.EmployeeReadMapper.findVisibleTo",
                        "com.szsemicon.hr.people.infrastructure.persistence.PeopleMapper.findBatch",
                        "com.szsemicon.hr.people.infrastructure.persistence.PeopleMapper.insertPublication",
                        "com.szsemicon.hr.people.infrastructure.persistence.PeopleMapper.findCurrentEmployee",
                        "com.szsemicon.hr.people.infrastructure.persistence.PeopleMapper.insertPriorServiceRecord",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.findLocation",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.findLocationByIdempotency",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.listLocations",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.countLocations",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.insertLocationIdentity",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.insertLocationRevision",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.findGroup",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.findGroupByIdempotency",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.listGroups",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.countGroups",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.insertGroupIdentity",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.insertGroupRevision",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.findAssignment",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.findAssignmentSuccessor",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.findAssignmentByIdempotency",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.listAssignments",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.resolveAssignments",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.hasAssignmentOverlap",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.lockEmployee",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.insertAssignment",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.insertAssignmentSuccessor",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.countEffectiveAssignments",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.lockGroup",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.findTemplate",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.findTemplateByIdempotency",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.listTemplates",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.insertTemplate",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.findVersion",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.findVersionByIdempotency",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.listVersions",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.nextVersionNumber",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.insertVersion",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.publishVersion",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.hasPublishedOverlap",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.resolvePublishedAt",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.findCalendar",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.findCalendarByIdempotency",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.listCalendars",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.insertCalendarIdentity",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.insertCalendarVersion",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.listDays",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.findDay",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.touchCalendar",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.insertDays",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.updateDay",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.lockCalendar",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.resolvePublishedVersions",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.listBindings",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.countBindings",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.insertBindingFamily",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.insertBindingRevision",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.publishedVersionMatchesKind",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.publishedVersionDigest",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.publishedVersionParameters",
                "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.hasOtherFamily",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyMapper.resolveBindings",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.lockScope",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.countVersions",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.listVersions",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.findVersion",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.findVersionByScopedVersionId",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.maxVersionNumber",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.findPublishedAt",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.findLatestPublishedVersionId",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.insertVersion",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.lifecycleHead",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendancePolicyLifecycleMapper.insertLifecycle",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceSetupIdempotencyMapper.find",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceSetupIdempotencyMapper.insertStarted",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceSetupIdempotencyMapper.complete",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.findRawBySourceIdentity",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.findRawByFingerprint",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.insertRawFact",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.insertNormalizedRecord",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.insertMatchDecision",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.findExactEvents",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.findNearEvents",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.insertEffectiveEvent",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.insertLifecycleFact",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.insertEvidenceLink",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.insertRecalculationIntent",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceEvidenceMapper.evidenceTrace",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceConfigurationAuthorityMapper.resolveForBusinessDate",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendancePeriodProtectionMapper.resolveLatestPublished",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceSourceReadMapper.countSources",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceSourceReadMapper.listSources",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceSourceReadMapper.countJobs",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceSourceReadMapper.listJobs",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceSourceReadMapper.countOaDocuments",
                        "com.szsemicon.hr.evidenceingestion.infrastructure.persistence.AttendanceSourceReadMapper.listOaDocuments",
                        "com.szsemicon.hr.punchimport.infrastructure.persistence.PunchImportReadMapper.countBatches",
                        "com.szsemicon.hr.punchimport.infrastructure.persistence.PunchImportReadMapper.listBatches",
                        "com.szsemicon.hr.punchimport.infrastructure.persistence.PunchImportReadMapper.findBatch",
                        "com.szsemicon.hr.punchimport.infrastructure.persistence.PunchImportReadMapper.countIssues",
                        "com.szsemicon.hr.punchimport.infrastructure.persistence.PunchImportReadMapper.listIssues",
                        "com.szsemicon.hr.punchimport.infrastructure.persistence.PunchImportReadMapper.countRows",
                        "com.szsemicon.hr.punchimport.infrastructure.persistence.PunchImportReadMapper.listRows")
                .doesNotContain(
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.resolveAssignment",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.insertLocation",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.updateLocation",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.insertGroup",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.AttendanceGroupMapper.updateGroup",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.ShiftMapper.findPublishedAt",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.findCalendarForYear",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.insertCalendar",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.hasOtherActiveCalendar",
                        "com.szsemicon.hr.attendance.infrastructure.persistence.CalendarMapper.deleteDays");
    }
}
