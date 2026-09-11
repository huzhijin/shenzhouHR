package com.szsemicon.hr.evidenceingestion.application.paper;

import java.awt.image.BufferedImage;

public interface OcrPort {

    String recognize(BufferedImage image);
}
