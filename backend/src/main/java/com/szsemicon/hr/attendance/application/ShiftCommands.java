package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import java.time.LocalDate;
import java.util.List;

public final class ShiftCommands {

    private ShiftCommands() {
    }

    public record TemplateCommand(
            String legalEntityId,
            String locationId,
            String code,
            String name,
            String reason) {
    }

    public record VersionCommand(
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            List<Segment> segments,
            String reason) {
    }
}
