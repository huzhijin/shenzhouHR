package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class JdkDeliEplusHttpTransport implements DeliEplusHttpTransport {

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxResponseBytes;

    JdkDeliEplusHttpTransport(DeliEplusProperties properties) {
        properties.validateForEnabledClient();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = properties.getRequestTimeout();
        this.maxResponseBytes = properties.getMaxResponseBytes();
    }

    @Override
    public DeliEplusHttpResponse post(DeliEplusHttpRequest request) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            request.body(),
                            StandardCharsets.UTF_8));
            request.headers().forEach(builder::header);
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                byte[] bytes = responseBody.readNBytes(maxResponseBytes + 1);
                if (bytes.length > maxResponseBytes) {
                    throw new DeliEplusClientException(
                            "DELI_RESPONSE_TOO_LARGE",
                            "Deli E+ response exceeded the configured safe size limit",
                            false);
                }
                return new DeliEplusHttpResponse(
                        response.statusCode(),
                        new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (DeliEplusClientException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeliEplusClientException(
                    "DELI_TRANSPORT_INTERRUPTED",
                    "Deli E+ request was interrupted",
                    false);
        } catch (Exception exception) {
            throw new DeliEplusClientException(
                    "DELI_TRANSPORT_FAILURE",
                    "Deli E+ request could not be completed",
                    true);
        }
    }
}
