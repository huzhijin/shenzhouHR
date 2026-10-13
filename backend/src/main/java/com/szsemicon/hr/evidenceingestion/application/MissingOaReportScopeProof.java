package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.evidenceingestion.domain.oa.OaEmployeeNumberCatalog;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A narrow negative proof, never an inference that a missing source form was cancelled. */
public final class MissingOaReportScopeProof {
    private MissingOaReportScopeProof() { }

    public static Set<String> provenUnrelatedKeys(String targetCompanyId, Collection<String> missingKeys,
            List<EvidenceRows.MissingOaKeyScopeRow> rows, List<String> targetEmployeeNumbers) {
        return provenUnrelatedKeysForTypes(targetCompanyId, missingKeys, rows, targetEmployeeNumbers,
                Set.of("OVERTIME", "PUNCH_CORRECTION"));
    }

    /** For an explicitly requested employee window only; an empty scope proves nothing. */
    public static Set<String> provenUnrelatedEmployeeKeys(String targetCompanyId,
            Collection<String> targetEmployeeIds, Collection<String> missingKeys,
            List<EvidenceRows.MissingOaKeyScopeRow> rows, List<String> targetEmployeeNumbers) {
        if (targetEmployeeIds == null || targetEmployeeIds.isEmpty()
                || targetEmployeeIds.stream().anyMatch(MissingOaReportScopeProof::blank)) {
            return Set.of();
        }
        // Leave amendments are resolved within the exact source + employee
        // identity, including the legacy no-serial interval fallback. A leave
        // with complete, unchanging non-target history can therefore be absent
        // without changing any selected employee's result. Company refreshes
        // retain the narrower family allowlist above.
        return provenUnrelatedKeysForScope(targetCompanyId, Set.copyOf(targetEmployeeIds), missingKeys,
                rows, targetEmployeeNumbers, Set.of("OVERTIME", "PUNCH_CORRECTION", "LEAVE"));
    }

    /**
     * A selected employee's missing key can be harmless without being cancelled:
     * its latest overtime context is not consumable, or its exact punch document
     * has an audited, knowledge-bounded report exclusion. Historical report rows
     * may then be replaced normally; raw observations and source statuses remain
     * untouched. Whole-company callers use the explicit company proof below.
     */
    public static Set<String> provenNoncontributingEmployeeKeys(String targetCompanyId,
            Collection<String> targetEmployeeIds, Collection<String> missingKeys,
            List<EvidenceRows.MissingOaKeyScopeRow> rows, List<String> targetEmployeeNumbers,
            List<EvidenceRows.MissingOaCurrentEligibilityRow> latestRows, Instant dataAsOf) {
        if (blank(targetCompanyId) || targetEmployeeIds == null || targetEmployeeIds.isEmpty()
                || targetEmployeeIds.stream().anyMatch(MissingOaReportScopeProof::blank)
                || missingKeys == null || missingKeys.isEmpty() || rows == null || rows.isEmpty()
                || latestRows == null || latestRows.isEmpty() || dataAsOf == null
                || targetEmployeeNumbers == null || targetEmployeeNumbers.isEmpty()
                || targetEmployeeNumbers.stream().anyMatch(MissingOaReportScopeProof::blank)) return Set.of();
        Set<String> targetNumbers = new HashSet<>();
        targetEmployeeNumbers.forEach(number -> addNumbers(targetNumbers, number));
        Set<String> proven = new LinkedHashSet<>();
        for (String key : missingKeys) {
            int separator = key == null ? -1 : key.indexOf(':');
            String type = separator > 0 && separator < key.length() - 1 ? key.substring(0, separator) : null;
            if (!"OVERTIME".equals(type) && !"PUNCH_CORRECTION".equals(type)) continue;
            var history = rows.stream().filter(row -> row != null && key.equals(row.sourceBusinessKey())).toList();
            var current = latestRows.stream().filter(row -> row != null && key.equals(row.sourceBusinessKey())).toList();
            if (history.isEmpty() || current.size() != 1) continue;
            var latest = current.getFirst();
            if (blank(latest.oaAttendanceDocumentId()) || !type.equals(latest.documentType())
                    || latest.createdAt() == null || latest.createdAt().isAfter(dataAsOf)
                    || !Boolean.FALSE.equals(latest.hasFutureDocument())
                    || !Boolean.FALSE.equals(latest.hasIndependentEvidenceLink())
                    || !Boolean.FALSE.equals(latest.hasConflictingReportIdentity())) continue;
            boolean cannotContribute = "OVERTIME".equals(type)
                    // Evaluate with the reader's SQL semantics, including its collation.
                    ? Boolean.FALSE.equals(latest.hasConsumableOvertimeContext())
                      || Boolean.TRUE.equals(latest.hasApplicableReportExclusion())
                    : Boolean.TRUE.equals(latest.hasApplicableReportExclusion());
            if (!cannotContribute) continue;
            Set<String> employees = new HashSet<>();
            boolean safe = true;
            boolean latestInHistory = false;
            for (var row : history) {
                if (blank(row.rawAttendanceFactId()) || !"OA_DOCUMENT".equals(row.factKind())
                        || blank(row.normalizedAttendanceRecordId()) || !"VALID".equals(row.validationStatus())
                        || !"OA_INTERVAL".equals(row.recordKind()) || blank(row.oaAttendanceDocumentId())
                        || !type.equals(row.documentType()) || !Boolean.TRUE.equals(row.documentIdentityConsistent())
                        || !"MATCHED".equals(row.matchStatus()) || blank(row.employeeId())
                        || !targetEmployeeIds.contains(row.employeeId())
                        || !targetCompanyId.equals(row.employeeCompanyId())
                        || blank(row.employeeNumber()) || blank(row.historicalEmployeeNumber())) {
                    safe = false;
                    break;
                }
                Set<String> sourceNumbers = new HashSet<>();
                addNumbers(sourceNumbers, row.employeeNumber());
                addNumbers(sourceNumbers, row.historicalEmployeeNumber());
                if (!targetNumbers.containsAll(sourceNumbers)) { safe = false; break; }
                employees.add(row.employeeId());
                latestInHistory |= latest.oaAttendanceDocumentId().equals(row.oaAttendanceDocumentId());
            }
            if (safe && latestInHistory && employees.size() == 1) proven.add(key);
        }
        return Set.copyOf(proven);
    }

