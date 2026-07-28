package com.szsemicon.hr.employee.interfaces.rest;

import java.util.List;

public record EmployeePageResponse(
        List<EmployeeSummaryResponse> items,
        long total,
        int page,
        int size) {
}

