package com.szsemicon.hr.wave6;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.leavetimeaccount.domain.LedgerEntryProvenance;
import com.szsemicon.hr.leavetimeaccount.domain.LedgerEntryType;
import com.szsemicon.hr.leavetimeaccount.domain.TimeAccountLedgerEntry;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Wave6DomainBoundaryTest {

    @Test
    void wave6_domain_has_no_framework_adapter_or_w5_dependency() {
        var classes = new ClassFileImporter()
                .importPackages("com.szsemicon.hr.leavetimeaccount.domain");

        noClasses()
                .that().resideInAPackage("..leavetimeaccount.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "org.mybatis..",
                        "..application..",
                        "..infrastructure..",
                        "..interfaces..",
                        "..attendancecalculation..",
                        "..periodclose..")
                .check(classes);
    }

    @Test
    void ledger_contract_contains_provenance_but_no_person_or_sensitive_leave_payload() {
        Set<String> componentNames = Set.of(LedgerEntryProvenance.class.getRecordComponents())
                .stream()
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertThat(componentNames)
                .contains(
                        "sourceType",
                        "sourceId",
                        "businessDate",
                        "effectiveFrom",
                        "expiresOn",
                        "policyVersionId",
                        "periodVersionId",
                        "closeSnapshotId",
                        "requestId")
                .doesNotContain(
                        "employeeName",
                        "leaveReason",
                        "attachment",
                        "location",
                        "payroll");
    }

    @Test
    void boundary_fixture_uses_explicitly_synthetic_identifiers() {
        var entry = TimeAccountLedgerEntry.create(
                "entry-synthetic-boundary",
                "account-synthetic-boundary",
                "employment-synthetic-boundary",
                1,
                LedgerEntryType.OPENING,
                new BigDecimal("8.00"),
                new LedgerEntryProvenance(
                        "OPENING_IMPORT",
                        "opening-synthetic-boundary",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 7, 21),
                        null,
                        null,
                        null,
                        null,
                        "request-synthetic-boundary"),
                null);

        assertThat(entry.entryId()).contains("synthetic");
        assertThat(entry.accountId()).contains("synthetic");
        assertThat(entry.employmentPeriodId()).contains("synthetic");
        assertThat(entry.provenance().sourceId()).contains("synthetic");
        assertThat(entry.provenance().requestId()).contains("synthetic");
    }
}
