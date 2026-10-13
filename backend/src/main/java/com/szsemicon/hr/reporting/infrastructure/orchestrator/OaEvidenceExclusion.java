package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaEvidenceExclusionRow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * HR decision to exclude one exact immutable OA document version from report calculation.
 * Exclusion markers bind precise identity, approval state, and the cutoff-reversed state
 * so a historical cutoff and a later reversal have distinct immutable input markers.
 */
final class OaEvidenceExclusion {
    static final String MARKER_PREFIX = com.szsemicon.hr.reporting.application.
            AttendanceReportDecisionVersionProvider.EVIDENCE_EXCLUSION_PREFIX;

    private OaEvidenceExclusion() { }

    static List<String> markers(List<OaEvidenceExclusionRow> rows, Instant asOf) {
        return rows.stream().filter(row -> !row.knowledgeAt().isAfter(asOf))
                .map(row -> marker(row, asOf)).sorted().toList();
    }

    private static String marker(OaEvidenceExclusionRow row, Instant asOf) {
        Instant reversal = row.reversedAt() != null && !row.reversedAt().isAfter(asOf)
                ? row.reversedAt() : null;
        return MARKER_PREFIX + row.exclusionId() + ":" + digest(
                row.exclusionId(), row.companyId(), row.employeeId(), row.sourceId(),
                row.sourceBusinessKey(), row.sourceVersion(), row.documentId(), row.documentType(),
                Objects.toString(row.intervalStart(), ""), Objects.toString(row.intervalEnd(), ""),
                Objects.toString(row.pointInstant(), ""),
                row.knowledgeAt().toString(), Objects.toString(reversal, "ACTIVE"),
                row.confirmationSha256(), row.evidenceSha256(), row.requestId());
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
