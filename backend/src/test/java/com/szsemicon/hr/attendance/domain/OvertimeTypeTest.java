package com.szsemicon.hr.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OvertimeTypeTest {

    @Test
    void mapsVerifiedOaEnumIds() {
        assertThat(OvertimeType.fromOaEnumId(-6539634143789166714L))
                .isEqualTo(OvertimeType.PAID);
        assertThat(OvertimeType.fromOaEnumId(5912806790045781226L))
                .isEqualTo(OvertimeType.COMPENSATORY);
        assertThat(OvertimeType.fromOaEnumId(4337518111002608138L))
                .isEqualTo(OvertimeType.VOLUNTARY);
    }

    @Test
    void leavesNullAndUnknownOaEnumIdsUnclassified() {
        assertThat(OvertimeType.fromOaEnumId(null)).isNull();
        assertThat(OvertimeType.fromOaEnumId(Long.MAX_VALUE)).isNull();
    }

    @Test
    void roundTripsEachKnownType() {
        for (OvertimeType type : OvertimeType.values()) {
            assertThat(OvertimeType.fromOaEnumId(type.toOaEnumId()))
                    .isEqualTo(type);
        }
    }
}
