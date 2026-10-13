package com.szsemicon.hr.evidenceingestion.domain.oa;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * Seeyon OA stores naive {@code DATETIME} values in Asia/Shanghai. JDBC with
 * {@code connectionTimeZone=UTC} turns {@code 08:30} into {@code 08:30Z}, and
 * the report then renders it as {@code 16:30}. Always lift the wall clock
 * through Asia/Shanghai instead of {@link Timestamp#toInstant()}.
 */
public final class OaSourceDateTimes {

    public static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Shanghai");
    public static final String SOURCE_VERSION_ZONE_MARK = "Asia/Shanghai";

    private static final DateTimeFormatter SOURCE_VERSION_TIME_FORMAT =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .toFormatter();

    private OaSourceDateTimes() {
    }

    public static Instant wallClock(ResultSet resultSet, String column)
            throws SQLException {
        LocalDateTime local = resultSet.getObject(column, LocalDateTime.class);
        if (local == null) {
            Timestamp timestamp = resultSet.getTimestamp(column);
            if (timestamp == null) {
                return null;
            }
            local = timestamp.toLocalDateTime();
        }
        return local.atZone(SOURCE_ZONE).toInstant();
    }

    public static Instant inclusiveDateStart(ResultSet resultSet, String column)
            throws SQLException {
        LocalDateTime local = resultSet.getObject(column, LocalDateTime.class);
        if (local == null) {
            Timestamp timestamp = resultSet.getTimestamp(column);
            if (timestamp == null) {
                return null;
            }
            local = timestamp.toLocalDateTime();
        }
        return local.toLocalDate().atStartOfDay(SOURCE_ZONE).toInstant();
    }

    public static Instant inclusiveDateEndExclusive(
            ResultSet resultSet, String column) throws SQLException {
        LocalDateTime local = resultSet.getObject(column, LocalDateTime.class);
        if (local == null) {
            Timestamp timestamp = resultSet.getTimestamp(column);
            if (timestamp == null) {
                return null;
            }
            local = timestamp.toLocalDateTime();
        }
        return local.toLocalDate()
                .plusDays(1)
                .atStartOfDay(SOURCE_ZONE)
                .toInstant();
    }

    public static String sourceVersion(Timestamp lastModified, Integer state) {
        if (lastModified == null || state == null) {
            return null;
        }
        return SOURCE_VERSION_TIME_FORMAT.format(lastModified.toLocalDateTime())
                + ":state="
                + state
                + ":"
                + SOURCE_VERSION_ZONE_MARK;
    }
}
