package com.szsemicon.hr.evidenceingestion.domain.oa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class OaEmployeeNumberCatalogTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void aliasesSzt0687ToSzst0687() {
        assertThat(OaEmployeeNumberCatalog.alias("SZT0687"))
                .isEqualTo("SZST0687");
        assertThat(OaEmployeeNumberCatalog.alias("SZT0709"))
                .isEqualTo("SZST0709");
        assertThat(OaEmployeeNumberCatalog.alias("SZST0687"))
                .isEqualTo("SZST0687");
        assertThat(OaEmployeeNumberCatalog.alias("SZSTSX71"))
                .isEqualTo("SZSTSX71");
    }

    @Test
    void suppressesLiTanWrongNinetyMinuteLineAlreadyOwnedByMaJinhan() {
        Instant start = LocalDateTime.of(2026, 8, 17, 18, 30)
                .atZone(SHANGHAI)
                .toInstant();
        Instant end = LocalDateTime.of(2026, 8, 17, 20, 0)
                .atZone(SHANGHAI)
                .toInstant();
        assertThat(OaEmployeeNumberCatalog.suppressOvertime(
                "SZST0263", start, end))
                .isTrue();
        Instant otherEnd = LocalDateTime.of(2026, 8, 17, 21, 0)
                .atZone(SHANGHAI)
                .toInstant();
        assertThat(OaEmployeeNumberCatalog.suppressOvertime(
                "SZST0263", start, otherEnd))
                .isFalse();
        assertThat(OaEmployeeNumberCatalog.suppressOvertime(
                "SZST0660", start, end))
                .isFalse();
    }
}
