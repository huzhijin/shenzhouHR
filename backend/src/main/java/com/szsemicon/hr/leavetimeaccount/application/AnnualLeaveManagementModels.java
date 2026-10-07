package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.shared.validation.IdempotencyKeyPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
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
            balanceHours = normalizeHours(balanceHours, "opening balance");
            if (balanceHours.compareTo(new BigDecimal("-9999")) < 0
                    || balanceHours.compareTo(new BigDecimal("9999")) > 0) {
                throw new IllegalArgumentException("opening balance out of range");
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
            validateMutationReferences(employeeId, reason, requestId);
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
            adjustmentHours = normalizeHours(adjustmentHours, "adjustment");
            if (adjustmentHours.abs().compareTo(new BigDecimal("9999")) > 0) {
                throw new IllegalArgumentException("adjustment too large");
            }
            if (year < 2000 || year > 2100) {
                throw new IllegalArgumentException("year out of range");
            }
            validateMutationReferences(employeeId, reason, requestId);
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
            case "ADJUSTMENT" -> "人工调整";
            case "USE" -> "已使用";
            case "EXPIRY" -> "已到期";
            case "RETURN" -> "退回";
            case "REVERSAL" -> "冲正";
            default -> entryType;
        };
    }

    /** Generate a stable annual-leave account ID for one employment period + year. */
    public static String accountId(
            String employeeId,
            String employmentPeriodId,
            int year) {
        return accountId("ANNUAL_LEAVE", employeeId, employmentPeriodId, year);
    }

    /** Generate a stable account ID for one account type + employment period + year. */
    public static String accountId(
            String accountType,
            String employeeId,
            String employmentPeriodId,
            int year) {
        return UUID.nameUUIDFromBytes(
                (accountType + ":"
                        + employeeId + ":"
                        + employmentPeriodId + ":"
                        + year)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    public static String defaultPolicyVersionId(String accountType) {
        return "TIME_OFF".equals(accountType)
                ? "TIME_OFF_DEFAULT_V1"
                : "ANNUAL_LEAVE_DEFAULT_V1";
    }

    private static BigDecimal normalizeHours(BigDecimal value, String field) {
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    field + " may contain at most two decimal places", exception);
        }
    }

    private static void validateMutationReferences(
            String employeeId,
            String reason,
            String requestId) {
        if (employeeId.isBlank() || employeeId.length() > 36) {
            throw new IllegalArgumentException("invalid employee id");
        }
        if (reason.isBlank()
                || reason.length() > 500
                || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid adjustment reason");
        }
        if (!IdempotencyKeyPolicy.isValid(requestId)) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
    }
}
