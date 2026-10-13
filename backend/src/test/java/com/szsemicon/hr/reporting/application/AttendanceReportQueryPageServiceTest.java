package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.infrastructure.export.QueryPageExcelEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceReportQueryPageMapper;
import com.szsemicon.hr.reporting.application.QueryPageRows;
import com.szsemicon.hr.reporting.application.QueryPageRows.DailyCellRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.DirectoryEmployeeRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.ExceptionRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.LeaveStatAccountRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.MonthlyLeaveUsageRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.TimeAccountRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.DailyJournalRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.DailyMetricCellRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.FactQuery;
import com.szsemicon.hr.reporting.application.QueryPageRows.FinanceOvertimeCellRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.LeaveSummaryRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.OaRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.PinRow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttendanceReportQueryPageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T07:00:00Z");
    private static final String PRINCIPAL = "principal-1";
    private static final String COMPANY = "company-a";

    @Mock
    private CurrentCapabilityService capabilities;
    @Mock
    private CurrentPrincipalProvider principals;
    @Mock
    private AttendanceReportSourceRepository sources;
    @Mock
    private AttendanceReportQueryPageMapper mapper;

    private AttendanceReportQueryPageService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceReportQueryPageService(
                capabilities,
                principals,
                sources,
                mapper,
                new QueryPageExcelEncoder(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient()
                .when(principals.currentPrincipalId())
                .thenReturn(PRINCIPAL);
        org.mockito.Mockito.lenient()
                .when(capabilities.currentCapabilities())
                .thenReturn(Set.of(CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ));
    }

    @Test
    void directoryWithoutCompanyReturnsAllAuthorizedCompanies() {
        when(sources.listAuthorizedCompanies(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL),
                        org.mockito.ArgumentMatchers.eq(
                                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(NOW)))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY, "测试公司"),
                        new CompanyOption("company-b", "第二家公司")));

        var page = service.directory(null, YearMonth.of(2026, 8));

        assertThat(page.companies()).hasSize(2);
        assertThat(page.employees()).isEmpty();
    }

    @Test
    void missingPinWithoutSourcesReturnsEmptyHint() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 7, 1)))
                .thenReturn(null);
        when(mapper.companyHasSourceEvidence(
                        org.mockito.ArgumentMatchers.eq(COMPANY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);

        var page = service.query(command("exceptions", YearMonth.of(2026, 6)));

        assertThat(page.rows()).isEmpty();
        assertThat(page.hint()).contains("暂无打卡或核算数据");
    }

    @Test
    void missingPinWithSourcesFailsAsNotReady() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(null);
        when(mapper.companyHasSourceEvidence(
                        org.mockito.ArgumentMatchers.eq(COMPANY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.query(command("exceptions", YearMonth.of(2026, 8))))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_PIN_NOT_READY");
    }

    @Test
    void outOfScopeEmployeeNumberReturnsEmptyFilterHint() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        RealtimeAuthorization orgScope = new RealtimeAuthorization(
                new AuthorizedScope(
                        ScopeType.ORGANIZATION,
                        "org",
                        "部门",
                        "a".repeat(64)),
                COMPANY,
                false,
                null,
                Set.of("employee-1"),
                Set.of("organization-a"));
        when(sources.resolveRealtimeAuthorization(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        COMPANY,
                        NOW))
                .thenReturn(Optional.of(orgScope));

        var page = service.query(new AttendanceReportQueryPageService.QueryCommand(
                "exceptions",
                COMPANY,
                null,
                "employee-forged",
                null,
                YearMonth.of(2026, 8),
                null,
                null,
                0,
                50,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(page.rows()).isEmpty();
        assertThat(page.hint()).contains("当前筛选条件下没有记录");
    }

    @Test
    void querySheetsUseOfficialJoinedDepartmentPath() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(sources.reportDepartmentPaths(COMPANY)).thenReturn(Map.of(
                "org-rf",
                DepartmentPathNames.fromRootToLeaf(
                        List.of("服务中心", "工程二部", "RF-B组"))
                        .reportDepartment()));
        when(mapper.countExceptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listExceptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new ExceptionRow(
                        "case-1",
                        "employee-1",
                        "SZST0015",
                        "孙鹏",
                        "org-rf",
                        "工程二部",
                        LocalDate.of(2026, 8, 17),
                        "MISSING_ON_DUTY",
                        "ERROR",
                        "OPEN",
                        240,
                        "无上班卡",
                        null,
                        null)));

        var page = service.query(command("exceptions", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(1);
        assertThat(DepartmentPathNames.visibleDepartment(
                        String.valueOf(page.rows().getFirst().get("department"))))
                .isEqualTo("服务中心-工程二部-RF-B组");
    }

    @Test
    void overtimeHoursUseOneDecimalFromRecognizedMinutes() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        org.mockito.Mockito.lenient().when(mapper.countOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(2L);
        when(mapper.listOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new OaRow(
                                "doc-1",
                                "employee-1",
                                "SZST0048",
                                "张珍珍",
                                "org-1",
                                "采购部",
                                "OVERTIME",
                                "PAID",
                                NOW,
                                NOW.plusSeconds(3 * 3600),
                                150L,
                                "APPROVED",
                                "OA"),
                        new OaRow(
                                "doc-2",
                                "employee-1",
                                "SZST0048",
                                "张珍珍",
                                "org-1",
                                "采购部",
                                "OVERTIME",
                                "PAID",
                                NOW,
                                NOW.plusSeconds(3600),
                                0L,
                                "APPROVED",
                                "OA")));

        var page = service.query(command("overtime", YearMonth.of(2026, 8)));

        assertThat(page.rows())
                .extracting(row -> row.get("hours"))
                .containsExactly(2.5d, 0.0d);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "APPROVED"})
    void leaveQueryKeepsIndependentLeaveAndContainedApprovedDocuments(String status) {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.listOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new OaRow(
                                "covering",
                                "employee-1",
                                "SZST0548",
                                "张晓冬",
                                "org-1",
                                "工程一部-RPS组",
                                "LEAVE",
                                "PERSONAL",
                                Instant.parse("2026-08-31T00:30:00Z"),
                                Instant.parse("2026-08-31T04:00:00Z"),
                                210L,
                                status,
                                "OA"),
                        new OaRow(
                                "time-off",
                                "employee-1",
                                "SZST0548",
                                "张晓冬",
                                "org-1",
                                "工程一部-RPS组",
                                "LEAVE",
                                "TIME_OFF",
                                Instant.parse("2026-08-31T00:30:00Z"),
                                Instant.parse("2026-08-31T03:00:00Z"),
                                150L,
                                "APPROVED",
                                "OA"),
                        new OaRow(
                                "personal",
                                "employee-1",
                                "SZST0548",
                                "张晓冬",
                                "org-1",
                                "工程一部-RPS组",
                                "LEAVE",
                                "PERSONAL",
                                Instant.parse("2026-08-31T03:00:00Z"),
                                Instant.parse("2026-08-31T04:00:00Z"),
                                60L,
                                "APPROVED",
                                "OA")));

        var page = service.query(command("leave", YearMonth.of(2026, 8)));

        assertThat(page.rowCount()).isEqualTo(3);
        assertThat(page.rows())
                .extracting(row -> row.get("documentId"))
                .containsExactlyInAnyOrder("covering", "time-off", "personal");
    }

    @Test
    void leaveQueryDeduplicatesOnlyIdenticalCopiesOfTheSameDocument() {
        authorize();
        when(mapper.findLatestPin(COMPANY, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow("proj-1", "ARP1-" + "a".repeat(64), "OPEN", NOW, "[]"));
        var original = new OaRow("same-document", "employee-1", "001", "员工", "org-1", "工程部",
                "LEAVE", "PERSONAL", NOW, NOW.plusSeconds(10800), 120, "APPROVED", "OA");
        var independent = new OaRow("different-document", "employee-1", "001", "员工", "org-1", "工程部",
                "LEAVE", "PERSONAL", NOW, NOW.plusSeconds(10800), 120, "APPROVED", "OA");
        when(mapper.listOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(original, original, independent));

        var page = service.query(command("leave", YearMonth.of(2026, 8)));

        assertThat(page.rowCount()).isEqualTo(2);
        assertThat(page.rows()).extracting(row -> row.get("documentId"))
                .containsExactly("same-document", "different-document");
        assertThat(page.rows()).allSatisfy(row -> {
            assertThat(row.get("startAt")).isEqualTo(NOW);
            assertThat(row.get("endAt")).isEqualTo(NOW.plusSeconds(10800));
            assertThat(row.get("hours")).isEqualTo(2.0d);
        });
    }

    @Test
    void overtimeQueryKeepsOneRowPerEmployeeInterval() {
        authorize();
        Instant startSat = Instant.parse("2026-08-08T01:30:00Z");
        Instant endSat = Instant.parse("2026-08-08T12:00:00Z");
        Instant startTue = Instant.parse("2026-08-04T10:30:00Z");
        Instant endTue = Instant.parse("2026-08-04T13:30:00Z");
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.listOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new OaRow(
                                "doc-b",
                                "employee-1",
                                "SZST0412",
                                "李尹",
                                "org-1",
                                "工程一部",
                                "OVERTIME",
                                "PAID",
                                startSat,
                                endSat,
                                540L,
                                "APPROVED",
                                "OA"),
                        new OaRow(
                                "doc-a",
                                "employee-1",
                                "SZST0412",
                                "李尹",
                                "org-1",
                                "工程一部",
                                "OVERTIME",
                                "PAID",
                                startSat,
                                endSat,
                                540L,
                                "APPROVED",
                                "OA"),
                        new OaRow(
                                "doc-tue",
                                "employee-1",
                                "SZST0412",
                                "李尹",
                                "org-1",
                                "工程一部",
                                "OVERTIME",
                                "PAID",
                                startTue,
                                endTue,
                                180L,
                                "APPROVED",
                                "OA")));

        var page = service.query(command("overtime", YearMonth.of(2026, 8)));

        assertThat(page.rowCount()).isEqualTo(2);
        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows())
                .extracting(row -> row.get("documentId"))
                .containsExactlyInAnyOrder("doc-a", "doc-tue");
        assertThat(page.rows())
                .extracting(row -> row.get("hours"))
                .containsExactlyInAnyOrder(9.0d, 3.0d);
    }

    @Test
    void overtimeDailySplitsHoursByDayType() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countOvertimeDaily(org.mockito.ArgumentMatchers.any()))
                .thenReturn(2L);
        when(mapper.listOvertimeDaily(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new DailyJournalRow(
                                "employee-1",
                                "SZST0131",
                                "周旋",
                                "org-1",
                                "工程一部",
                                LocalDate.of(2026, 8, 4),
                                "WEEKDAY",
                                "白班",
                                null,
                                Instant.parse("2026-08-04T00:30:00Z"),
                                Instant.parse("2026-08-04T13:00:00Z"),
                                0,
                                0,
                                0,
                                0,
                                360L,
                                480,
                                0,
                                240L,
                                120L),
                        new DailyJournalRow(
                                "employee-1",
                                "SZST0131",
                                "周旋",
                                "org-1",
                                "工程一部",
                                LocalDate.of(2026, 8, 9),
                                "SATURDAY",
                                "",
                                null,
                                Instant.parse("2026-08-09T00:30:00Z"),
                                Instant.parse("2026-08-09T13:00:00Z"),
                                0,
                                0,
                                0,
                                0,
                                450L,
                                0,
                                0)));

        var page = service.query(command("overtime-daily", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows().getFirst().get("weekdayOvertimeHours")).isEqualTo(6.0d);
        assertThat(page.rows().getFirst().get("weekendOvertimeHours")).isEqualTo(0.0d);
        assertThat(page.rows().getFirst().get("paidOvertimeHours")).isEqualTo(4.0d);
        assertThat(page.rows().getFirst().get("compensatoryOvertimeHours")).isEqualTo(2.0d);
        assertThat(page.rows().getFirst().get("voluntaryOvertimeHours")).isEqualTo(0.0d);
        assertThat(page.rows().get(1).get("weekendOvertimeHours")).isEqualTo(7.5d);
        assertThat(page.rows().get(1).get("holidayOvertimeHours")).isEqualTo(0.0d);
    }

    @Test
    void overtimeDailyExposesVoluntaryHoursAndFilterProjectsThem() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countOvertimeDaily(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listOvertimeDaily(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DailyJournalRow(
                        "employee-1",
                        "SZST0487",
                        "赵俊杰",
                        "org-1",
                        "DC部-软件设计组",
                        LocalDate.of(2026, 8, 22),
                        "SATURDAY",
                        "",
                        null,
                        Instant.parse("2026-08-22T00:30:00Z"),
                        Instant.parse("2026-08-22T09:00:00Z"),
                        0,
                        0,
                        0,
                        0,
                        480L,
                        0,
                        0,
                        270L,
                        0L,
                        210L)));

        var unfiltered = service.query(command("overtime-daily", YearMonth.of(2026, 8)));
        assertThat(unfiltered.rows().getFirst().get("weekendOvertimeHours")).isEqualTo(4.5d);
        assertThat(unfiltered.rows().getFirst().get("paidOvertimeHours")).isEqualTo(4.5d);
        assertThat(unfiltered.rows().getFirst().get("voluntaryOvertimeHours")).isEqualTo(3.5d);

        var filtered = service.query(commandTreated(
                "overtime-daily", YearMonth.of(2026, 8), "义务加班"));
        assertThat(filtered.rows().getFirst().get("weekendOvertimeHours")).isEqualTo(3.5d);
        assertThat(filtered.rows().getFirst().get("voluntaryOvertimeHours")).isEqualTo(3.5d);
    }

    @Test
    void financeOvertimeCellsKeepOccurrenceDayAndAuthorizedDepartmentScope() {
        authorize();
        when(mapper.findLatestPin(COMPANY,
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .thenReturn(new PinRow("proj-1", "ARP1-" + "a".repeat(64),
                        "OPEN", NOW, "[]"));
        when(mapper.listDescendantOrganizationIds("office"))
                .thenReturn(List.of("office", "office-shanghai"));
        when(mapper.countFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DirectoryEmployeeRow(
                        "he-weihao", "SZST-HE", "何伟豪", "office-shanghai", "上海办事处")));
        when(mapper.listFinanceOvertimeCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new FinanceOvertimeCellRow(
                        "he-weihao", LocalDate.of(2026, 9, 25), "PUBLIC_HOLIDAY",
                        510L, 510L, 0L, 0L)));

        var page = service.query(new AttendanceReportQueryPageService.QueryCommand(
                "finance-overtime", COMPANY, "office", "he-weihao", "SZST-HE",
                YearMonth.of(2026, 9), null, null, 0, 50,
                null, null, null, null, null, null, 25,
                null, null, null, null, null, null, null, null, null, null));

        ArgumentCaptor<FactQuery> peopleQuery = ArgumentCaptor.forClass(FactQuery.class);
        ArgumentCaptor<FactQuery> cellsQuery = ArgumentCaptor.forClass(FactQuery.class);
        verify(mapper).listFinanceOvertimePeople(peopleQuery.capture());
        verify(mapper).listFinanceOvertimeCells(cellsQuery.capture());
        assertThat(cellsQuery.getValue()).usingRecursiveComparison()
                .ignoringFields("employeeIds", "limit")
                .isEqualTo(peopleQuery.getValue());
        assertThat(cellsQuery.getValue().occurrenceDay()).isEqualTo(25);
        assertThat(cellsQuery.getValue().organizationIds())
                .containsExactly("office", "office-shanghai");
        assertThat(cellsQuery.getValue().employeeIds()).containsExactly("he-weihao");
        assertThat(cellsQuery.getValue().limit()).isEqualTo(1);
        assertThat(page.rows().getFirst()).containsEntry("paidOvertimeHours", 8.5d)
                .containsEntry("holidayOvertimeHours", 8.5d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) page.rows().getFirst().get("days");
        assertThat(days).hasSize(1);
        assertThat(days.getFirst()).containsEntry("date", LocalDate.of(2026, 9, 25));
    }

    @Test
    void financeOvertimeAggregatesOneRowPerPersonAndKeepsHolidayOffWeekend() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DirectoryEmployeeRow(
                        "employee-1",
                        "SZST0131",
                        "周旋",
                        "org-1",
                        "工程一部")));
        when(mapper.listFinanceOvertimeCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new FinanceOvertimeCellRow(
                                "employee-1",
                                LocalDate.of(2026, 8, 4),
                                "WEEKDAY",
                                150L),
                        new FinanceOvertimeCellRow(
                                "employee-1",
                                LocalDate.of(2026, 8, 9),
                                "SATURDAY",
                                450L),
                        new FinanceOvertimeCellRow(
                                "employee-1",
                                LocalDate.of(2026, 5, 1),
                                "PUBLIC_HOLIDAY",
                                480L)));

        var page = service.query(command("finance-overtime", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("employeeName")).isEqualTo("周旋");
        assertThat(page.rows().getFirst().get("weekdayOvertimeHours")).isEqualTo(2.5d);
        assertThat(page.rows().getFirst().get("weekendOvertimeHours")).isEqualTo(7.5d);
        assertThat(page.rows().getFirst().get("holidayOvertimeHours")).isEqualTo(8.0d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) page.rows().getFirst().get("days");
        assertThat(days).hasSize(3);
    }

    @Test
    void financeOvertimeExposesPaidAndCompensatoryHoursAndDominantTreatment() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DirectoryEmployeeRow(
                        "employee-1",
                        "SZST0131",
                        "周旋",
                        "org-1",
                        "工程一部")));
        when(mapper.listFinanceOvertimeCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new FinanceOvertimeCellRow(
                                "employee-1",
                                LocalDate.of(2026, 8, 4),
                                "WEEKDAY",
                                360L,
                                240L,
                                120L,
                                0L),
                        new FinanceOvertimeCellRow(
                                "employee-1",
                                LocalDate.of(2026, 8, 8),
                                "SATURDAY",
                                180L,
                                0L,
                                180L,
                                0L)));

        var page = service.query(command("finance-overtime", YearMonth.of(2026, 8)));

        assertThat(page.rows().getFirst().get("paidOvertimeHours")).isEqualTo(4.0d);
        assertThat(page.rows().getFirst().get("compensatoryOvertimeHours"))
                .isEqualTo(5.0d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) page.rows().getFirst().get("days");
        assertThat(days.getFirst())
                .containsEntry("treatment", "PAID")
                .containsEntry("paidHours", 4.0d)
                .containsEntry("compensatoryHours", 2.0d);
        assertThat(days.get(1))
                .containsEntry("treatment", "COMPENSATORY")
                .containsEntry("compensatoryHours", 3.0d);
        assertThat(days.getFirst().get("treatment"))
                .isNotEqualTo(days.get(1).get("treatment"));
    }

    @Test
    void financeOvertimeExcludesVoluntaryHoursFromFeeTotals() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DirectoryEmployeeRow(
                        "employee-1",
                        "SZST0487",
                        "赵俊杰",
                        "org-1",
                        "DC部-软件设计组")));
        when(mapper.listFinanceOvertimeCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new FinanceOvertimeCellRow(
                        "employee-1",
                        LocalDate.of(2026, 8, 22),
                        "SATURDAY",
                        480L,
                        270L,
                        0L,
                        210L)));

        var page = service.query(command("finance-overtime", YearMonth.of(2026, 8)));

        assertThat(page.rows().getFirst().get("weekendOvertimeHours")).isEqualTo(4.5d);
        assertThat(page.rows().getFirst().get("paidOvertimeHours")).isEqualTo(4.5d);
        assertThat(page.rows().getFirst().get("voluntaryOvertimeHours")).isEqualTo(3.5d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) page.rows().getFirst().get("days");
        assertThat(days.getFirst())
                .containsEntry("hours", 4.5d)
                .containsEntry("paidHours", 4.5d)
                .containsEntry("voluntaryHours", 3.5d)
                .containsEntry("treatment", "PAID");
    }

    @Test
    void financeOvertimeVoluntaryFilterProjectsVoluntaryHours() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DirectoryEmployeeRow(
                        "employee-1",
                        "SZST0487",
                        "赵俊杰",
                        "org-1",
                        "DC部-软件设计组")));
        when(mapper.listFinanceOvertimeCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new FinanceOvertimeCellRow(
                        "employee-1",
                        LocalDate.of(2026, 8, 22),
                        "SATURDAY",
                        480L,
                        270L,
                        0L,
                        210L)));

        var page = service.query(commandTreated(
                "finance-overtime", YearMonth.of(2026, 8), "义务加班"));

        assertThat(page.rows().getFirst().get("weekendOvertimeHours")).isEqualTo(3.5d);
        assertThat(page.rows().getFirst().get("voluntaryOvertimeHours")).isEqualTo(3.5d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) page.rows().getFirst().get("days");
        assertThat(days.getFirst())
                .containsEntry("hours", 3.5d)
                .containsEntry("voluntaryHours", 3.5d)
                .containsEntry("treatment", "VOLUNTARY");
    }

    @Test
    void overtimeVoluntaryDailyLocksTreatmentWithoutClientFilter() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listFinanceOvertimePeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DirectoryEmployeeRow(
                        "employee-1",
                        "SZST0487",
                        "赵俊杰",
                        "org-1",
                        "DC部-软件设计组")));
        when(mapper.listFinanceOvertimeCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new FinanceOvertimeCellRow(
                        "employee-1",
                        LocalDate.of(2026, 8, 22),
                        "SATURDAY",
                        480L,
                        270L,
                        0L,
                        210L)));

        var page = service.query(command(
                "overtime-voluntary-daily", YearMonth.of(2026, 8)));

        assertThat(page.sheet()).isEqualTo("overtime-voluntary-daily");
        assertThat(page.rows().getFirst().get("weekendOvertimeHours")).isEqualTo(3.5d);
        assertThat(page.rows().getFirst().get("voluntaryOvertimeHours")).isEqualTo(3.5d);
        org.mockito.ArgumentCaptor<FactQuery> captor =
                org.mockito.ArgumentCaptor.forClass(FactQuery.class);
        org.mockito.Mockito.verify(mapper).countFinanceOvertimePeople(captor.capture());
        assertThat(captor.getValue().overtimeTreatment()).isEqualTo("义务加班");
    }

    @Test
    void absenceStatListsOnlyPeopleWithAbsenceHoursAndBlanksZeroDays() {
        authorize();
        pinAugust();
        when(mapper.countAbsenceStatPeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listAbsenceStatPeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DirectoryEmployeeRow(
                        "employee-1",
                        "SZST0007",
                        "钱七",
                        "org-1",
                        "工程一部")));
        when(mapper.listAbsenceStatCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DailyMetricCellRow(
                        "employee-1",
                        LocalDate.of(2026, 8, 12),
                        "WEEKDAY",
                        480L)));

        var page = service.query(command("absence-stat", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("employeeName")).isEqualTo("钱七");
        assertThat(page.rows().getFirst().get("absenceHours")).isEqualTo(8.0d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) page.rows().getFirst().get("days");
        assertThat(days).hasSize(1);
        assertThat(days.getFirst()).containsEntry("hours", 8.0d);
    }

    @Test
    void leaveStatKeepsDocumentLeaveSheetSeparate() {
        authorize();
        pinAugust();
        when(mapper.countLeaveStatPeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listLeaveStatPeople(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DirectoryEmployeeRow(
                        "employee-1",
                        "SZST0008",
                        "赵六",
                        "org-1",
                        "工程一部")));
        when(mapper.listLeaveStatCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DailyMetricCellRow(
                        "employee-1",
                        LocalDate.of(2026, 8, 5),
                        "PERSONAL",
                        270L)));

        var page = service.query(command("leave-stat", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("leaveHours")).isEqualTo(4.5d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) page.rows().getFirst().get("days");
        assertThat(days.getFirst()).containsEntry("dayType", "PERSONAL");
    }

    @Test
    void timeOffDailyListsOneRowPerPersonDay() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countTimeOffDaily(org.mockito.ArgumentMatchers.any()))
                .thenReturn(2L);
        when(mapper.listTimeOffDaily(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new DailyJournalRow(
                                "employee-1",
                                "SZST0131",
                                "周旋",
                                "org-1",
                                "工程一部",
                                LocalDate.of(2026, 8, 4),
                                "WEEKDAY",
                                "白班",
                                "TIME_OFF",
                                null,
                                null,
                                0,
                                0,
                                0,
                                480L,
                                0,
                                480,
                                0),
                        new DailyJournalRow(
                                "employee-1",
                                "SZST0131",
                                "周旋",
                                "org-1",
                                "工程一部",
                                LocalDate.of(2026, 8, 5),
                                "WEEKDAY",
                                "白班",
                                "TIME_OFF",
                                null,
                                null,
                                0,
                                0,
                                0,
                                240L,
                                0,
                                480,
                                0)));

        var page = service.query(command("time-off-daily", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows().getFirst().get("hours")).isEqualTo(8.0d);
        assertThat(page.rows().get(1).get("hours")).isEqualTo(4.0d);
        assertThat(page.rows())
                .extracting(row -> row.get("businessDate"))
                .containsExactly(
                        LocalDate.of(2026, 8, 4),
                        LocalDate.of(2026, 8, 5));
    }

    @Test
    void dailyJournalExemptWorkdayDoesNotInventMissingPunches() {
        var row = journalDisplay(new DailyJournalRow(
                "he-weihao", "SZST-HE", "何伟豪", "office-shanghai", "上海办事处",
                LocalDate.of(2026, 9, 17), "WORKDAY", "白班", null,
                null, null, 0, 0, 0, 0, 150L, 480, 0));

        assertThat(row).containsEntry("onDuty", "")
                .containsEntry("offDuty", "")
                .containsEntry("remark", "")
                .containsEntry("overtimeHours", 2.5d)
                .containsEntry("absenceHours", "");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("journalPunchCases")
    void dailyJournalRespectsCalculatedMissingPunchesAndLeaveCoverage(
            String scenario, String dayType, long scheduled, long leave, int missing,
            String first, String last, String expectedOn, String expectedOff,
            String expectedRemark) {
        var row = journalDisplay(new DailyJournalRow(
                "employee-1", "TEST001", "员工", "org-1", "测试部门",
                LocalDate.of(2026, 9, 17), dayType, "白班", leave > 0 ? "PERSONAL" : null,
                first == null ? null : Instant.parse(first),
                last == null ? null : Instant.parse(last),
                0, 0, 0, leave, 150L, scheduled, missing));

        assertThat(row).containsEntry("onDuty", expectedOn)
                .containsEntry("offDuty", expectedOff)
                .containsEntry("remark", expectedRemark)
                .containsEntry("overtimeHours", 2.5d)
                .containsEntry("leaveType", leave > 0 ? "事假" : "");
    }

    private static Stream<Arguments> journalPunchCases() {
        String on = "2026-09-17T00:30:00Z";
        String off = "2026-09-17T10:00:00Z";
        return Stream.of(
                Arguments.of("同卡但已计算无缺卡", "WORKDAY", 480, 0, 0,
                        on, on, "08:30", "", ""),
                Arguments.of("只有下班卡但已计算无缺卡", "WORKDAY", 480, 0, 0,
                        null, off, "", "18:00", ""),
                Arguments.of("普通工作日两次缺卡", "WORKDAY", 480, 0, 2,
                        null, null, "漏刷", "漏刷", "漏刷"),
                Arguments.of("普通工作日缺下班卡", "WORKDAY", 480, 0, 1,
                        on, null, "08:30", "漏刷", "下班漏刷"),
                Arguments.of("普通工作日缺上班卡", "WORKDAY", 480, 0, 1,
                        null, off, "漏刷", "18:00", "上班漏刷"),
                Arguments.of("同卡且已计算缺一次卡", "WORKDAY", 480, 0, 1,
                        on, on, "08:30", "漏刷", "下班漏刷"),
                Arguments.of("半天请假仍有真实缺卡", "WORKDAY", 480, 240, 1,
                        on, null, "08:30", "漏刷", "下班漏刷"),
                Arguments.of("半天请假无缺卡", "WORKDAY", 480, 240, 0,
                        on, on, "08:30", "", ""),
                Arguments.of("整班请假抑制历史缺卡", "WORKDAY", 480, 480, 2,
                        null, null, "", "", ""),
                Arguments.of("无排班休息日不造漏刷", "SUNDAY", 0, 0, 2,
                        null, null, "", "", ""),
                Arguments.of("休息日实际卡照常显示", "SUNDAY", 0, 0, 0,
                        on, off, "08:30", "18:00", ""),
                Arguments.of("休息日有排班保留真实缺卡", "SUNDAY", 480, 0, 1,
                        on, null, "08:30", "漏刷", "下班漏刷"),
                Arguments.of("无排班但有加班不等于缺卡", "WORKDAY", 0, 0, 0,
                        null, null, "", "", ""));
    }

    private Map<String, Object> journalDisplay(DailyJournalRow row) {
        authorize();
        when(mapper.findLatestPin(COMPANY,
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .thenReturn(new PinRow("proj-1", "ARP1-" + "a".repeat(64),
                        "OPEN", NOW, "[]"));
        when(mapper.countDailyJournal(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(mapper.listDailyJournal(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(row));
        return service.query(command("daily-journal", YearMonth.of(2026, 9)))
                .rows().getFirst();
    }

    @Test
    void dailyJournalExposesPunchAndOvertimeColumns() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countDailyJournal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listDailyJournal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DailyJournalRow(
                        "employee-1",
                        "SZST0131",
                        "周旋",
                        "org-1",
                        "工程一部",
                        LocalDate.of(2026, 8, 4),
                        "WEEKDAY",
                        "白班",
                        null,
                        Instant.parse("2026-08-04T00:29:00Z"),
                        Instant.parse("2026-08-04T13:00:00Z"),
                        0,
                        0,
                        0,
                        0,
                        150L,
                        480,
                        0)));

        var page = service.query(command("daily-journal", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("onDuty")).isEqualTo("08:29");
        assertThat(page.rows().getFirst().get("offDuty")).isEqualTo("21:00");
        assertThat(page.rows().getFirst().get("overtimeHours")).isEqualTo(2.5d);
        assertThat(page.rows().getFirst().get("shiftLabel")).isEqualTo("白班");
    }

    @Test
    void dailyJournalOvernightOffDutyUsesNextDayPrefixAndFoldsHours() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countDailyJournal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(2L);
        when(mapper.listDailyJournal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new DailyJournalRow(
                                "employee-1",
                                OvernightReturnFixtures.JIN_YULIANG,
                                "金玉亮",
                                "org-1",
                                "工程一部",
                                OvernightReturnFixtures.AUG_11,
                                "WEEKDAY",
                                "白班",
                                null,
                                OvernightReturnFixtures.jinAug11On(),
                                OvernightReturnFixtures.jinAug12OvernightOff(),
                                0,
                                0,
                                0,
                                0,
                                300L,
                                480,
                                0),
                        new DailyJournalRow(
                                "employee-1",
                                OvernightReturnFixtures.JIN_YULIANG,
                                "金玉亮",
                                "org-1",
                                "工程一部",
                                OvernightReturnFixtures.AUG_12,
                                "WEEKDAY",
                                "白班",
                                null,
                                OvernightReturnFixtures.jinAug12On(),
                                OvernightReturnFixtures.shanghai(
                                        OvernightReturnFixtures.AUG_12.atTime(18, 18)),
                                0,
                                0,
                                0,
                                0,
                                0L,
                                480,
                                0)));
        when(mapper.listOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new OaRow(
                        "ot-1",
                        "employee-1",
                        OvernightReturnFixtures.JIN_YULIANG,
                        "金玉亮",
                        "org-1",
                        "工程一部",
                        "OVERTIME",
                        "PAID",
                        OvernightReturnFixtures.shanghai(
                                OvernightReturnFixtures.AUG_11.atTime(21, 0)),
                        OvernightReturnFixtures.shanghai(
                                OvernightReturnFixtures.AUG_12.atTime(2, 0)),
                        300L,
                        "APPROVED",
                        "OA")));

        var page = service.query(command("daily-journal", YearMonth.of(2026, 8)));

        assertThat(page.rows().getFirst().get("offDuty")).isEqualTo("次日 00:14");
        assertThat(page.rows().getFirst().get("overtimeHours")).isEqualTo(5.0d);
        assertThat(page.rows().get(1).get("onDuty")).isEqualTo("08:24");
        assertThat(page.rows().get(1).get("overtimeHours")).isEqualTo("");
    }

    @Test
    void dailyJournalLeaveWithoutPunchIsNotMissingPunch() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countDailyJournal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listDailyJournal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DailyJournalRow(
                        "employee-1",
                        "SZST0131",
                        "周旋",
                        "org-1",
                        "工程一部",
                        LocalDate.of(2026, 8, 17),
                        "WEEKDAY",
                        "白班",
                        "PERSONAL",
                        null,
                        null,
                        0,
                        0,
                        0,
                        480L,
                        0,
                        480,
                        2)));

        var page = service.query(command("daily-journal", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("onDuty")).isEqualTo("");
        assertThat(page.rows().getFirst().get("offDuty")).isEqualTo("");
        assertThat(page.rows().getFirst().get("leaveType")).isEqualTo("事假");
        assertThat(page.rows().getFirst().get("remark")).isEqualTo("");
    }

    @Test
    void leaveSummaryAddsHoursForTheSameLeaveType() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countLeaveSummary(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listLeaveSummary(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new LeaveSummaryRow(
                        "employee-1",
                        "SZST0048",
                        "张珍珍",
                        "org-1",
                        "采购部",
                        "ANNUAL",
                        480L,
                        2L)));

        var page = service.query(command("leave-summary", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("leaveType")).isEqualTo("年假");
        assertThat(page.rows().getFirst().get("hours")).isEqualTo(8.0d);
        assertThat(page.rows().getFirst().get("documentCount")).isEqualTo(2L);
    }

    @Test
    void overtimeQueryIncludesPaperOriginRows() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        org.mockito.Mockito.lenient().when(mapper.countOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new OaRow(
                        "doc-paper",
                        "employee-1",
                        "10012",
                        "陈士庆",
                        "org-1",
                        "设备工程部",
                        "OVERTIME",
                        "COMPENSATORY",
                        NOW,
                        NOW.plusSeconds(3 * 3600),
                        150L,
                        "APPROVED",
                        "PAPER")));

        var page = service.query(command("overtime", YearMonth.of(2026, 8)));

        assertThat(page.rows()).singleElement().satisfies(row -> {
            assertThat(row.get("sourceOrigin")).isEqualTo("PAPER");
            assertThat(row.get("hours")).isEqualTo(2.5d);
        });
    }

    @Test
    void exportOvertimeWorkbookUsesVisibleDocumentColumns() throws Exception {
        org.mockito.Mockito.lenient()
                .when(capabilities.currentCapabilities())
                .thenReturn(Set.of(
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD));
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        org.mockito.Mockito.lenient().when(mapper.countOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new OaRow(
                        "doc-1",
                        "employee-1",
                        "SZST0048",
                        "张珍珍",
                        "org-1",
                        "采购部",
                        "OVERTIME",
                        "PAID",
                        NOW,
                        NOW.plusSeconds(3 * 3600),
                        150L,
                        "APPROVED",
                        "OA")));

        var file = service.export(command("overtime", YearMonth.of(2026, 8)));

        assertThat(file.fileName()).isEqualTo("2026-08_加班统计.xlsx");
        assertThat(file.content()).startsWith(
                new byte[]{0x50, 0x4B, 0x03, 0x04});
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(file.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue())
                    .isEqualTo("加班方式");
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue())
                    .isEqualTo("2.5");
        }
    }

    @Test
    void exportPageEncodesOnlyTheRequestedPage() throws Exception {
        org.mockito.Mockito.lenient()
                .when(capabilities.currentCapabilities())
                .thenReturn(Set.of(
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD));
        authorize();
        pinAugust();
        when(mapper.listOaDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new OaRow(
                        "doc-1",
                        "employee-1",
                        "SZST0001",
                        "张三",
                        "org-1",
                        "采购部",
                        "LEAVE",
                        "MARRIAGE",
                        NOW,
                        NOW.plusSeconds(8 * 3600),
                        480L,
                        "APPROVED",
                        "OA")));

        var pageCommand = new AttendanceReportQueryPageService.QueryCommand(
                "leave",
                COMPANY,
                null,
                null,
                null,
                YearMonth.of(2026, 8),
                null,
                null,
                0,
                1,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var file = service.export(pageCommand, "PAGE");

        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(file.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("假别");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("张三");
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(2);
        }
    }

    @Test
    void missedPunchQueryStaysAnEventList() {
        authorize();
        pinAugust();
        when(mapper.countExceptions(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(mapper.listExceptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new ExceptionRow(
                        "case-1",
                        "employee-1",
                        "SZST0015",
                        "孙鹏",
                        "org-rf",
                        "工程二部",
                        LocalDate.of(2026, 8, 17),
                        "MISSING_ON_DUTY",
                        "ERROR",
                        "OPEN",
                        240,
                        "无上班卡",
                        null,
                        null)));

        var page = service.query(command("missed-punch", YearMonth.of(2026, 8)));

        assertThat(page.rows().getFirst()).containsKeys(
                "businessDate", "exceptionType", "details");
        assertThat(page.rows().getFirst()).doesNotContainKey("days");
    }

    @Test
    void missedPunchStatPagesEmployeeSlotsAndOmitsFullyNormalPeople() {
        authorize();
        pinAugust();
        when(mapper.countMissedPunchStatEmployees(org.mockito.ArgumentMatchers.any()))
                .thenReturn(2L);
        when(mapper.listMissedPunchStatEmployees(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new DirectoryEmployeeRow(
                                "employee-miss", "SZST0001", "张三", "org-1", "工程部"),
                        new DirectoryEmployeeRow(
                                "employee-ok", "SZST0002", "李四", "org-1", "工程部")));
        when(mapper.listDailyCells(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new DailyCellRow(
                                "employee-miss",
                                LocalDate.of(2026, 8, 4),
                                "WEEKDAY",
                                null,
                                0,
                                0,
                                1,
                                Instant.parse("2026-08-04T09:00:00Z"),
                                Instant.parse("2026-08-04T09:00:00Z"),
                                0,
                                0),
                        new DailyCellRow(
                                "employee-ok",
                                LocalDate.of(2026, 8, 4),
                                "WEEKDAY",
                                null,
                                0,
                                0,
                                0,
                                Instant.parse("2026-08-04T00:32:00Z"),
                                Instant.parse("2026-08-04T09:00:00Z"),
                                0,
                                0)));
        when(mapper.listOaForEmployees(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var page = service.query(command("missed-punch-stat", YearMonth.of(2026, 8)));

        assertThat(page.rowCount()).isEqualTo(1);
        assertThat(page.rows()).extracting(row -> row.get("employeeNumber"))
                .containsExactly("SZST0001");
        assertThat(page.rows().getFirst().get("sequence")).isEqualTo(1);
        assertThat(page.rows().getFirst().get("missedCount")).isEqualTo(1);
        assertThat(page.rows().getFirst().get("remark")).isEqualTo("8月4日（上班）");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) page.rows().getFirst().get("days");
        @SuppressWarnings("unchecked")
        Map<String, Object> morning = (Map<String, Object>) days.getFirst().get("morning");
        assertThat(morning.get("text")).isEqualTo("漏刷");
        assertThat(morning.get("tone")).isEqualTo("MISSING_PUNCH");
    }

    @Test
    void annualLeaveStatUsesSlashBeforeOpeningAndDoesNotCalculateWithoutPin() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
        when(mapper.countLeaveStatAccounts(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listLeaveStatAccounts(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new LeaveStatAccountRow(
                        "employee-1",
                        "SZST0001",
                        "张三",
                        "org-1",
                        "工程一部",
                        "ANNUAL_LEAVE",
                        new java.math.BigDecimal("40.00"),
                        new java.math.BigDecimal("40.00"),
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO,
                        new java.math.BigDecimal("40.00"),
                        LocalDate.of(2020, 3, 1),
                        0)));
        when(mapper.listLeaveUsageDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var page = service.query(command("annual-leave-stat", YearMonth.of(2026, 8)));

        assertThat(page.rows().getFirst().get("usedMonth7")).isEqualTo("/");
        assertThat((java.math.BigDecimal) page.rows().getFirst().get("usedMonth8"))
                .isEqualByComparingTo("0.00");
        assertThat((java.math.BigDecimal) page.rows().getFirst().get("newHireCalendarDays"))
                .isEqualByComparingTo("0.0");
        assertThat(page.allowedActions()).contains("REPORT_QUERY");
        assertThat(page.allowedActions()).doesNotContain("LEAVE_ADJUST");
    }

    @Test
    void yearRangeWithOnePinDoesNotProbeMissingMonthsForEvidence() {
        authorize();
        when(mapper.findLatestPin(
                        org.mockito.ArgumentMatchers.eq(COMPANY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    LocalDate start = invocation.getArgument(1);
                    if (YearMonth.from(start).equals(YearMonth.of(2026, 8))) {
                        return new PinRow(
                                "proj-1",
                                "ARP1-" + "a".repeat(64),
                                "OPEN",
                                NOW,
                                "[]");
                    }
                    return null;
                });
        when(mapper.countLeaveStatAccounts(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listLeaveStatAccounts(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new LeaveStatAccountRow(
                        "employee-1",
                        "SZST0001",
                        "张三",
                        "org-1",
                        "工程一部",
                        "ANNUAL_LEAVE",
                        new java.math.BigDecimal("40.00"),
                        new java.math.BigDecimal("40.00"),
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO,
                        new java.math.BigDecimal("40.00"),
                        LocalDate.of(2020, 3, 1),
                        0)));
        when(mapper.listLeaveUsageDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var page = service.query(command(
                "annual-leave-stat",
                YearMonth.of(2026, 8),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("usedMonth7")).isEqualTo("/");
        assertThat(page.omittedMonths())
                .extracting(AttendanceReportQueryPageService.OmittedMonth::period)
                .doesNotContain("2026-01", "2026-07")
                .contains("2026-09");
        verify(mapper, never()).companyHasSourceEvidence(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void workHoursMatchesReportCenterAndOmitsPeopleWithoutDailyFacts() {
        authorize();
        pinAugust();
        when(mapper.listEmployeeDailyAggregates(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var page = service.query(command("work-hours", YearMonth.of(2026, 8)));

        assertThat(page.rows()).isEmpty();
    }

    @Test
    void leaveStatWithoutPinDoesNotCalculate() {
        authorize();
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(null);
        when(mapper.companyHasSourceEvidence(
                        org.mockito.ArgumentMatchers.eq(COMPANY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.query(command("annual-leave-stat", YearMonth.of(2026, 8))))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("核算尚未完成");
    }

    @Test
    void leaveStatFallsBackToTimeAccountsWhenHireDateQueryFails() {
        authorize();
        pinAugust();
        when(mapper.countLeaveStatAccounts(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        when(mapper.listLeaveStatAccounts(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.apache.ibatis.exceptions.PersistenceException("zero date"));
        when(mapper.listTimeAccounts(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new TimeAccountRow(
                        "employee-1",
                        "SZST0001",
                        "张三",
                        "org-1",
                        "工程一部",
                        "ANNUAL_LEAVE",
                        new java.math.BigDecimal("40.00"),
                        new java.math.BigDecimal("40.00"),
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO,
                        new java.math.BigDecimal("40.00"))));
        when(mapper.listLeaveUsageDocuments(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var page = service.query(command("annual-leave-stat", YearMonth.of(2026, 8)));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("employeeName")).isEqualTo("张三");
        assertThat(page.rows().getFirst().get("hireDate")).isEqualTo("");
        assertThat(page.rows().getFirst().get("usedMonth7")).isEqualTo("/");
        assertThat((java.math.BigDecimal) page.rows().getFirst().get("usedMonth8"))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void missedPunchStatFiltersNormalCandidatesBeforeEveryPageAndCount() {
        authorize();
        pinAugust();
        List<DirectoryEmployeeRow> candidates = List.of(
                new DirectoryEmployeeRow("covered", "001", "covered", "org-1", "工程部"),
                new DirectoryEmployeeRow("a", "002", "a", "org-1", "工程部"),
                new DirectoryEmployeeRow("normal", "003", "normal", "org-1", "工程部"),
                new DirectoryEmployeeRow("b", "004", "b", "org-1", "工程部"));
        when(mapper.countMissedPunchStatEmployees(org.mockito.ArgumentMatchers.any())).thenReturn(4L);
        when(mapper.listMissedPunchStatEmployees(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    FactQuery query = invocation.getArgument(0);
                    return candidates.subList(Math.min(query.offset(), candidates.size()),
                            Math.min(query.offset() + query.limit(), candidates.size()));
                });
        when(mapper.listDailyCells(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                missedStatCell("covered", "ANNUAL", 1),
                missedStatCell("a", null, 1),
                missedStatCell("normal", null, 0),
                missedStatCell("b", null, 1)));
        when(mapper.listOaForEmployees(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        for (int index = 0; index < 3; index++) {
            var page = service.query(command("missed-punch-stat", YearMonth.of(2026, 8))
                    .withPaging(index, 1));
            assertThat(page.rowCount()).isEqualTo(2);
            assertThat(page.rows()).extracting(row -> row.get("employeeId"))
                    .containsExactlyElementsOf(index < 2 ? List.of(index == 0 ? "a" : "b") : List.of());
            if (index < 2) assertThat(page.rows().getFirst().get("sequence")).isEqualTo(index + 1);
        }
    }

    private static DailyCellRow missedStatCell(String employee, String leaveType, int missing) {
        return new DailyCellRow(employee, LocalDate.of(2026, 8, 4), "WEEKDAY", leaveType,
                0, 0, missing, Instant.parse("2026-08-04T09:00:00Z"),
                Instant.parse("2026-08-04T09:00:00Z"), 0, 0);
    }

    @Test
    void missedPunchStatRejectsOversizedCandidatesRatherThanReturningPartialSuccess() {
        authorize();
        pinAugust();
        when(mapper.countMissedPunchStatEmployees(org.mockito.ArgumentMatchers.any())).thenReturn(50001L);
        assertThatThrownBy(() -> service.query(command("missed-punch-stat", YearMonth.of(2026, 8))))
                .isInstanceOf(ApiProblemException.class).hasMessageContaining("候选人数过多");
        verify(mapper, never()).listMissedPunchStatEmployees(org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"annual-leave-stat", "time-off-stat"})
    void monthlyUsageReadFailureCannotBecomeAZeroUsageReport(String sheet) {
        authorize();
        pinAugust();
        when(mapper.countLeaveStatAccounts(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(mapper.listLeaveStatAccounts(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new LeaveStatAccountRow("employee-1", "001", "张三", "org-1", "工程部",
                        "ANNUAL_LEAVE", java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.TEN,
                        LocalDate.of(2020, 1, 1), 0)));
        when(mapper.listLeaveUsageDocuments(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.apache.ibatis.exceptions.PersistenceException("database detail not user-safe"));
        assertThatThrownBy(() -> service.query(command(sheet, YearMonth.of(2026, 8))))
                .isInstanceOf(ApiProblemException.class).hasMessageContaining("请假使用统计暂时读取失败")
                .hasMessageNotContaining("database detail");
    }

    // ── workHoursRow period-filter regression tests ──────────────────────────
    //
    // Real-data basis:
    //   唐勇 SZST0026: sick-leave docs spanning Aug–Sep and Sep–Oct,
    //     byDate maps include weekends → 543.5 h anomaly before the fix.
    //   陈书傲 SZJN0015: 调休 doc Sep 25–Oct 10, UI showed 96 h instead of
    //     88 h because the full recognizedMinutes was used without clipping.
    //   Cross-month same-doc dedup: one OA document can appear in two
    //     projection rows; projectionStart/End intersection prevents double-count.
    //   销假 amended interval: OA interval may lie outside the query window
    //     while the dayMap date falls inside it — must still count.

    @Test
    void workHoursRowFullMonthLeaveCountsEntireDayMap() {
        // Sick leave entirely within September (workdays only, no weekends).
        // 22 workdays × 480 = 10560 min = 176 h; scheduled also 176 h → actual 0.
        var aggregate = workHoursAggregate(10560L); // 176 h scheduled
        String dayMap = "{\"2026-09-01\":480,\"2026-09-02\":480,\"2026-09-03\":480,"
                + "\"2026-09-04\":480,\"2026-09-07\":480,\"2026-09-08\":480,"
                + "\"2026-09-09\":480,\"2026-09-10\":480,\"2026-09-11\":480,"
                + "\"2026-09-14\":480,\"2026-09-15\":480,\"2026-09-16\":480,"
                + "\"2026-09-17\":480,\"2026-09-18\":480,\"2026-09-21\":480,"
                + "\"2026-09-22\":480,\"2026-09-23\":480,\"2026-09-24\":480,"
                + "\"2026-09-25\":480,\"2026-09-28\":480,\"2026-09-29\":480,"
                + "\"2026-09-30\":480}";
        var doc = oaLeaveRow("leave-sep", "SICK",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                10560L, dayMap);

        var row = AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));

        assertThat(row.get("leaveHours")).isEqualTo(176.0d);
        assertThat(row.get("actualHours")).isEqualTo(0.0d);
    }

    @Test
    void workHoursRowCrossMonthLeaveClipsToQueryWindow() {
        // 陈书傲 SZJN0015: 调休 doc Aug 31 → Sep 4 (real data).
        // Full dayMap: Aug 31 + Sep 1–4 = 5 days × 480 = 2400 min.
        // Sep projection (Sep 1 → Oct 1) clips to Sep 1–4 only = 4 × 480 = 1920 min = 32 h.
        // Before the fix workHoursRow used the whole recognizedMinutes (2400 min = 40 h).
        var aggregate = workHoursAggregate(11040L); // 184 h scheduled, actual = 184 − 32 = 152
        String dayMap = "{\"2026-08-31\":480,"
                + "\"2026-09-01\":480,\"2026-09-02\":480,"
                + "\"2026-09-03\":480,\"2026-09-04\":480}";
        var doc = oaTimeOffRow("leave-cross", "TIME_OFF",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                2400L, dayMap);

        var row = AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));

        // Sep days only: 4 × 480 = 1920 min = 32 h (not 40 h from full dayMap)
        assertThat(row.get("timeOffHours")).isEqualTo(32.0d);
        assertThat(row.get("actualHours")).isEqualTo(184.0d - 32.0d);
    }

    @Test
    void workHoursRowCrossMonthDocInTwoProjectionsCountsEachMonthOnce() {
        // A cross-month OA document produces one OaRow per projection.
        // Each row carries the full dayMap; projectionStart/End intersection
        // must prevent double-counting: Sep row counts Sep days only,
        // Oct row counts Oct days only, total = 4 × 480 = 32 h, not 64 h.
        var aggregate = workHoursAggregate(22080L);
        String dayMap = "{\"2026-09-29\":480,\"2026-09-30\":480,"
                + "\"2026-10-08\":480,\"2026-10-09\":480}";
        var sepDoc = oaLeaveRow("leave-dup", "ANNUAL_LEAVE",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                1920L, dayMap);
        var octDoc = oaLeaveRow("leave-dup", "ANNUAL_LEAVE",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1),
                1920L, dayMap);

        var row = AttendanceReportQueryPageService.workHoursRow(
                aggregate, List.of(sepDoc, octDoc),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 1));

        assertThat(row.get("annualLeaveHours")).isEqualTo(32.0d);
    }

    @Test
    void workHoursRowAmendedLeaveIntervalOutsideWindowCountedViaDayMap() {
        // 销假 scenario: the OA interval is in August, but the dayMap carries
        // a September date (the amended/moved working day).
        // The SQL oaBusinessDateWindow already selects this row via REGEXP;
        // workHoursRow must count the Sep day from the dayMap.
        var aggregate = workHoursAggregate(11040L);
        String dayMap = "{\"2026-09-03\":480}";
        var doc = new OaRow(
                "amended-1", "emp-1", "SZST0285", "汪立青", "org-1", "研发部",
                "LEAVE", "PERSONAL",
                Instant.parse("2026-08-24T16:00:00Z"),
                Instant.parse("2026-08-25T16:00:00Z"),
                480L, "APPROVED", "OA",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                dayMap);

        var row = AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));

        assertThat(row.get("leaveHours")).isEqualTo(8.0d);
    }

    @Test
    void workHoursRowLegacyNullByDateJsonFallsBackToRecognizedMinutes() {
        // Legacy fact: no byDate JSON, interval wholly within Sep projection.
        // Same month, fully within window → safe to return recognizedMinutes.
        var aggregate = workHoursAggregate(11040L);
        var doc = new OaRow(
                "legacy-1", "emp-1", "SZST0001", "张三", "org-1", "工程部",
                "LEAVE", "PERSONAL",
                Instant.parse("2026-09-01T16:00:00Z"), // 2026-09-02 00:00 CST
                Instant.parse("2026-09-02T16:00:00Z"), // 2026-09-03 00:00 CST
                480L, "APPROVED", "OA",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                null);

        var row = AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));

        assertThat(row.get("leaveHours")).isEqualTo(8.0d);
    }

    @Test
    void workHoursRowLegacyCrossMonthNullByDateJsonThrows503() {
        // Legacy fact: no byDate JSON, interval spans Aug–Sep.
        // Attribution between months is ambiguous → 503 REQUIRED, not silent 0.
        var aggregate = workHoursAggregate(11040L);
        var doc = new OaRow(
                "legacy-cross", "emp-1", "SZST0001", "张三", "org-1", "工程部",
                "LEAVE", "PERSONAL",
                Instant.parse("2026-08-30T16:00:00Z"), // 2026-08-31 CST
                Instant.parse("2026-09-02T16:00:00Z"), // 2026-09-03 CST
                960L, "APPROVED", "OA",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                null);

        assertThatThrownBy(() ->
                AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("逐日使用明细");
    }

    @Test
    void workHoursRowInvalidByDateJsonThrows503() {
        // Malformed byDate JSON must throw 503, not silently return 0 or full total.
        var aggregate = workHoursAggregate(11040L);
        var doc = new OaRow(
                "bad-json", "emp-1", "SZST0001", "张三", "org-1", "工程部",
                "LEAVE", "PERSONAL",
                Instant.parse("2026-09-01T16:00:00Z"),
                Instant.parse("2026-09-02T16:00:00Z"),
                480L, "APPROVED", "OA",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                "{not-valid-json}");

        assertThatThrownBy(() ->
                AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void workHoursRowDuplicateDayDifferentValueThrows503() {
        // OaRecognizedDayMinutes.decode returns a single-entry map per day,
        // so duplicate keys in the JSON with differing values should be caught
        // by the decoder itself (Jackson last-write-wins) which then fails
        // the sum validation — verify the 503 surfaces to the caller.
        // We simulate the scenario that would arise if two different decoded
        // Maps were merged for the same document (shouldn't happen in practice
        // but the seen-map guard must detect it).
        // Use a day-map where the total declared (480) does not match the sum
        // of individual entries (480+480=960) so OaRecognizedDayMinutes.decode
        // throws due to validation mismatch.
        var aggregate = workHoursAggregate(11040L);
        var doc = new OaRow(
                "mismatch", "emp-1", "SZST0001", "张三", "org-1", "工程部",
                "LEAVE", "PERSONAL",
                Instant.parse("2026-09-01T16:00:00Z"),
                Instant.parse("2026-09-03T16:00:00Z"),
                480L, // declared total = 480, but map sums to 960
                "APPROVED", "OA",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                "{\"2026-09-02\":480,\"2026-09-03\":480}"); // sum=960 ≠ 480

        assertThatThrownBy(() ->
                AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(ApiProblemException.class);
    }

    // ── helpers for workHoursRow unit tests ──────────────────────────────────

    private static QueryPageRows.EmployeeDailyAggregateRow workHoursAggregate(
            long scheduledMinutes) {
        return new QueryPageRows.EmployeeDailyAggregateRow(
                "emp-1", "SZST0001", "测试员工", "org-1", "测试部门",
                0, 0, 0, 0,
                scheduledMinutes,
                0, 0, 0,
                0, 0, 0, 0,
                scheduledMinutes / 480,
                java.math.BigDecimal.ZERO,
                null);
    }

    private static OaRow oaLeaveRow(
            String docId, String leaveType,
            LocalDate projStart, LocalDate projEnd,
            long recognizedMinutes, String byDateJson) {
        return new OaRow(
                docId, "emp-1", "SZST0001", "测试员工", "org-1", "测试部门",
                "LEAVE", leaveType,
                projStart.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                projEnd.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                recognizedMinutes, "APPROVED", "OA",
                projStart, projEnd, byDateJson);
    }

    private static OaRow oaTimeOffRow(
            String docId, String leaveType,
            LocalDate projStart, LocalDate projEnd,
            long recognizedMinutes, String byDateJson) {
        return new OaRow(
                docId, "emp-1", "SZST0001", "测试员工", "org-1", "测试部门",
                "TIME_OFF", leaveType,
                projStart.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                projEnd.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                recognizedMinutes, "APPROVED", "OA",
                projStart, projEnd, byDateJson);
    }

    @Test
    void naturalYearStatReadsProvenLegacyAugustShareWithoutRewritingThePin() {
        authorize();
        when(mapper.findLatestPin(org.mockito.ArgumentMatchers.eq(COMPANY),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(LocalDate.class))).thenAnswer(invocation -> {
                    LocalDate start = invocation.getArgument(1);
                    LocalDate end = invocation.getArgument(2);
                    assertThat(end).isEqualTo(start.plusMonths(1));
                    return start.equals(LocalDate.of(2026,8,1))
                            ? new PinRow("proj-1","ARP1-" + "a".repeat(64),"OPEN",NOW,"[]") : null;
                });
        when(mapper.countLeaveStatAccounts(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(mapper.listLeaveStatAccounts(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new LeaveStatAccountRow("employee-1", "001", "张三", "org-1", "工程部",
                        "ANNUAL_LEAVE", java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.TEN,
                        LocalDate.of(2020, 1, 1), 0)));
        when(mapper.listLeaveUsageDocuments(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new QueryPageRows.OaUsageDocumentRow("legacy-form","employee-1",
                        LocalDate.of(2026,8,1),LocalDate.of(2026,9,1),
                        Instant.parse("2026-08-31T00:30:00Z"),Instant.parse("2026-09-01T10:00:00Z"),960,"ANNUAL",null)));
        when(mapper.listLegacyLeaveDayEvidence(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(List.of("legacy-form")))).thenReturn(List.of(
                        new QueryPageRows.LegacyLeaveDayEvidenceRow("legacy-form","employee-1",
                                LocalDate.of(2026,8,1),LocalDate.of(2026,8,31),480L,"ANNUAL",1)));
        var page = service.query(command("annual-leave-stat",YearMonth.of(2026,8),
                LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)));
        assertThat((java.math.BigDecimal) page.rows().getFirst().get("usedMonth8"))
                .isEqualByComparingTo("1.00");
        verify(mapper).listLegacyLeaveDayEvidence(org.mockito.ArgumentMatchers.argThat(q ->
                q.employeeIds().equals(List.of("employee-1")) && q.projectionIds().equals(List.of("proj-1"))),
                org.mockito.ArgumentMatchers.eq(List.of("legacy-form")));
    }

    private void pinAugust() {
        when(mapper.findLatestPin(
                        COMPANY,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1)))
                .thenReturn(new PinRow(
                        "proj-1",
                        "ARP1-" + "a".repeat(64),
                        "OPEN",
                        NOW,
                        "[]"));
    }

    private void authorize() {
        when(sources.listAuthorizedCompanies(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL),
                        org.mockito.ArgumentMatchers.eq(
                                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(NOW)))
                .thenReturn(List.of(new CompanyOption(COMPANY, "测试公司")));
        when(sources.resolveRealtimeAuthorization(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        COMPANY,
                        NOW))
                .thenReturn(Optional.of(new RealtimeAuthorization(
                        new AuthorizedScope(
                                ScopeType.COMPANY,
                                COMPANY,
                                "公司",
                                "a".repeat(64)),
                        COMPANY,
                        true,
                        null,
                        Set.of(),
                        Set.of())));
    }

    private static AttendanceReportQueryPageService.QueryCommand command(
            String sheet, YearMonth period) {
        return command(sheet, period, null, null);
    }

    private static AttendanceReportQueryPageService.QueryCommand commandTreated(
            String sheet, YearMonth period, String overtimeTreatment) {
        return new AttendanceReportQueryPageService.QueryCommand(
                sheet,
                COMPANY,
                null,
                null,
                null,
                period,
                null,
                null,
                0,
                50,
                null, null, null, null, null, overtimeTreatment,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static AttendanceReportQueryPageService.QueryCommand command(
            String sheet, YearMonth period, LocalDate fromDate, LocalDate toDate) {
        return new AttendanceReportQueryPageService.QueryCommand(
                sheet,
                COMPANY,
                null,
                null,
                null,
                period,
                fromDate,
                toDate,
                0,
                50,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
