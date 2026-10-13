package com.szsemicon.hr.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class OaApprovalStatusTest {
    @Test
    void onlyExactLegacyAdapterStateZeroIsPending() {
        assertThat(OaApprovalStatus.normalizeLegacy("UNKNOWN", "2026-09-09T08:30:00:state=0:Asia/Shanghai"))
                .isEqualTo("PENDING");
        assertThat(OaApprovalStatus.normalizeLegacy("UNKNOWN", "2026-09-09T08:30:00.123456789:state=0:Asia/Shanghai"))
                .isEqualTo("PENDING");
        for (String version : new String[] {null, "v1", "2026-09-09T08:30:00:state=0",
                "2026-09-09T08:30:00:state=1:Asia/Shanghai",
                "2026-09-09T08:30:00:state=01:Asia/Shanghai",
                "2026-09-09T08:30:00:state=99:Asia/Shanghai",
                "2026-09-09T08:30:00:state=0:UTC",
                "2026-99-99T08:30:00:state=0:Asia/Shanghai"}) {
            assertThat(OaApprovalStatus.normalizeLegacy("UNKNOWN", version)).isEqualTo("UNKNOWN");
        }
        assertThat(OaApprovalStatus.normalizeLegacy("REVOKED", "2026-09-09T08:30:00:state=0:Asia/Shanghai"))
                .isEqualTo("REVOKED");
    }

    @Test
    void pendingCoversAttendanceButNeverAuthorizesAccountPosting() {
        assertThat(OaApprovalStatus.effective("PENDING")).isTrue();
        assertThat(OaApprovalStatus.approved("PENDING")).isFalse();
        assertThat(OaApprovalStatus.effective("UNKNOWN")).isFalse();
        assertThat(OaApprovalStatus.effective(null)).isFalse();
        for (String status : new String[] {"APPROVED", "MODIFIED", "SUPPLEMENTED"}) {
            assertThat(OaApprovalStatus.effective(status)).isTrue();
            assertThat(OaApprovalStatus.approved(status)).isTrue();
        }
    }
}
