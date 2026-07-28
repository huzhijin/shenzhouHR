package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.RecalculationBatchRequest;
import java.util.ArrayList;
import java.util.List;

public final class RecalculationRequestDigests {

    private RecalculationRequestDigests() {
    }

    public static String canonicalRequestDigest(
            RecalculationBatchRequest request) {
        List<String> fields = new ArrayList<>(List.of(
                request.legalEntityId(),
                request.periodId(),
                Long.toString(request.periodVersion()),
                request.periodToken(),
                request.reason(),
                request.actorId(),
                request.requestId(),
                request.correlationId(),
                request.idempotencyKey()));
        request.targets().stream()
                .map(AttendanceRecalculationModels.RecalculationTarget::stableKey)
                .forEach(fields::add);
        fields.addAll(request.triggerReferences());
        return CanonicalAttendanceDigests.digestStrings(
                "W5_RECALCULATION_REQUEST_V1", fields);
    }
}