    /**
     * Company reports cannot use "outside the selected employees" as proof.
     * Instead, each missing OT/PC key must have one complete, stable identity in
     * this company and independently fail the actual current reader gate. The
     * employee helper below checks that identity positively, not as a complement.
     * LEAVE and unknown status decisions are intentionally not handled here.
     */
    public static Set<String> provenNoncontributingCompanyKeys(String targetCompanyId,
            Collection<String> missingKeys, List<EvidenceRows.MissingOaKeyScopeRow> rows,
            List<String> targetEmployeeNumbers,
            List<EvidenceRows.MissingOaCompanyCurrentEligibilityRow> latestRows, Instant dataAsOf) {
        if (blank(targetCompanyId) || missingKeys == null || missingKeys.isEmpty()
                || rows == null || rows.isEmpty() || latestRows == null || latestRows.isEmpty()) return Set.of();
        Set<String> proven = new LinkedHashSet<>();
        for (String key : missingKeys) {
            if (key == null || !(key.startsWith("OVERTIME:") || key.startsWith("PUNCH_CORRECTION:"))) continue;
            var history = rows.stream().filter(row -> row != null && key.equals(row.sourceBusinessKey())).toList();
            var current = latestRows.stream().filter(row -> row != null && key.equals(row.sourceBusinessKey())).toList();
            if (history.isEmpty() || current.size() != 1 || current.getFirst().hasConsumableSourceStatus() == null) continue;
            Set<String> owners = new HashSet<>();
            for (var row : history) {
                if (!blank(row.employeeId())) owners.add(row.employeeId());
            }
            if (owners.size() != 1) continue;
            proven.addAll(provenNoncontributingEmployeeKeys(targetCompanyId, owners, List.of(key), history,
                    targetEmployeeNumbers, List.of(current.getFirst().readerEligibility()), dataAsOf));
        }
        return Set.copyOf(proven);
    }

    /**
     * A never-effective draft is a separate proof, not a zero-hour exemption.
     * Every observation must be a fully resolved LEAVE, every historical status
     * explicitly DRAFT, and no company/pin may ever hold even a zero-minute fact.
     * Reader status gates also exclude drafts as amendment originals; any separate
     * effective amendment remains subject to the converter's normal strict checks.
     */
    public static Set<String> provenNeverEffectiveDraftLeaveKeys(String reportCompanyId,
            Collection<String> missingKeys, List<EvidenceRows.MissingOaKeyScopeRow> rows,
            List<EvidenceRows.MissingOaCompanyCurrentEligibilityRow> latestRows, Instant dataAsOf) {
        if (blank(reportCompanyId) || missingKeys == null || rows == null || latestRows == null
                || dataAsOf == null) return Set.of();
        Set<String> proven = new LinkedHashSet<>();
        for (String key : missingKeys) {
            if (key == null || !key.startsWith("LEAVE:") || blank(key.substring("LEAVE:".length()))) continue;
            var history = rows.stream().filter(row -> row != null && key.equals(row.sourceBusinessKey())).toList();
            var current = latestRows.stream().filter(row -> row != null && key.equals(row.sourceBusinessKey())).toList();
            if (history.isEmpty() || current.size() != 1) continue;
            var latest = current.getFirst();
            if (!"LEAVE".equals(latest.documentType()) || blank(latest.oaAttendanceDocumentId())
                    || latest.createdAt() == null || latest.createdAt().isAfter(dataAsOf)
                    || !Boolean.FALSE.equals(latest.hasFutureDocument())
                    || !Boolean.FALSE.equals(latest.hasConsumableSourceStatus())
                    || !Boolean.TRUE.equals(latest.hasOnlyExplicitDraftLeaveHistory())
                    || !Boolean.FALSE.equals(latest.hasAnyReportFact())
                    || !Boolean.FALSE.equals(latest.hasIndependentEvidenceLink())
                    || !Boolean.FALSE.equals(latest.hasConflictingReportIdentity())) continue;
            Set<String> employees = new HashSet<>();
            Set<String> companies = new HashSet<>();
            boolean safe = true;
            boolean latestInHistory = false;
            for (var row : history) {
                if (blank(row.rawAttendanceFactId()) || !"OA_DOCUMENT".equals(row.factKind())
                        || blank(row.normalizedAttendanceRecordId()) || !"VALID".equals(row.validationStatus())
                        || !"OA_INTERVAL".equals(row.recordKind()) || blank(row.oaAttendanceDocumentId())
                        || !"LEAVE".equals(row.documentType()) || !Boolean.TRUE.equals(row.documentIdentityConsistent())
                        || !"MATCHED".equals(row.matchStatus()) || blank(row.employeeId())
                        || blank(row.employeeCompanyId()) || blank(row.employeeNumber())
                        || blank(row.historicalEmployeeNumber()) || !Boolean.FALSE.equals(row.hasTargetReportFact())) {
                    safe = false;
                    break;
                }
                employees.add(row.employeeId());
                companies.add(row.employeeCompanyId());
                latestInHistory |= latest.oaAttendanceDocumentId().equals(row.oaAttendanceDocumentId());
            }
            if (safe && latestInHistory && employees.size() == 1 && companies.size() == 1) proven.add(key);
        }
        return Set.copyOf(proven);
    }

