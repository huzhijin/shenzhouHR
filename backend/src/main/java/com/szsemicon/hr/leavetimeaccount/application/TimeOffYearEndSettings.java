package com.szsemicon.hr.leavetimeaccount.application;

import java.time.Duration;
import java.time.ZoneId;

public record TimeOffYearEndSettings(
        Duration lockLease,
        ZoneId businessZone,
        String lockOwner) {

    public TimeOffYearEndSettings {
        if (lockLease == null
                || lockLease.compareTo(Duration.ofSeconds(30)) < 0
                || lockLease.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "TIME_OFF year-end lock lease must be between PT30S and PT24H");
        }
        if (businessZone == null) {
            throw new IllegalArgumentException(
                    "TIME_OFF year-end business zone is required");
        }
        if (lockOwner == null || lockOwner.isBlank()
                || lockOwner.length() > 128
                || lockOwner.chars().anyMatch(character ->
                        character < 0x20 || character > 0x7e)) {
            throw new IllegalArgumentException(
                    "TIME_OFF year-end lock owner must contain 1-128 printable ASCII characters");
        }
    }
}
