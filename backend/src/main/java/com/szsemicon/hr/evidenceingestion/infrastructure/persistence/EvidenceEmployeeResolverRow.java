package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

record EvidenceEmployeeResolverRow(
        String employeeId,
        String employmentPeriodId,
        String assignmentVersionId,
        String employeeVersionId,
        String organizationId,
        String bindingId,
        long employeeAggregateVersion,
        long employeeVersion,
        long employmentVersion) {
}
