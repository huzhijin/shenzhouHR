package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

@FunctionalInterface
interface DeliEplusHttpTransport {

    DeliEplusHttpResponse post(DeliEplusHttpRequest request);
}

final class DeliEplusHttpRequest {

    private final URI uri;
    private final Map<String, String> headers;
    private final String body;

    DeliEplusHttpRequest(
            URI uri,
            Map<String, String> headers,
            String body) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = Map.copyOf(headers);
        this.body = Objects.requireNonNull(body, "body");
    }

    URI uri() {
        return uri;
    }

    Map<String, String> headers() {
        return headers;
    }

    String body() {
        return body;
    }

    @Override
    public String toString() {
        return "DeliEplusHttpRequest[uri=" + uri
                + ", headerNames=" + headers.keySet()
                + ", body=<redacted>]";
    }
}

record DeliEplusHttpResponse(int statusCode, String body) {

    DeliEplusHttpResponse {
        Objects.requireNonNull(body, "body");
    }

    @Override
    public String toString() {
        return "DeliEplusHttpResponse[statusCode=" + statusCode
                + ", body=<redacted>]";
    }
}
