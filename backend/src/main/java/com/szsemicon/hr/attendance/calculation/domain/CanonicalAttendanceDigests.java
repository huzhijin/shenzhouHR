package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

public final class CanonicalAttendanceDigests {

    private CanonicalAttendanceDigests() {
    }

    public static String inputDigest(CalculationInputSnapshot snapshot) {
        CanonicalWriter writer = new CanonicalWriter("W5_CALC_INPUT_V2")
                .text(snapshot.companyId())
                .text(snapshot.employeeId())
                .text(snapshot.employmentPeriodId())
                .text(snapshot.businessDate().toString())
                .text(snapshot.businessZone().getId())
                .instant(snapshot.knowledgeCutoff())
                .text(snapshot.configurationSnapshotReference())
                .text(snapshot.configurationDigest())
                .text(snapshot.evidenceSnapshotReference())
                .text(snapshot.evidenceDigest())
                .text(snapshot.adjustmentDigest())
                .text(snapshot.periodId())
                .number(snapshot.periodVersion())
                .text(snapshot.periodToken())
                .text(snapshot.algorithmVersion())
                .text(snapshot.requestId())
                .text(snapshot.correlationId());
        writer.number(snapshot.segments().size());
        snapshot.segments().forEach(segment -> writer
                .text(segment.segmentId())
                .text(segment.businessDate().toString())
                .instant(segment.interval().start())
                .instant(segment.interval().end())
                .instant(segment.arrivalWindow().start())
                .instant(segment.arrivalWindow().end())
                .instant(segment.departureWindow().start())
                .instant(segment.departureWindow().end())
                .text(segment.kind().name()));
        writer.number(snapshot.punchEvents().size());
        snapshot.punchEvents().forEach(punch -> writer
                .text(punch.eventId())
                .instant(punch.instant())
                .text(punch.direction().name())
                .text(punch.evidenceReference()));
        writer.number(snapshot.intervalEvidence().size());
        snapshot.intervalEvidence().forEach(evidence -> writer
                .text(evidence.evidenceId())
                .text(evidence.kind().name())
                .instant(evidence.interval().start())
                .instant(evidence.interval().end())
                .text(evidence.sourceReference())
                .nullableInstant(evidence.firstSubmittedAt())
                .bool(evidence.effective()));
        writer.number(snapshot.adjustments().size());
        snapshot.adjustments().forEach(adjustment -> writer
                .text(adjustment.adjustmentId())
                .instant(adjustment.interval().start())
                .instant(adjustment.interval().end())
                .text(adjustment.conclusion().name())
                .text(adjustment.reason())
                .text(adjustment.actorId())
                .text(adjustment.authorizationDecisionReference())
                .text(adjustment.approvalReference())
                .text(adjustment.requestId())
                .text(adjustment.correlationId())
                .text(adjustment.periodToken())
                .number(adjustment.version())
                .nullableText(adjustment.reversesAdjustmentId()));
        writer.text(snapshot.graceConsumption().employeeId())
                .text(snapshot.graceConsumption().month().toString())
                .number(snapshot.graceConsumption().used())
                .text(snapshot.graceConsumption().snapshotDigest())
                .number(snapshot.policy().lateGraceMaxMinutes())
                .number(snapshot.policy().monthlyLateGraceUses())
                .instant(snapshot.policy().correctionDeadline())
                .bool(snapshot.policy().timelyPendingSubmission())
                .nullableInstant(snapshot.policy().overtimeFirstSubmittedAt())
                .number(snapshot.policy().overtimeSubmissionDeadlineMinutes())
                .number(snapshot.policy().mealDeductions().size());
        snapshot.policy().mealDeductions().forEach(rule -> writer
                .text(rule.ruleId())
                .instant(rule.window().start())
                .instant(rule.window().end())
                .number(rule.deductionMinutes())
                .number(rule.triggerMinutes())
                .bool(rule.requireFullCoverage()));
        return writer.digest();
    }

