package com.szsemicon.hr.attendance.domain;

import java.util.Set;
import java.util.regex.Pattern;

/** Separates a known pending OA workflow from an unrecognised source state. */
public final class OaApprovalStatus {

    private static final Set<String> APPROVED = Set.of(
            "APPROVED", "MODIFIED", "SUPPLEMENTED");
    private static final Pattern LEGACY_PENDING_VERSION = Pattern.compile(
            "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])"
                    + "T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "([.][0-9]{1,9})?:state=0:Asia/Shanghai$");

    private OaApprovalStatus() {
    }

    /** Only the exact version emitted by the OA wall-clock adapter proves state 0. */
    public static String normalizeLegacy(String status, String sourceVersion) {
        return "UNKNOWN".equals(status)
                        && sourceVersion != null
                        && LEGACY_PENDING_VERSION.matcher(sourceVersion).matches()
                ? "PENDING"
                : status;
    }

    public static boolean approved(String status) {
        return status != null && APPROVED.contains(status);
    }

    /** Attendance coverage includes known pending workflows; account posting does not. */
    public static boolean effective(String status) {
        return approved(status) || "PENDING".equals(status);
    }
}