    static Set<String> provenUnrelatedKeysForTypes(String targetCompanyId, Collection<String> missingKeys,
            List<EvidenceRows.MissingOaKeyScopeRow> rows, List<String> targetEmployeeNumbers, Set<String> types) {
        return provenUnrelatedKeysForScope(targetCompanyId, null, missingKeys, rows, targetEmployeeNumbers, types);
    }

    private static Set<String> provenUnrelatedKeysForScope(String targetCompanyId, Set<String> targetEmployeeIds,
            Collection<String> missingKeys, List<EvidenceRows.MissingOaKeyScopeRow> rows,
            List<String> targetEmployeeNumbers, Set<String> types) {
        if (blank(targetCompanyId) || missingKeys == null || missingKeys.isEmpty()
                || rows == null || rows.isEmpty() || targetEmployeeNumbers == null
                || targetEmployeeNumbers.isEmpty() || targetEmployeeNumbers.stream().anyMatch(MissingOaReportScopeProof::blank)) {
            return Set.of();
        }
        Set<String> targetNumbers = new HashSet<>();
        targetEmployeeNumbers.forEach(number -> addNumbers(targetNumbers, number));
        Set<String> proven = new LinkedHashSet<>();
        for (String key : missingKeys) {
            int separator = key == null ? -1 : key.indexOf(':');
            String type = separator > 0 && separator < key.length() - 1 ? key.substring(0, separator) : null;
            if (type == null || !types.contains(type)) continue;
            var history = rows.stream().filter(row -> row != null && key.equals(row.sourceBusinessKey())).toList();
            if (history.isEmpty()) continue;
            Set<String> employees = new HashSet<>();
            Set<String> companies = new HashSet<>();
            boolean safe = true;
            for (var row : history) {
                if (blank(row.rawAttendanceFactId()) || !"OA_DOCUMENT".equals(row.factKind())
                        || blank(row.normalizedAttendanceRecordId()) || !"VALID".equals(row.validationStatus())
                        || !"OA_INTERVAL".equals(row.recordKind()) || blank(row.oaAttendanceDocumentId())
                        || !type.equals(row.documentType()) || !Boolean.TRUE.equals(row.documentIdentityConsistent())
                        || !"MATCHED".equals(row.matchStatus()) || blank(row.employeeId())
                        || blank(row.employeeCompanyId())
                        || (targetEmployeeIds == null ? targetCompanyId.equals(row.employeeCompanyId())
                                : targetEmployeeIds.contains(row.employeeId()))
                        || blank(row.employeeNumber()) || blank(row.historicalEmployeeNumber())
                        || !Boolean.FALSE.equals(row.hasTargetReportFact())) {
                    safe = false;
                    break;
                }
                employees.add(row.employeeId());
                companies.add(row.employeeCompanyId());
                Set<String> sourceNumbers = new HashSet<>();
                addNumbers(sourceNumbers, row.employeeNumber());
                addNumbers(sourceNumbers, row.historicalEmployeeNumber());
                if (sourceNumbers.stream().anyMatch(targetNumbers::contains)) {
                    safe = false;
                    break;
                }
            }
            // Even an all-outside key that changed its employee is not proven.
            if (safe && employees.size() == 1 && companies.size() == 1) proven.add(key);
        }
        return Set.copyOf(proven);
    }

    private static void addNumbers(Set<String> numbers, String number) {
        numbers.add(number);
        numbers.add(number.strip());
        numbers.add(OaEmployeeNumberCatalog.alias(number));
        numbers.add(OaEmployeeNumberCatalog.alias(number.strip()));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
