package com.szsemicon.hr.people.domain;

import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 共享精确工龄台账计算器。
 * 使用统一精确分数累积（分母131400），支持新365年数、Excel360导入、实际天数、历史legacy记录。
 * 规则：
 * - 1年 = 131400单位，0.01年 = 1314单位
 * - 实际天 = 360单位，Excel360天 = 365单位
 * - normalized days = units/360 四舍五入
 * - completeMonths = units/10950 向下取整
 */
public final class PriorServiceLedger {

    private PriorServiceLedger() {
    }

    /**
     * 重放记录列表，返回逐行累计及最终汇总。
     */
    public static ReplayResult replay(List<PriorServiceRecord> records) {
        long cumulativeUnits = 0;
        int previousStored = 0;
        List<LineItem> items = new ArrayList<>();

        for (PriorServiceRecord record : records) {
            long recordUnits;
            long previousUnits = cumulativeUnits;

            // REVERSAL记录：区分新version1（精确反向）和旧全null（legacy分支）
            if ("REVERSAL".equals(record.recordType())) {
                // 旧REVERSAL：metadata全null，走legacy分支
                if (record.conversionBasis() == null
                        && record.conversionVersion() == null
                        && record.amountYears() == null) {
                    // 继续到legacy分支处理
                } else {
                    // 新REVERSAL：必须version=1且有reversalOfRecordId
                    if (record.conversionVersion() == null || record.conversionVersion() != 1) {
                        throw new IllegalStateException("REVERSAL record with metadata must have version 1: "
                                + "recordId=" + record.priorServiceRecordId()
                                + " version=" + record.conversionVersion());
                    }
                    if (record.reversalOfRecordId() == null) {
                        throw new IllegalStateException("REVERSAL record with metadata must have reversalOfRecordId: "
                                + "recordId=" + record.priorServiceRecordId());
                    }

                    LineItem originalItem = items.stream()
                            .filter(item -> item.recordId().equals(record.reversalOfRecordId()))
                            .findFirst()
                            .orElse(null);

                    if (originalItem == null) {
                        throw new IllegalStateException("REVERSAL target record not found in replay: "
                                + "recordId=" + record.priorServiceRecordId()
                                + " reversalOf=" + record.reversalOfRecordId());
                    }

                    // 拒绝第二次撤销同一target：检查是否已有其他REVERSAL指向相同target
                    boolean alreadyReversed = records.stream()
                            .filter(r -> "REVERSAL".equals(r.recordType()))
                            .filter(r -> !r.priorServiceRecordId().equals(record.priorServiceRecordId()))
                            .anyMatch(r -> record.reversalOfRecordId().equals(r.reversalOfRecordId()));

                    if (alreadyReversed) {
                        throw new IllegalStateException("REVERSAL target already reversed: "
                                + "recordId=" + record.priorServiceRecordId()
                                + " reversalOf=" + record.reversalOfRecordId());
                    }

                    recordUnits = -originalItem.deltaUnits();
                    cumulativeUnits += recordUnits;
                    if (cumulativeUnits < 0) {
                        throw new IllegalStateException("Cumulative seniority cannot be negative: "
                                + "recordId=" + record.priorServiceRecordId());
                    }

                    long absNormDays = Math.abs(cumulativeUnits) / 360L;
                    if (Math.abs(cumulativeUnits) % 360L >= 180L) {
                        absNormDays++;
                    }
                    int normalizedDays = cumulativeUnits >= 0 ? (int) absNormDays : -(int) absNormDays;

                    if (normalizedDays != record.resultingTotalDays()) {
                        throw new IllegalStateException("REVERSAL record replay invariant violated: "
                                + "recordId=" + record.priorServiceRecordId()
                                + " calculated=" + normalizedDays
                                + " vs stored=" + record.resultingTotalDays());
                    }

                    previousStored = record.resultingTotalDays();
                    items.add(new LineItem(
                            record.priorServiceRecordId(),
                            recordUnits,
                            record.amountDays(),
                            cumulativeUnits,
                            normalizedDays));
                    continue;
                }
            }

            // Excel 360导入（SET操作）
            if ("DAYS_PER_YEAR_360".equals(record.conversionBasis())) {
                // 元数据校验
                if (record.conversionVersion() == null || record.conversionVersion() != 1) {
                    throw new IllegalStateException("DAYS_PER_YEAR_360 record must have version 1: "
                            + "recordId=" + record.priorServiceRecordId());
                }
                if (record.amountYears() != null) {
                    throw new IllegalStateException("DAYS_PER_YEAR_360 record must not have amountYears: "
                            + "recordId=" + record.priorServiceRecordId());
                }
                if (record.resultingTotalDays() < 0) {
                    throw new IllegalStateException("DAYS_PER_YEAR_360 record cannot have negative target: "
                            + "recordId=" + record.priorServiceRecordId()
                            + " resultingTotalDays=" + record.resultingTotalDays());
                }

                // 旧台账校验：previousStored + amountDays == stored
                int expectedStored = previousStored + record.amountDays();
                if (expectedStored != record.resultingTotalDays()) {
                    throw new IllegalStateException("Excel import integrity violated: "
                            + "recordId=" + record.priorServiceRecordId()
                            + " expected=" + expectedStored
                            + " vs stored=" + record.resultingTotalDays());
                }

                // SET units：target = stored * 365
                long targetUnits = (long) record.resultingTotalDays() * 365L;
                recordUnits = targetUnits - previousUnits; // delta
                cumulativeUnits = targetUnits;
                previousStored = record.resultingTotalDays();

                // normalizedDays：units / 360 四舍五入
                long absNormDays = Math.abs(cumulativeUnits) / 360L;
                if (Math.abs(cumulativeUnits) % 360L >= 180L) {
                    absNormDays++;
                }
                int normalizedDays = cumulativeUnits >= 0 ? (int) absNormDays : -(int) absNormDays;

                items.add(new LineItem(
                        record.priorServiceRecordId(),
                        recordUnits,
                        record.amountDays(),
                        cumulativeUnits,
                        normalizedDays));
                continue;
            }

            // 新365记录（DAYS_PER_YEAR_365）
            if ("DAYS_PER_YEAR_365".equals(record.conversionBasis())) {
                if (record.amountYears() == null) {
                    throw new IllegalStateException("DAYS_PER_YEAR_365 record must have amountYears: "
                            + "recordId=" + record.priorServiceRecordId());
                }
                if (record.conversionVersion() == null || record.conversionVersion() != 1) {
                    throw new IllegalStateException("DAYS_PER_YEAR_365 record must have version 1: "
                            + "recordId=" + record.priorServiceRecordId());
                }

                long yearCents = record.amountYears()
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue();
                recordUnits = yearCents * 1314L;

                cumulativeUnits += recordUnits;
                if (cumulativeUnits < 0) {
                    throw new IllegalStateException("Cumulative seniority cannot be negative: "
                            + "recordId=" + record.priorServiceRecordId());
                }

                long absNormDays = Math.abs(cumulativeUnits) / 360L;
                if (Math.abs(cumulativeUnits) % 360L >= 180L) {
                    absNormDays++;
                }
                int normalizedDays = cumulativeUnits >= 0 ? (int) absNormDays : -(int) absNormDays;

                if (normalizedDays != record.resultingTotalDays()) {
                    throw new IllegalStateException("DAYS_PER_YEAR_365 record replay invariant violated: "
                            + "recordId=" + record.priorServiceRecordId()
                            + " calculated=" + normalizedDays
                            + " vs stored=" + record.resultingTotalDays());
                }

                previousStored = record.resultingTotalDays();
                items.add(new LineItem(
                        record.priorServiceRecordId(),
                        recordUnits,
                        record.amountDays(),
                        cumulativeUnits,
                        normalizedDays));
                continue;
            }

            // 新ACTUAL_DAYS记录（旧API新写入）
            if ("ACTUAL_DAYS".equals(record.conversionBasis())) {
                if (record.amountYears() != null) {
                    throw new IllegalStateException("ACTUAL_DAYS record must not have amountYears: "
                            + "recordId=" + record.priorServiceRecordId());
                }
                if (record.conversionVersion() == null || record.conversionVersion() != 1) {
                    throw new IllegalStateException("ACTUAL_DAYS record must have version 1: "
                            + "recordId=" + record.priorServiceRecordId());
                }

                recordUnits = (long) record.amountDays() * 360L;
                cumulativeUnits += recordUnits;
                if (cumulativeUnits < 0) {
                    throw new IllegalStateException("Cumulative seniority cannot be negative: "
                            + "recordId=" + record.priorServiceRecordId());
                }

                long absNormDays = Math.abs(cumulativeUnits) / 360L;
                if (Math.abs(cumulativeUnits) % 360L >= 180L) {
                    absNormDays++;
                }
                int normalizedDays = cumulativeUnits >= 0 ? (int) absNormDays : -(int) absNormDays;

                if (normalizedDays != record.resultingTotalDays()) {
                    throw new IllegalStateException("ACTUAL_DAYS record replay invariant violated: "
                            + "recordId=" + record.priorServiceRecordId()
                            + " calculated=" + normalizedDays
                            + " vs stored=" + record.resultingTotalDays());
                }

                previousStored = record.resultingTotalDays();
                items.add(new LineItem(
                        record.priorServiceRecordId(),
                        recordUnits,
                        record.amountDays(),
                        cumulativeUnits,
                        normalizedDays));
                continue;
            }

            // 历史legacy记录：仅允许basis/version/years均null
            if (record.conversionBasis() != null || record.conversionVersion() != null || record.amountYears() != null) {
                throw new IllegalStateException("Legacy record must have null basis, version and amountYears: "
                        + "recordId=" + record.priorServiceRecordId()
                        + " basis=" + record.conversionBasis()
                        + " version=" + record.conversionVersion()
                        + " amountYears=" + record.amountYears());
            }

            // 旧台账校验：previousStored + amountDays == stored
            int expectedStored = previousStored + record.amountDays();
            if (expectedStored != record.resultingTotalDays()) {
                throw new IllegalStateException("Legacy record integrity violated: "
                        + "recordId=" + record.priorServiceRecordId()
                        + " expected=" + expectedStored
                        + " vs stored=" + record.resultingTotalDays());
            }

            recordUnits = (long) record.amountDays() * 360L;
            cumulativeUnits += recordUnits;
            if (cumulativeUnits < 0) {
                throw new IllegalStateException("Cumulative seniority cannot be negative: "
                        + "recordId=" + record.priorServiceRecordId());
            }

            // legacy: 使用units计算normalized，但校验stored链
            long absNormDays = Math.abs(cumulativeUnits) / 360L;
            if (Math.abs(cumulativeUnits) % 360L >= 180L) {
                absNormDays++;
            }
            int normalizedDays = cumulativeUnits >= 0 ? (int) absNormDays : -(int) absNormDays;

            previousStored = record.resultingTotalDays();
            items.add(new LineItem(
                    record.priorServiceRecordId(),
                    recordUnits,
                    record.amountDays(),
                    cumulativeUnits,
                    normalizedDays));
        }

        BigDecimal cumulativeYears = BigDecimal.valueOf(cumulativeUnits)
                .divide(BigDecimal.valueOf(131400), 2, RoundingMode.HALF_UP);
        int completeMonths = (int) (cumulativeUnits / 10950L);

        // totalDays使用最后一条记录的normalized days（而非stored）
        int totalDays = items.isEmpty() ? 0 : items.get(items.size() - 1).cumulativeDays();

        return new ReplayResult(cumulativeUnits, cumulativeYears, totalDays, completeMonths, items);
    }

