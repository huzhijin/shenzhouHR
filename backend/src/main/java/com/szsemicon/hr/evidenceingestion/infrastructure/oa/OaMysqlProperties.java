package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shenzhouhr.integrations.oa-mysql")
public final class OaMysqlProperties {

    static final ZoneId REQUIRED_SOURCE_TIME_ZONE =
            ZoneId.of("Asia/Shanghai");

    private static final Duration MIN_CONNECTION_TIMEOUT =
            Duration.ofMillis(250);
    private static final Duration MAX_CONNECTION_TIMEOUT =
            Duration.ofSeconds(30);
    private static final Duration MIN_QUERY_TIMEOUT =
            Duration.ofSeconds(1);
    private static final Duration MAX_QUERY_TIMEOUT =
            Duration.ofSeconds(30);
    private static final Set<String> FORBIDDEN_JDBC_URL_PROPERTIES =
            Set.of(
                    "user",
                    "password",
                    "password1",
                    "password2",
                    "password3",
                    "propertiestransform",
                    "useconfigs",
                    "readonlypropagatestoserver",
                    "uselocalsessionstate",
                    "allowmultiqueries");

    private boolean enabled;
    private String jdbcUrl = "";
    private String username = "";
    private String password = "";
    private ZoneId sourceTimeZone = REQUIRED_SOURCE_TIME_ZONE;
    private int maximumPoolSize = 2;
    private Duration connectionTimeout = Duration.ofSeconds(5);
    private Duration queryTimeout = Duration.ofSeconds(3);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public ZoneId getSourceTimeZone() {
        return sourceTimeZone;
    }

    public void setSourceTimeZone(ZoneId sourceTimeZone) {
        this.sourceTimeZone = sourceTimeZone;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    void validateEnabled() {
        if (!enabled) {
            throw new IllegalStateException(
                    "OA MySQL integration is disabled");
        }
        if (!safeJdbcUrl(jdbcUrl)) {
            throw new IllegalStateException(
                    "OA MySQL JDBC URL is invalid");
        }
        if (!safeCredential(username) || !safeCredential(password)) {
            throw new IllegalStateException(
                    "OA MySQL credentials are not configured safely");
        }
        if (!REQUIRED_SOURCE_TIME_ZONE.equals(sourceTimeZone)) {
            throw new IllegalStateException(
                    "OA MySQL source time zone must be Asia/Shanghai");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 4) {
            throw new IllegalStateException(
                    "OA MySQL pool size must be between 1 and 4");
        }
        if (!within(
                        connectionTimeout,
                        MIN_CONNECTION_TIMEOUT,
                        MAX_CONNECTION_TIMEOUT)
                || !within(
                        queryTimeout,
                        MIN_QUERY_TIMEOUT,
                        MAX_QUERY_TIMEOUT)) {
            throw new IllegalStateException(
                    "OA MySQL timeouts are invalid");
        }
    }

    int queryTimeoutSeconds() {
        long seconds = queryTimeout.getSeconds();
        if (queryTimeout.getNano() > 0) {
            seconds++;
        }
        return Math.toIntExact(seconds);
    }

    @Override
    public String toString() {
        return "OaMysqlProperties[enabled="
                + enabled
                + ", jdbcUrl=<redacted>, username=<redacted>"
                + ", password=<redacted>, maximumPoolSize="
                + maximumPoolSize
                + ", sourceTimeZone="
                + sourceTimeZone
                + ", connectionTimeout="
                + connectionTimeout
                + ", queryTimeout="
                + queryTimeout
                + "]";
    }

    private static boolean safeJdbcUrl(String value) {
        if (value == null
                || !value.startsWith("jdbc:mysql://")
                || value.length() > 2_048
                || containsUnsafeText(value)) {
            return false;
        }

        try {
            URI uri = new URI(value.substring("jdbc:".length()));
            if (!"mysql".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getPath() == null
                    || uri.getPath().length() <= 1) {
                return false;
            }
            return hasNoForbiddenJdbcUrlProperties(uri.getRawQuery());
        } catch (URISyntaxException
                | IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean safeCredential(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 512
                && !containsUnsafeText(value);
    }

    private static boolean hasNoForbiddenJdbcUrlProperties(
            String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return true;
        }
        for (String pair : rawQuery.split("&", -1)) {
            int separator = pair.indexOf('=');
            String rawName =
                    separator < 0 ? pair : pair.substring(0, separator);
            String name;
            try {
                name = URLDecoder.decode(
                                rawName, StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException exception) {
                return false;
            }
            if (FORBIDDEN_JDBC_URL_PROPERTIES.contains(name)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsUnsafeText(String value) {
        return value.chars().anyMatch(character ->
                Character.isISOControl(character)
                        || Character.getType(character)
                                == Character.LINE_SEPARATOR
                        || Character.getType(character)
                                == Character.PARAGRAPH_SEPARATOR);
    }

    private static boolean within(
            Duration value,
            Duration minimum,
            Duration maximum) {
        return value != null
                && value.compareTo(minimum) >= 0
                && value.compareTo(maximum) <= 0;
    }
}
