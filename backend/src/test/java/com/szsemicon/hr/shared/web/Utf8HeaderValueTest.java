package com.szsemicon.hr.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Utf8HeaderValueTest {

    @Test
    void decodesBrowserSafeUtf8PercentEncoding() {
        assertThat(Utf8HeaderValue.decode(
                "UTF-8''%E5%A4%8D%E6%A0%B8%E5%B9%B4%E6%9C%AB%2B%E7%94%9F%E4%BA%A7%E6%97%A5"))
                .isEqualTo("复核年末+生产日");
    }

    @Test
    void keepsExistingAsciiAndMockMvcValuesBackwardCompatible() {
        assertThat(Utf8HeaderValue.decode("calendar year-end review"))
                .isEqualTo("calendar year-end review");
        assertThat(Utf8HeaderValue.decode("WAVE-3 工作日设置"))
                .isEqualTo("WAVE-3 工作日设置");
    }
}
