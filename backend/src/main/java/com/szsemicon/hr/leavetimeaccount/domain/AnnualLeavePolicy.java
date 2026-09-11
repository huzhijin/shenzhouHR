package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AnnualLeavePolicy(
        String policyVersionId,
        boolean qualificationRequired,
        int qualificationMonths,
        LeapDayAnniversaryRule leapDayRule,
        List<AnnualLeaveTier> tiers) {

    public AnnualLeavePolicy {
        requireText(policyVersionId, "年假策略版本不能为空");
        Objects.requireNonNull(leapDayRule, "闰日周年策略不能为空");
        if (qualificationMonths < 0) {
            throw new IllegalArgumentException("资格门槛月数不得为负数");
        }
        tiers = List.copyOf(Objects.requireNonNull(tiers, "年假档位不能为空"));
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("年假策略至少需要一个档位");
        }
        validateTiers(tiers);
    }

    public static AnnualLeavePolicy defaults(String policyVersionId) {
        return new AnnualLeavePolicy(
                policyVersionId,
                true,
                12,
                LeapDayAnniversaryRule.FEBRUARY_28,
                List.of(
                        new AnnualLeaveTier(
                                AnnualLeaveTierCode.FIVE_DAYS,
                                12,
                                120,
                                5,
                                new BigDecimal("40.00")),
                        new AnnualLeaveTier(
                                AnnualLeaveTierCode.TEN_DAYS,
                                120,
                                240,
                                10,
                                new BigDecimal("80.00")),
                        new AnnualLeaveTier(
                                AnnualLeaveTierCode.FIFTEEN_DAYS,
                                240,
                                null,
                                15,
                                new BigDecimal("120.00"))));
    }

    public AnnualLeavePolicy withQualificationMonths(int months) {
        return new AnnualLeavePolicy(
                policyVersionId,
                qualificationRequired,
                months,
                leapDayRule,
                tiers);
    }

    public AnnualLeavePolicy withLeapDayRule(LeapDayAnniversaryRule rule) {
        return new AnnualLeavePolicy(
                policyVersionId,
                qualificationRequired,
                qualificationMonths,
                rule,
                tiers);
    }

    public AnnualLeavePolicy withoutQualificationRequirement() {
        return new AnnualLeavePolicy(
                policyVersionId,
                false,
                qualificationMonths,
                leapDayRule,
                tiers);
    }

    private static void validateTiers(List<AnnualLeaveTier> tiers) {
        List<AnnualLeaveTier> sorted = tiers.stream()
                .sorted(Comparator.comparingInt(AnnualLeaveTier::minimumMonthsInclusive))
                .toList();
        if (!sorted.equals(tiers)) {
            throw new IllegalArgumentException("年假档位必须按最小累计工龄月数升序");
        }

        Set<AnnualLeaveTierCode> codes = new HashSet<>();
        Integer expectedMinimum = null;
        for (AnnualLeaveTier tier : tiers) {
            if (!codes.add(tier.code())) {
                throw new IllegalArgumentException("年假档位编码不得重复");
            }
            if (expectedMinimum != null && tier.minimumMonthsInclusive() != expectedMinimum) {
                throw new IllegalArgumentException("年假档位区间不得重叠或留空档");
            }
            expectedMinimum = tier.maximumMonthsExclusive();
            if (expectedMinimum == null && tier != tiers.getLast()) {
                throw new IllegalArgumentException("无上限档位必须位于最后");
            }
        }
        if (tiers.getLast().maximumMonthsExclusive() != null) {
            throw new IllegalArgumentException("最后一个年假档位必须无上限");
        }
    }

    static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
