package com.szsemicon.hr.reporting.infrastructure.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.szsemicon.hr.reporting.application.MapBasemapProperties;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PunchBasemapClientTest {
    private final MapBasemapProperties props = new MapBasemapProperties();
    private final HttpClient http = mock(HttpClient.class);
    private final PunchBasemapClient client = new PunchBasemapClient(props, new ObjectMapper(), http);

    @Test
    void convertsWgs84ThenDrawsExactlyOneMarkerWithoutExposingKey() throws Exception {
        configure();
        List<HttpRequest> requests = new ArrayList<>();
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return requests.size() == 1
                    ? response(200, "{\"status\":\"1\",\"locations\":\"121.62,38.92\"}".getBytes(StandardCharsets.UTF_8))
                    : response(200, new byte[]{(byte)137,80,78,71,13,10,26,10,0});
        });
        var image = client.renderWgs84(new BigDecimal("121.614"), new BigDecimal("38.914"));
        assertThat(image).isPresent();
        assertThat(image.get()).startsWith("data:image/png;base64,").doesNotContain("synthetic-key", "121.");
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().uri().getQuery()).contains("coordsys=gps", "locations=121.614000,38.914000");
        assertThat(requests.get(1).uri().getQuery()).contains("location=121.620000,38.920000", "markers=mid,0xE5484D,A:121.620000,38.920000").doesNotContain("|");
        assertThat(requests.getFirst().timeout()).hasValue(java.time.Duration.ofSeconds(3));
    }

    @Test
    void unsupportedOrMissingConfigurationNeverContactsProvider() {
        props.setBasemapProvider("OTHER");
        props.setBasemapKey("synthetic-key");
        assertThat(client.renderWgs84(BigDecimal.ONE, BigDecimal.ONE)).isEmpty();
        verifyNoInteractions(http);
    }

    @Test
    void conversionFailureDoesNotRequestStaticMap() throws Exception {
        configure();
        when(http.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> response(200, "{\"status\":\"0\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(client.renderWgs84(BigDecimal.ONE, BigDecimal.ONE)).isEmpty();
        verify(http).send(any(), any());
    }

    @Test
    void timeoutReturnsTextFallbackWithoutThrowingProviderUrl() throws Exception {
        configure();
        when(http.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.net.http.HttpTimeoutException("sensitive provider URL"));
        assertThat(client.renderWgs84(BigDecimal.ONE, BigDecimal.ONE)).isEmpty();
    }

    @Test
    void nonImageResponseIsNotReturnedToBrowser() throws Exception {
        configure();
        when(http.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> response(200, "{\"status\":\"1\",\"locations\":\"121,38\"}".getBytes(StandardCharsets.UTF_8)))
                .thenAnswer(invocation -> response(200, "provider-error-containing-key".getBytes(StandardCharsets.UTF_8)));
        assertThat(client.renderWgs84(BigDecimal.ONE, BigDecimal.ONE)).isEmpty();
    }

    private void configure() {
        props.setBasemapProvider("AMAP");
        props.setBasemapKey("synthetic-key");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, byte[] bytes) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(bytes);
        return response;
    }
}
