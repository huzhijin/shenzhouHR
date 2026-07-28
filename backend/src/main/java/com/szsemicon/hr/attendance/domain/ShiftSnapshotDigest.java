package com.szsemicon.hr.attendance.domain;

import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The single canonical digest algorithm for immutable shift-version content.
 *
 * <p>Lifecycle state and audit metadata are intentionally excluded. Every
 * included value is NFC-normalized and UTF-8-length-prefixed, so delimiters in
 * snapshot values cannot create an ambiguous representation.</p>
 */
public final class ShiftSnapshotDigest {

    private static final String FORMAT = "shift-snapshot-v1";

    private ShiftSnapshotDigest() {
    }

    public static String digest(ShiftVersion version) {
        Objects.requireNonNull(version, "version");

        StringBuilder canonical = new StringBuilder();
        append(canonical, FORMAT);
        append(canonical, require(version.shiftId(), "shiftId"));
        append(canonical, require(version.shiftVersionId(), "shiftVersionId"));
        append(canonical, Integer.toString(version.versionNumber()));
        append(canonical, require(version.effectiveFrom(), "effectiveFrom").toString());
        append(canonical, version.effectiveTo() == null
                ? null : version.effectiveTo().toString());
        append(canonical, require(version.timeZone(), "timeZone"));
        append(canonical, Integer.toString(version.segments().size()));
        for (Segment segment : version.segments()) {
            Objects.requireNonNull(segment, "segment");
            append(canonical, require(segment.segmentType(), "segmentType").name());
            append(canonical, require(
                    segment.startLocalTime(), "startLocalTime").toString());
            append(canonical, Integer.toString(segment.startDayOffset()));
            append(canonical, require(
                    segment.endLocalTime(), "endLocalTime").toString());
            append(canonical, Integer.toString(segment.endDayOffset()));
        }
        return sha256(canonical.toString());
    }

    private static <T> T require(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static void append(StringBuilder target, String rawValue) {
        if (rawValue == null) {
            target.append("N:0:");
            return;
        }
        String value = Normalizer.normalize(rawValue, Normalizer.Form.NFC);
        target.append("S:")
                .append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "SHA-256 must be available", exception);
        }
    }
}
