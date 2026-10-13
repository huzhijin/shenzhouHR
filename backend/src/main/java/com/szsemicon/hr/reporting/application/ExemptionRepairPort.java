package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.ExemptionRow;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairReceipt;
import java.time.Instant;
import java.util.Optional;

/**
 * 免打卡修复服务的数据访问端口。
 */
public interface ExemptionRepairPort {

    /**
     * 根据 exemptionId 查找免打卡记录（FOR UPDATE 悲观锁）。
     * 仅返回 source='STANDING_LIST' 且 valid_to IS NULL 的有效记录。
     */
    Optional<ExemptionRow> findExemption(String exemptionId);

    /**
     * CAS 更新免打卡记录的生效时间。
     * WHERE exemption_id = ? AND valid_from = expectedValidFrom
     *
     * @return 实际更新的行数（0=旧值不匹配或不存在，1=成功）
     */
    int updateExemptionValidFrom(String exemptionId, Instant expectedValidFrom, Instant newValidFrom);

    /**
     * 插入修复回执（幂等：exemptionId + operation 唯一约束）。
     */
    void insertRepairReceipt(RepairReceipt receipt);

    /**
     * 查询已存在的修复回执，用于幂等路径返回真实持久化记录。
     */
    Optional<RepairReceipt> findReceipt(String exemptionId, String operation);
}
