package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import java.time.LocalDate;

public final class AttendancePolicyCommands {

    private AttendancePolicyCommands() {
    }

    public record BindingCommand(
            PolicyKind policyKind,
            String policyVersionId,
            String groupId,
            String groupRevisionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }
}
