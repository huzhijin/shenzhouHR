package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shenzhouhr.integrations.deli-eplus")
public final class DeliEplusProperties {

    static final URI OFFICIAL_BASE_URL = URI.create("https://v2-api.delicloud.com");

    private boolean enabled;
    private URI baseUrl = OFFICIAL_BASE_URL;
    private String appKey = "";
    private String appSecret = "";
    private int pageSize = 500;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(60);
    private int maxResponseBytes = 4 * 1024 * 1024;
    private ZoneId sourceTimeZone = ZoneId.of("Asia/Shanghai");
    private String credentialReferenceName = "DELI_EPLUS_APP_CREDENTIALS";
    private Instant kqIngestNotBefore = Instant.parse("2026-07-31T16:00:00Z");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public ZoneId getSourceTimeZone() {
        return sourceTimeZone;
    }

    public void setSourceTimeZone(ZoneId sourceTimeZone) {
        this.sourceTimeZone = sourceTimeZone;
    }

    public String getCredentialReferenceName() {
        return credentialReferenceName;
    }

    public void setCredentialReferenceName(String credentialReferenceName) {
        this.credentialReferenceName = credentialReferenceName;
    }

    public Instant getKqIngestNotBefore() {
        return kqIngestNotBefore;
    }

    public void setKqIngestNotBefore(Instant kqIngestNotBefore) {
        this.kqIngestNotBefore = kqIngestNotBefore;
    }

    void validateForEnabledClient() {
        if (!enabled) {
            throw new IllegalStateException("Deli E+ integration is disabled");
        }
        if (!isOfficialHttpsEndpoint(baseUrl)) {
            throw new IllegalStateException("Deli E+ base URL must use the official HTTPS endpoint");
        }
        if (!safeCredential(appKey) || !safeCredential(appSecret)) {
            throw new IllegalStateException("Deli E+ credentials are not configured safely");
        }
        if (pageSize < 1 || pageSize > DeliEplusClient.MAX_PAGE_SIZE) {
            throw new IllegalStateException("Deli E+ page size must be between 1 and 500");
        }
        if (!positive(connectTimeout) || !positive(requestTimeout)) {
            throw new IllegalStateException("Deli E+ timeouts must be positive");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalStateException("Deli E+ response size limit is invalid");
        }
        if (sourceTimeZone == null) {
            throw new IllegalStateException("Deli E+ source time zone is required");
        }
        if (credentialReferenceName == null
                || !credentialReferenceName.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalStateException(
                    "Deli E+ credential reference name is invalid");
        }
    }

    @Override
    public String toString() {
        return "DeliEplusProperties[enabled=" + enabled
                + ", baseUrl=" + safeBaseUrlDescription()
                + ", appKey=<redacted>, appSecret=<redacted>"
                + ", pageSize=" + pageSize
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", maxResponseBytes=" + maxResponseBytes
                + ", sourceTimeZone=" + sourceTimeZone
                + ", credentialReferenceName=" + credentialReferenceName
                + ", kqIngestNotBefore=" + kqIngestNotBefore
                + "]";
    }

    private String safeBaseUrlDescription() {
        if (baseUrl == null) {
            return "<not-configured>";
        }
        String scheme = baseUrl.getScheme() == null
                ? "<invalid>"
                : baseUrl.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = baseUrl.getHost() == null
                ? "<invalid>"
                : baseUrl.getHost().toLowerCase(java.util.Locale.ROOT);
        return scheme + "://" + host;
    }

    private static boolean isOfficialHttpsEndpoint(URI value) {
        if (value == null
                || !"https".equalsIgnoreCase(value.getScheme())
                || !"v2-api.delicloud.com".equalsIgnoreCase(value.getHost())
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null) {
            return false;
        }
        int port = value.getPort();
        String path = value.getPath();
        return (port == -1 || port == 443)
                && (path == null || path.isEmpty() || "/".equals(path));
    }

    private static boolean safeCredential(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            return false;
        }
        return value.chars().noneMatch(character ->
                Character.isISOControl(character)
                        || Character.getType(character) == Character.LINE_SEPARATOR
                        || Character.getType(character) == Character.PARAGRAPH_SEPARATOR);
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
