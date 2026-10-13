package com.szsemicon.hr.evidenceingestion.infrastructure.ocr;

import com.szsemicon.hr.evidenceingestion.application.paper.OcrPort;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BaiduOcrAdapter implements OcrPort {

    private static final Logger log = LoggerFactory.getLogger(BaiduOcrAdapter.class);
    private static final String TOKEN_URL =
            "https://aip.baidubce.com/oauth/2.0/token";
    private static final String OCR_URL =
            "https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic";
    private static final int MAX_EDGE = 1600;

    private final String apiKey;
    private final String secretKey;
    private volatile String cachedToken;

    public BaiduOcrAdapter(
            @Value("${shenzhouhr.ocr.baidu-api-key:}") String apiKey,
            @Value("${shenzhouhr.ocr.baidu-secret-key:}") String secretKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.secretKey = secretKey == null ? "" : secretKey;
    }

    @Override
    public String recognize(BufferedImage image) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            log.warn("paper overtime OCR skipped: Baidu keys are blank");
            return "";
        }
        try {
            String token = token();
            if (token == null || token.isBlank()) {
                log.warn("paper overtime OCR failed: empty Baidu token");
                return "";
            }
            String imageParam = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(jpeg(image)),
                    StandardCharsets.UTF_8);
            String body = "image=" + imageParam;
            HttpURLConnection connection = (HttpURLConnection) URI
                    .create(OCR_URL + "?access_token=" + token)
                    .toURL()
                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty(
                    "Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            if (stream == null) {
                log.warn("paper overtime OCR failed: HTTP {}", status);
                return "";
            }
            String json;
            try (InputStream input = stream) {
                json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (json.contains("\"error_code\"")) {
                log.warn("paper overtime OCR Baidu error: {}",
                        json.length() > 240 ? json.substring(0, 240) : json);
                return "";
            }
            return extractWords(json);
        } catch (Exception exception) {
            log.warn("paper overtime OCR failed", exception);
            return "";
        }
    }

    static byte[] jpeg(BufferedImage source) throws Exception {
        BufferedImage scaled = downscale(source, MAX_EDGE);
        BufferedImage rgb = new BufferedImage(
                scaled.getWidth(),
                scaled.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        graphics.drawImage(scaled, 0, 0, null);
        graphics.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.82f);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output =
                new MemoryCacheImageOutputStream(buffer)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    static BufferedImage downscale(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int edge = Math.max(width, height);
        if (edge <= maxEdge) {
            return source;
        }
        double scale = maxEdge / (double) edge;
        int nextWidth = Math.max(1, (int) Math.round(width * scale));
        int nextHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage scaled = new BufferedImage(
                nextWidth, nextHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, nextWidth, nextHeight, null);
        graphics.dispose();
        return scaled;
    }

    private String token() throws Exception {
        if (cachedToken != null) {
            return cachedToken;
        }
        String params = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&client_secret="
                + URLEncoder.encode(secretKey, StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) URI
                .create(TOKEN_URL)
                .toURL()
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(params.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream input = connection.getInputStream()) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = json.indexOf("\"access_token\":\"");
            if (start < 0) {
                log.warn("paper overtime OCR token response had no access_token");
                return "";
            }
            start += 16;
            int end = json.indexOf('"', start);
            cachedToken = json.substring(start, end);
            return cachedToken;
        }
    }

    private static String extractWords(String json) {
        StringBuilder text = new StringBuilder();
        int cursor = 0;
        while (true) {
            int start = indexOfWords(json, cursor);
            if (start < 0) {
                break;
            }
            int end = json.indexOf('"', start);
            if (end < 0) {
                break;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(json, start, end);
            cursor = end + 1;
        }
        return text.toString();
    }

    private static int indexOfWords(String json, int cursor) {
        int compact = json.indexOf("\"words\":\"", cursor);
        int spaced = json.indexOf("\"words\": \"", cursor);
        if (compact < 0) {
            return spaced < 0 ? -1 : spaced + 10;
        }
        if (spaced < 0 || compact < spaced) {
            return compact + 9;
        }
        return spaced + 10;
    }
}
