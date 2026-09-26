package com.szsemicon.hr.reporting.infrastructure.map;

import com.szsemicon.hr.reporting.application.MapBasemapProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Fetches exactly one map after authorization. Keys and provider URLs never reach the browser. */
@Component
public class PunchBasemapClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private final MapBasemapProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public PunchBasemapClient(MapBasemapProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    PunchBasemapClient(MapBasemapProperties properties, ObjectMapper json, HttpClient http) {
        this.properties = properties;
        this.json = json;
        this.http = http;
    }

    public Optional<String> renderWgs84(BigDecimal longitude, BigDecimal latitude) {
        if (!properties.configured() || !inBounds(longitude, latitude)) {
            return Optional.empty();
        }
        try {
            // V22 map columns are WGS84. Amap requires conversion to its own coordinate system.
            String key = encode(properties.getBasemapKey().trim());
            String point = decimal(longitude) + "," + decimal(latitude);
            var convertedResponse = get("https://restapi.amap.com/v3/assistant/coordinate/convert"
                    + "?coordsys=gps&output=JSON&locations=" + encode(point) + "&key=" + key);
            if (convertedResponse.statusCode() != 200 || convertedResponse.body().length > 16384) {
                return Optional.empty();
            }
            var converted = json.readTree(convertedResponse.body());
            if (!"1".equals(converted.path("status").asText())) {
                return Optional.empty();
            }
            String[] values = converted.path("locations").asText().split(",", -1);
            if (values.length != 2 || values[0].length() > 32 || values[1].length() > 32) {
                return Optional.empty();
            }
            BigDecimal lon = new BigDecimal(values[0]);
            BigDecimal lat = new BigDecimal(values[1]);
            if (!inBounds(lon, lat)) {
                return Optional.empty();
            }
            String mapPoint = decimal(lon) + "," + decimal(lat);
            var image = get("https://restapi.amap.com/v3/staticmap?zoom=16&size=600*360&scale=1"
                    + "&location=" + encode(mapPoint)
                    + "&markers=" + encode("mid,0xE5484D,A:" + mapPoint) + "&key=" + key);
            byte[] bytes = image.body();
            if (image.statusCode() != 200 || bytes.length > MAX_IMAGE_BYTES || !isPng(bytes)) {
                return Optional.empty();
            }
            return Optional.of("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            // Provider exceptions can contain precise coordinates and the key. Do not log them.
            return Optional.empty();
        }
    }

    private HttpResponse<byte[]> get(String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static boolean inBounds(BigDecimal longitude, BigDecimal latitude) {
        return longitude != null && latitude != null
                && longitude.abs().compareTo(BigDecimal.valueOf(180)) <= 0
                && latitude.abs().compareTo(BigDecimal.valueOf(90)) <= 0;
    }

    private static boolean isPng(byte[] bytes) {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) return false;
        }
        return true;
    }

    private static String decimal(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