    /**
     * 计算新调整后的累计（用于预检/发布），不校验stored值。
     */
    public static ProjectionResult project(
            List<PriorServiceRecord> existingRecords,
            BigDecimal amountYears,
            Integer amountDays) {
        ReplayResult current = replay(existingRecords);
        long currentUnits = current.cumulativeUnits();

        long adjustmentUnits;
        if (amountYears != null) {
            adjustmentUnits = amountYears
                    .multiply(BigDecimal.valueOf(131400))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        } else {
            adjustmentUnits = (long) amountDays * 360L;
        }

        long resultingUnits = currentUnits + adjustmentUnits;
        if (resultingUnits < 0) {
            throw new IllegalStateException("Cumulative seniority cannot be negative");
        }

        long absDays = Math.abs(resultingUnits) / 360L;
        if (Math.abs(resultingUnits) % 360L >= 180L) {
            absDays++;
        }
        int resultingTotal = resultingUnits >= 0 ? (int) absDays : -(int) absDays;

        return new ProjectionResult(resultingUnits, resultingTotal);
    }

    /**
     * 计算reversal的精确单位（用于撤销）。
     */
    public static long reversalUnits(PriorServiceRecord originalRecord) {
        if ("DAYS_PER_YEAR_365".equals(originalRecord.conversionBasis())
                && originalRecord.amountYears() != null) {
            long yearCents = originalRecord.amountYears()
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
            return -(yearCents * 1314L);
        } else {
            return -(long) originalRecord.amountDays() * 360L;
        }
    }

    public record ReplayResult(
            long cumulativeUnits,
            BigDecimal cumulativeYears,
            int totalDays,
            int completeMonths,
            List<LineItem> items) {

        /**
         * 计算对外显示的工龄年数，用累计units/131400保留完整精度。
         * 避免completeMonths/12的精度丢失（0.01年 → 0完整月 → 0.00年）。
         */
        public BigDecimal priorYears() {
            return BigDecimal.valueOf(cumulativeUnits)
                    .divide(BigDecimal.valueOf(131400), 2, RoundingMode.HALF_UP);
        }
    }

    public record LineItem(
            String recordId,
            long deltaUnits,
            int deltaDays,
            long cumulativeUnits,
            int cumulativeDays) {
    }

    public record ProjectionResult(
            long resultingUnits,
            int resultingTotalDays) {
    }
}
