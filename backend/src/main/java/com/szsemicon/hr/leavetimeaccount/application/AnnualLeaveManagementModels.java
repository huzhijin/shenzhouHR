package com.szsemicon.hr.leavetimeaccount.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AnnualLeaveManagementModels {

    private AnnualLeaveManagementModels() {
    }

    public record LeaveAccountView(
            String accountId,
            String employeeId,
            int year,
            BigDecimal balanceHours,
            BigDecimal equivalentDays,
            long rowVersion,
            List<LedgerEntryView> entries,
            long totalEntries) {
    }

    public record LedgerEntryView(
            String entryId,
            String entryType,
            String entryTypeLabel,
            BigDecimal amountHours,
            String sourceType,
            LocalDate businessDate,
            LocalDate effectiveFrom,
            LocalDate expiresOn,
            Instant occurredAt) {
    }

    public record OpeningBalanceCommand(
            String employeeId,
            BigDecimal balanceHours,
            int year,
            LocalDate openingDate,
            String reason,
            String requestId) {

        public OpeningBalanceCommand {
            Objects.requireNonNull(employeeId, "employeeId");
            Objects.requireNonNull(balanceHours, "balanceHours");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(requestId, "requestId");
            if (balanceHours.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("opening balance cannot be negative");
            }
            if (balanceHours.compareTo(new BigDecimal("9999")) > 0) {
                throw new IllegalArgumentException("opening balance too large");
            }
            if (year < 2000 || year > 2100) {
                throw new IllegalArgumentException("year out of range");
            }
            if (openingDate == null) {
                openingDate = defaultOpeningDate(year);
            }
            if (openingDate.getYear() != year) {
                throw new IllegalArgumentException(
                        "opening date must fall inside the account year");
            }
        }
    }

    public record AdjustBalanceCommand(
            String employeeId,
            BigDecimal adjustmentHours,
            int year,
            String reason,
            String requestId) {

        public AdjustBalanceCommand {
            Objects.requireNonNull(employeeId, "employeeId");
            Objects.requireNonNull(adjustmentHours, "adjustmentHours");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(requestId, "requestId");
            if (adjustmentHours.abs().compareTo(new BigDecimal("9999")) > 0) {
                throw new IllegalArgumentException("adjustment too large");
            }
            if (year < 2000 || year > 2100) {
                throw new IllegalArgumentException("year out of range");
            }
        }
    }

    /** Derive year-end expiry date for opening/grant entries. */
    public static LocalDate yearEnd(int year) {
        return LocalDate.of(year, 12, 31);
    }

    /**
     * Default business date for an opening-balance entry.
     *
     * <p>The customer's annual-leave cycle starts on 1 August, so opening
     * balances are booked on that date rather than on the calendar-year
     * boundary or on the day the import happens.</p>
     */
    public static LocalDate defaultOpeningDate(int year) {
        return LocalDate.of(year, 8, 1);
    }

    /** Entry type labels in Chinese. */
    public static String entryTypeLabel(String entryType) {
        return switch (entryType) {
            case "OPENING" -> "期初录入";
            case "GRANT" -> "系统发放";
            case "OVERTIME_CREDIT" -> "加班换休";
            case "MANUAL_INCREASE" -> "手动增加";
            case "USED" -> "已使用";
            case "EXPIRED" -> "已到期";
            case "RETURN" -> "退回";
            case "MANUAL_DEDUCTION" -> "手动扣减";
            default -> entryType;
        };
    }

    /** Generate a stable account ID for an employee + year (deterministic). */
    public static String accountId(String employeeId, int year) {
        return UUID.nameUUIDFromBytes(
                ("ANNUAL_LEAVE:" + employeeId + ":" + year)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }
}