    public static String resultDigest(DailyAttendanceResult result) {
        CanonicalWriter writer = new CanonicalWriter("W5_CALC_RESULT_V1")
                .text(result.inputDigest())
                .text(result.algorithmVersion())
                .number(result.metrics().scheduledMinutes())
                .number(result.metrics().confirmedScheduledWorkMinutes())
                .number(result.metrics().extendedPresenceMinutes())
                .number(result.metrics().recognizedOvertimeMinutes())
                .number(result.metrics().leaveOrTimeOffMinutes())
                .number(result.metrics().absenceMinutes())
                .number(result.metrics().actualWorkMinutes())
                .number(result.items().size());
        result.items().forEach(item -> writer
                .text(item.semanticKey())
                .text(item.segmentId())
                .instant(item.interval().start())
                .instant(item.interval().end())
                .text(item.category().name())
                .number(item.minutes())
                .text(item.reasonCode())
                .texts(item.evidenceIds())
                .nullableText(item.exceptionFingerprint()));
        writer.number(result.ruleHits().size());
        result.ruleHits().forEach(hit -> writer
                .text(hit.ruleHitId())
                .text(hit.ruleReference())
                .text(hit.segmentId())
                .text(hit.ruleCode())
                .number(hit.rawMinutes())
                .number(hit.includedMinutes())
                .texts(hit.evidenceIds()));
        writer.number(result.evidenceDecisions().size());
        result.evidenceDecisions().forEach(decision -> writer
                .text(decision.decisionId())
                .instant(decision.interval().start())
                .instant(decision.interval().end())
                .text(decision.evidenceId())
                .text(decision.kind().name())
                .text(decision.status().name())
                .text(decision.reasonCode()));
        writer.number(result.explanation().nodes().size());
        result.explanation().nodes().forEach(node -> {
            boolean resultRoot =
                    node.type()
                            == AttendanceCalculationModels
                                    .ExplanationNodeType.DAILY_RESULT;
            writer.text(resultRoot ? "<DAILY_RESULT_ROOT>" : node.nodeId())
                    .text(node.type().name())
                    .text(resultRoot
                            ? "<CALCULATION_VERSION>"
                            : node.referenceId())
                    .map(node.attributes());
        });
        writer.number(result.explanation().edges().size());
        result.explanation().edges().forEach(edge -> writer
                .text(normalizeResultRoot(edge.fromNodeId()))
                .text(normalizeResultRoot(edge.toNodeId()))
                .text(edge.relationship()));
        writer.texts(result.consumedPunchEventIds().stream().sorted().toList())
                .texts(result.exceptionFingerprints());
        return writer.digest();
    }

    static String digestStrings(String namespace, Collection<String> values) {
        return new CanonicalWriter(namespace).texts(
                values.stream().sorted().toList()).digest();
    }

    private static String normalizeResultRoot(String nodeId) {
        return nodeId.startsWith("result:")
                ? "<DAILY_RESULT_ROOT>"
                : nodeId;
    }

    private static final class CanonicalWriter {

        private final MessageDigest digest;

        private CanonicalWriter(String namespace) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(
                        "SHA-256 is required by the Java runtime", exception);
            }
            text(namespace);
        }

        private CanonicalWriter text(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bytes.length)
                    .array());
            digest.update(bytes);
            return this;
        }

        private CanonicalWriter nullableText(String value) {
            return value == null ? text("<NULL>") : text(value);
        }

        private CanonicalWriter instant(Instant value) {
            return text(value.toString());
        }

        private CanonicalWriter nullableInstant(Instant value) {
            return value == null ? text("<NULL>") : instant(value);
        }

        private CanonicalWriter number(long value) {
            return text(Long.toString(value));
        }

        private CanonicalWriter bool(boolean value) {
            return text(Boolean.toString(value));
        }

        private CanonicalWriter texts(Collection<String> values) {
            number(values.size());
            values.forEach(this::text);
            return this;
        }

        private CanonicalWriter map(Map<String, String> values) {
            number(values.size());
            values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(
                            Comparator.naturalOrder()))
                    .forEach(entry ->
                            text(entry.getKey()).text(entry.getValue()));
            return this;
        }

        private String digest() {
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }
}
