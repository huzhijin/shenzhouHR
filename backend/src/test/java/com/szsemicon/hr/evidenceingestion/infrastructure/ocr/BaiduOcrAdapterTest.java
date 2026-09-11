package com.szsemicon.hr.evidenceingestion.infrastructure.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BaiduOcrAdapterTest {

    @Test
    void jpegFitsBaiduPayloadForPhonePhotos() throws Exception {
        BufferedImage photo = new BufferedImage(
                3200, 2400, BufferedImage.TYPE_INT_RGB);
        byte[] jpeg = BaiduOcrAdapter.jpeg(photo);
        assertThat(jpeg.length).isGreaterThan(1000);
        assertThat(jpeg.length).isLessThan(1_500_000);
        assertThat(BaiduOcrAdapter.downscale(photo, 1600).getWidth())
                .isEqualTo(1600);
    }
}
