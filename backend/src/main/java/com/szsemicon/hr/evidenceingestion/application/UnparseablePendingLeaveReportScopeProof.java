package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.attendance.domain.OaApprovalStatus;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.DocumentType;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaDocumentRecord;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.SourceStatus;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** An invalid current draft cannot be activated; this does not identify its current owner. */
public final class UnparseablePendingLeaveReportScopeProof {
    private UnparseablePendingLeaveReportScopeProof() { }

    public static boolean isCandidate(OaDocumentRecord record) {
        return record != null && record.documentType() == DocumentType.LEAVE
                && record.sourceStatus() == SourceStatus.PENDING
                && record.sourceBusinessKey() != null && record.sourceBusinessKey().startsWith("LEAVE:")
                && record.sourceBusinessKey().length() > 6 && !blank(record.sourceVersion())
                && record.start() != null && record.end() == null && record.leaveType() == null
                && blank(record.externalPersonRef()) && blank(record.employeeNumber())
                && !blank(record.leaveSerial()) && record.leaveSerial().equals(record.leaveSerial().strip());
    }

    public static boolean cannotActivateAndNoTargetHistory(String targetCompanyId, OaDocumentRecord record,
            List<EvidenceRows.UnparseableLeaveScopeRow> rows, List<String> targetNumbers,
            Collection<OaDocumentRecord> currentRecords) {
        return cannotActivateAndNoTargetHistory(targetCompanyId, List.of(), record, rows,
                targetNumbers, currentRecords);
    }

    public static boolean cannotActivateAndNoTargetHistory(String targetCompanyId,
            List<String> targetEmployeeIds, OaDocumentRecord record,
            List<EvidenceRows.UnparseableLeaveScopeRow> rows, List<String> targetNumbers,
            Collection<OaDocumentRecord> currentRecords) {
        if (!isCandidate(record) || rows == null || currentRecords == null || targetEmployeeIds == null) return false;
        var history = rows.stream().filter(row -> row != null
                && record.sourceBusinessKey().equals(row.sourceBusinessKey())).toList();
        if (history.isEmpty() || history.stream().anyMatch(row ->
                blank(row.sourceVersion()) || !"PENDING".equals(OaApprovalStatus.normalizeLegacy(row.sourceStatus(), row.sourceVersion()))
                || !record.leaveSerial().equals(row.leaveSerial())
                || !Boolean.FALSE.equals(row.hasRelatedRevocation()))) return false;
        // Blank serials can be matched by the old interval fallback. They cannot
        // establish absence, even when currently outside this reporting window.
        if (currentRecords.stream().anyMatch(current -> current == null
                || (current.documentType() == DocumentType.LEAVE_REVOCATION
                    && (blank(current.originalLeaveSerial())
                        || record.leaveSerial().equals(current.originalLeaveSerial().strip()))))) return false;
        if (!targetEmployeeIds.isEmpty()) {
            return MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(targetCompanyId,
                    targetEmployeeIds, List.of(record.sourceBusinessKey()), history.stream()
                            .map(EvidenceRows.UnparseableLeaveScopeRow::identityHistory).toList(),
                    targetNumbers).contains(record.sourceBusinessKey());
        }
        return MissingOaReportScopeProof.provenUnrelatedKeysForTypes(targetCompanyId,
                List.of(record.sourceBusinessKey()), history.stream()
                        .map(EvidenceRows.UnparseableLeaveScopeRow::identityHistory).toList(),
                targetNumbers, Set.of("LEAVE")).contains(record.sourceBusinessKey());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
