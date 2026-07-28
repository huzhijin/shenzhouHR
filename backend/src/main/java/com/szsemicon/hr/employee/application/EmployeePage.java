package com.szsemicon.hr.employee.application;

import com.szsemicon.hr.employee.domain.EmployeeSummary;
import java.util.List;

public record EmployeePage(
        List<EmployeeSummary> items,
        long total,
        int page,
        int size) {

    public EmployeePage {
        items = List.copyOf(items);
    }
}

