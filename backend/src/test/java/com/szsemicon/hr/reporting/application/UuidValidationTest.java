package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for strict UUID validation.
 * Tests that validateUuid rejects invalid formats like all-hyphens and all-hex-no-hyphens.
 */
class UuidValidationTest {

    /**
     * Reflectively invoke the private validateUuid method.
     */
    private static void validateUuid(String value, String fieldName) {
        try {
            var method = PunchExemptionRepairService.class.getDeclaredMethod(
                    "validateUuid", String.class, String.class);
            method.setAccessible(true);
            method.invoke(null, value, fieldName);
        } catch (Exception e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e.getCause();
            }
            throw new RuntimeException("Unexpected reflection error", e);
        }
    }

    @Test
    void validUuid_passes() {
        String valid = UUID.randomUUID().toString();
        assertThatCode(() -> validateUuid(valid, "testField"))
                .doesNotThrowAnyException();
    }

    @Test
    void validUuid_lowerCase_passes() {
        String valid = "550e8400-e29b-41d4-a716-446655440000";
        assertThatCode(() -> validateUuid(valid, "testField"))
                .doesNotThrowAnyException();
    }

    @Test
    void allHyphens_rejected() {
        String allHyphens = "------------------------------------";
        assertThatThrownBy(() -> validateUuid(allHyphens, "testField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID")
                .hasMessageContaining("testField");
    }

    @Test
    void allHexNoHyphens_rejected() {
        String allHex = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; // 36 chars, no hyphens
        assertThatThrownBy(() -> validateUuid(allHex, "testField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID")
                .hasMessageContaining("testField");
    }

    @Test
    void wrongHyphenPositions_rejected() {
        String wrongHyphens = "550e8400-e29b41d4-a716-446655440000"; // missing one hyphen
        assertThatThrownBy(() -> validateUuid(wrongHyphens, "testField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void tooShort_rejected() {
        String tooShort = "550e8400-e29b-41d4-a716";
        assertThatThrownBy(() -> validateUuid(tooShort, "testField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void tooLong_rejected() {
        String tooLong = "550e8400-e29b-41d4-a716-446655440000-extra";
        assertThatThrownBy(() -> validateUuid(tooLong, "testField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void nullValue_rejected() {
        assertThatThrownBy(() -> validateUuid(null, "testField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void upperCaseMixed_rejected() {
        // UUID.fromString accepts mixed case but toString returns lowercase
        // Our validation requires round-trip equality, so mixed case should fail
        String mixedCase = "550E8400-e29b-41d4-A716-446655440000";
        assertThatThrownBy(() -> validateUuid(mixedCase, "testField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须为有效 UUID 格式")
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .extracting(e -> e.getCause().getMessage())
                .asString()
                .contains("标准小写");
    }
}
