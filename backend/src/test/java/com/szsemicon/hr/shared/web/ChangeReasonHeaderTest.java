package com.szsemicon.hr.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChangeReasonHeaderTest {

    @Test
    void acceptsMatchingAsciiAndEncodedUnicodeReasons() {
        assertThat(ChangeReasonHeader.requireMatches(
                        "calendar review", "calendar review"))
                .isEqualTo("calendar review");
        assertThat(ChangeReasonHeader.requireMatches(
                        "UTF-8''%E5%8F%91%E5%B8%83%E7%AD%96%E7%95%A5",
                        "发布策略"))
                .isEqualTo("发布策略");
    }

    @Test
    void rejectsMalformedMismatchedAndOutOfRangeReasons() {
        assertThatThrownBy(() -> ChangeReasonHeader.requireMatches(
                        "first reason", "second reason"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("CHANGE_REASON_MISMATCH");
        assertThatThrownBy(() -> ChangeReasonHeader.decodeAndValidate("UTF-8''%GG"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("CHANGE_REASON_INVALID");
        assertThatThrownBy(() -> ChangeReasonHeader.decodeAndValidate(" "))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("CHANGE_REASON_INVALID");
    }
}
