package com.szsemicon.hr.punchimport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.evidenceingestion.application.AttendanceEvidenceRepository;
import com.szsemicon.hr.evidenceingestion.application.EvidenceRows.EffectiveEventRow;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper.EmployeeMatch;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper.FileRef;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper.LatestPrecheck;
import com.szsemicon.hr.punchimport.application.PunchImportCommandMapper.StagedRow;
import com.szsemicon.hr.punchimport.application.PunchImportReadModels.BatchView;
import com.szsemicon.hr.punchimport.port.MalwareScanPort;
import com.szsemicon.hr.punchimport.port.StoredObjectPort;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PunchImportCommandServiceTest {

    @Mock
    private CurrentCapabilityService capabilities;
    @Mock
    private CurrentPrincipalProvider principals;
    @Mock
    private PunchImportCommandMapper commands;
    @Mock
    private PunchImportReadService reads;
    @Mock
    private PunchWorkbookGateway workbooks;
    @Mock
    private StoredObjectPort storage;
    @Mock
    private MalwareScanPort scanner;
    @Mock
    private AttendanceEvidenceRepository evidence;

    private PunchImportCommandService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new PunchImportCommandService(
                capabilities,
                principals,
                commands,
                reads,
                workbooks,
                storage,
                scanner,
                evidence,
                json,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC));
        when(principals.currentPrincipalId()).thenReturn("actor-1");
    }

    @Test
    void publishCreatesEventOnlyForMissingTimes() throws Exception {
        BatchView batch = batch("AWAITING_CONFIRMATION");
        when(reads.find("batch-1")).thenReturn(batch);
        when(commands.countCompanyScope(any(), any(), any(), any())).thenReturn(1);
        when(commands.findLatestPrecheck("batch-1")).thenReturn(new LatestPrecheck(
                "pre-1",
                "att-1",
                sha("token-1"),
                Instant.parse("2026-08-19T00:00:00Z"),
                2,
                2,
                0));
        when(commands.incrementBatchVersion("batch-1", 2)).thenReturn(1L);
        when(commands.nextStateSequence("batch-1")).thenReturn(3, 4);
        Instant punch = Instant.parse("2026-01-16T00:19:00Z");
        String existingJson = json.writeValueAsString(Map.of(
                "employeeNumber", "SZST0001",
                "direction", "IN",
                "punchTime", "2026-01-16 08:19:00",
                "_employeeId", "emp-1",
                "_employmentPeriodId", "per-1",
                "_instant", punch.toString()));
        String freshJson = json.writeValueAsString(Map.of(
                "employeeNumber", "SZST0001",
                "direction", "OUT",
                "punchTime", "2026-01-16 18:05:00",
                "_employeeId", "emp-1",
                "_employmentPeriodId", "per-1",
                "_instant", Instant.parse("2026-01-16T10:05:00Z").toString()));
        when(commands.listStagedRows("batch-1")).thenReturn(List.of(
                new StagedRow("row-dup", 1, existingJson, "fp-1", 0),
                new StagedRow("row-new", 2, freshJson, "fp-2", 0)));
        when(evidence.findExactEvents(eq("co-1"), eq("emp-1"), eq(punch), eq("IN")))
                .thenReturn(List.of(new EffectiveEventRow(
                        "evt-existing",
                        "co-1",
                        "emp-1",
                        "PUNCH_POINT",
                        "IN",
                        punch,
                        null,
                        null,
                        "digest",
                        Instant.parse("2026-08-01T00:00:00Z"))));
        when(evidence.findExactEvents(
                eq("co-1"),
                eq("emp-1"),
                eq(Instant.parse("2026-01-16T10:05:00Z")),
                eq("OUT")))
                .thenReturn(List.of());

        BatchView published = service.publish(
                "batch-1",
                2,
                "token-1",
                "VALID_ROWS_ONLY",
                "CONFIRM_PUBLISH",
                "确认导入",
                "req-1");

        assertThat(published.batchId()).isEqualTo("batch-1");
        verify(evidence).insertEffectiveEvent(any());
        verify(evidence, never()).insertRecalculationIntent(any());
        verify(evidence, org.mockito.Mockito.atLeastOnce())
                .insertEvidenceLink(any());
    }

    @Test
    void precheckMarksMissingNumberAsBlocking() {
        BatchView batch = batch("DRAFT");
        when(reads.find("batch-1")).thenReturn(
                batch,
                new BatchView(
                        batch.batchId(),
                        batch.companyId(),
                        batch.sourceId(),
                        batch.originalFilename(),
                        batch.fileSha256(),
                        "AWAITING_CONFIRMATION",
                        1,
                        0,
                        1,
                        0,
                        0,
                        0,
                        LocalDate.of(2026, 1, 16),
                        LocalDate.of(2026, 1, 16),
                        true,
                        null,
                        batch.createdAt(),
                        2));
        when(commands.countCompanyScope(any(), any(), any(), any())).thenReturn(1);
        when(commands.incrementBatchVersion(any(), anyLong())).thenReturn(1L);
        when(commands.findFile("batch-1")).thenReturn(new FileRef(
                "file-1", "obj-1", "月报.xlsx", "a".repeat(64), "MONTHLY_SUMMARY", "b".repeat(64)));
        when(storage.open("obj-1")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(workbooks.parse(any(), any(), any())).thenReturn(
                new PunchWorkbookGateway.ParsedWorkbook(
                        "MONTHLY_SUMMARY",
                        "c".repeat(64),
                        "MONTHLY_SUMMARY",
                        List.of(Map.of(
                                "employeeNumber", "",
                                "punchTime", "2026-01-16 08:19:00",
                                "direction", "IN",
                                "sourceTimeZone", "Asia/Shanghai")),
                        List.of()));
        when(commands.nextAttemptNumber("batch-1")).thenReturn(1);
        when(commands.nextStateSequence("batch-1")).thenReturn(2, 3);

        BatchView result = service.precheck("batch-1", 1, "req-pre");
        assertThat(result.state()).isEqualTo("AWAITING_CONFIRMATION");
        verify(commands).insertIssue(
                anyString(),
                anyString(),
                anyString(),
                eq("UNMATCHED_EMPLOYEE_NUMBER"),
                eq("BLOCKING"),
                eq("employeeNumber"),
                anyString(),
                any());
        verify(commands, never()).matchEmployees(any(), any(), any());
    }

    private static BatchView batch(String state) {
        return new BatchView(
                "batch-1",
                "co-1",
                "src-1",
                "月报.xlsx",
                "d".repeat(64),
                state,
                2,
                2,
                0,
                1,
                0,
                1,
                LocalDate.of(2026, 1, 16),
                LocalDate.of(2026, 1, 16),
                true,
                "token-1",
                Instant.parse("2026-08-18T00:00:00Z"),
                2);
    }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
