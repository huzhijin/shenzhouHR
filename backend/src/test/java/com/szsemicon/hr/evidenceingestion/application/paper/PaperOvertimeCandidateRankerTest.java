package com.szsemicon.hr.evidenceingestion.application.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeRows.EmployeeCandidateRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaperOvertimeCandidateRankerTest {

    @Test
    void twoHomonymsAreBothListed() {
        List<EmployeeCandidateRow> ranked = PaperOvertimeCandidateRanker.rank(
                List.of(
                        candidate("emp-finance", "10087", "陈士庆", "财务部"),
                        candidate("emp-equipment", "10012", "陈士庆", "设备工程部")),
                "陈士庆",
                "设备部");

        assertThat(ranked)
                .extracting(EmployeeCandidateRow::employeeNumber)
                .containsExactly("10012", "10087");
        assertThat(ranked)
                .extracting(EmployeeCandidateRow::departmentName)
                .containsExactly("设备工程部", "财务部");
    }

    @Test
    void fuzzyDepartmentRanksEquipmentEngineeringFirstButKeepsTheOtherPerson() {
        List<EmployeeCandidateRow> ranked = PaperOvertimeCandidateRanker.rank(
                List.of(
                        candidate("emp-finance", "10087", "陈士庆", "财务部"),
                        candidate("emp-equipment", "10012", "陈士庆", "设备工程部")),
                "陈士庆",
                "设备部");

        assertThat(ranked.getFirst().departmentName()).isEqualTo("设备工程部");
        assertThat(ranked.get(1).employeeId()).isEqualTo("emp-finance");
    }

    private static EmployeeCandidateRow candidate(
            String id, String number, String name, String department) {
        return new EmployeeCandidateRow(
                id, number, name, "org-" + id, department, "ACTIVE");
    }
}
