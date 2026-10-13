package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.employee.application.EmployeeAuthorizationPort;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 免卡日期修复服务
 *
 * <p>幂等地将免卡记录的 valid_from 更新到目标值。操作语义：
 * <ol>
 *   <li>权限验证：调用者必须对目标员工有 EMPLOYEE_EDIT 权限</li>
 *   <li>锁定记录：FOR UPDATE + 验证 source/validTo/employeeId/listedEmployeeNumber/companyId</li>
 *   <li>如果目标值已是当前值，查询已持久化回执并验证原请求摘要</li>
 *   <li>CAS 更新：WHERE exemptionId = ? AND valid_from = expectedValidFrom</li>
 *   <li>原子提交：更新 + 回执 + 审计在同一事务</li>
 * </ol>
 */
@Service
public class PunchExemptionRepairService {

    private static final Logger log =
            LoggerFactory.getLogger(PunchExemptionRepairService.class);

    private static final int REASON_MAX_LENGTH = 64;

    private final ExemptionRepairPort port;
    private final AuditService auditService;
    private final CurrentPrincipalProvider principalProvider;
    private final EmployeeAuthorizationPort employeeAuthorizationPort;
    private final Clock clock;

    public PunchExemptionRepairService(
            ExemptionRepairPort port,
            AuditService auditService,
            CurrentPrincipalProvider principalProvider,
            EmployeeAuthorizationPort employeeAuthorizationPort,
            Clock clock) {
        this.port = port;
        this.auditService = auditService;
        this.principalProvider = principalProvider;
        this.employeeAuthorizationPort = employeeAuthorizationPort;
        this.clock = clock;
    }

    /**
     * 修复单条免卡记录的 valid_from。
     *
     * @param intent 修复意图（含 exemptionId、预期旧值、目标新值、companyId）
     * @return 操作回执
     * @throws ResourceNotAvailableAccessDeniedException 权限不足
     * @throws IllegalStateException 旧值不匹配或员工/公司归属不符时抛出，触发事务回滚
     */
    private static final java.util.regex.Pattern HEX64_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RepairReceipt repair(RepairIntent intent) {
        // 输入验证：在任何锁或DB操作之前执行
        validateUuid(intent.requestId(), "requestId");
        if (intent.approvalEvidenceDigest() == null
                || !HEX64_PATTERN.matcher(intent.approvalEvidenceDigest()).matches()) {
            throw new IllegalArgumentException(
                    "approvalEvidenceDigest 必须为 64 位小写十六进制: "
                            + intent.approvalEvidenceDigest());
        }

        String actorId = principalProvider.currentPrincipalId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 验证时间戳精度不超过微秒（拒绝纳秒级输入）
        if (intent.expectedValidFrom().getNano() % 1000 != 0) {
            throw new IllegalArgumentException(
                    "expectedValidFrom 精度不能超过微秒: " + intent.expectedValidFrom());
        }
        if (intent.targetValidFrom().getNano() % 1000 != 0) {
            throw new IllegalArgumentException(
                    "targetValidFrom 精度不能超过微秒: " + intent.targetValidFrom());
        }

        // 权限验证：在锁行之前执行，避免FOR UPDATE持锁后才失败
        if (!employeeAuthorizationPort.canAccessEmployee(
                actorId, CapabilityCodes.EMPLOYEE_EDIT, now, intent.employeeId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }

        log.info("开始免卡日期修复: employee={}, exemptionId={}, expected={}, target={}",
                intent.employeeNumber(),
                intent.exemptionId(),
                intent.expectedValidFrom(),
                intent.targetValidFrom());

        ExemptionRow current = port.findExemption(intent.exemptionId())
                .orElseThrow(() -> new IllegalStateException(
                        "免卡记录不存在或已关闭: exemptionId=" + intent.exemptionId()));

        // 验证员工归属与公司归属
        if (!intent.employeeId().equals(current.employeeId())) {
            throw new IllegalStateException(String.format(
                    "员工ID不匹配: intent=%s, record=%s",
                    intent.employeeId(), current.employeeId()));
        }
        if (!intent.employeeNumber().equals(current.listedEmployeeNumber())) {
            throw new IllegalStateException(String.format(
                    "员工工号不匹配: intent=%s, record=%s",
                    intent.employeeNumber(), current.listedEmployeeNumber()));
        }
        if (!intent.companyId().equals(current.companyId())) {
            throw new IllegalStateException(String.format(
                    "公司不匹配: intent=%s, record=%s",
                    intent.companyId(), current.companyId()));
        }

        // 幂等：目标值已是当前值，查询已持久化的回执并严格比较原请求完整 payload
        if (intent.targetValidFrom().equals(current.validFrom())) {
            log.info("免卡日期已是目标值，查询已有回执: exemptionId={}", intent.exemptionId());
            RepairReceipt existing = port.findReceipt(intent.exemptionId(), "ADJUST_VALID_FROM")
                    .orElseThrow(() -> new IllegalStateException(
                            "免卡日期已是目标值但未找到历史回执: exemptionId=" + intent.exemptionId()));
            // 比较所有 payload 字段，防止不同请求（不同 expected/approval/requestId）蹭同一 receipt
            if (!intent.expectedValidFrom().equals(existing.originalValidFrom())) {
                throw new IllegalStateException(String.format(
                        "重放冲突：expectedValidFrom=%s 与原回执 originalValidFrom=%s 不符",
                        intent.expectedValidFrom(), existing.originalValidFrom()));
            }
            if (!intent.targetValidFrom().equals(existing.repairedValidFrom())) {
                throw new IllegalStateException(String.format(
                        "重放冲突：targetValidFrom=%s 与原回执 repairedValidFrom=%s 不符",
                        intent.targetValidFrom(), existing.repairedValidFrom()));
            }
            if (!intent.approvalEvidenceDigest().equals(existing.approvalEvidenceDigest())) {
                throw new IllegalStateException(String.format(
                        "重放冲突：approvalEvidenceDigest=%s 与原回执 approvalEvidenceDigest=%s 不符",
                        intent.approvalEvidenceDigest(), existing.approvalEvidenceDigest()));
            }
            // 规范化完整 payload 摘要必须匹配，捕获同 requestId 不同 payload 的非法重放
            String intentPayload = payloadDigest(intent);
            if (!intentPayload.equals(existing.payloadDigest())) {
                throw new IllegalStateException(String.format(
                        "重放冲突：完整 payloadDigest 不匹配（意图=%s，原回执=%s），拒绝",
                        intentPayload, existing.payloadDigest()));
            }
            // requestId 相同则为合法重放；不同则为不同请求蹭同 exemptionId，拒绝
            if (!intent.requestId().equals(existing.requestId())) {
                throw new IllegalStateException(String.format(
                        "重放冲突：requestId=%s 与原回执 requestId=%s 不符",
                        intent.requestId(), existing.requestId()));
            }
            return existing;
        }

        // CAS 更新：WHERE exemption_id = ? AND valid_from = expectedValidFrom
        int updated = port.updateExemptionValidFrom(
                intent.exemptionId(), intent.expectedValidFrom(), intent.targetValidFrom());
        if (updated != 1) {
            throw new IllegalStateException(String.format(
                    "CAS更新行数异常(%d)，可能有并发修改或旧值不匹配: exemptionId=%s, expected=%s",
                    updated, intent.exemptionId(), intent.expectedValidFrom()));
        }

        // 写后读：重新查询已更新的行，验证目标日期及获取真实字段生成 afterDigest
        ExemptionRow afterRow = port.findExemption(intent.exemptionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CAS更新后未找到免卡记录: exemptionId=" + intent.exemptionId()));

        // 验证更新后的 valid_from 确实是目标值
        if (!intent.targetValidFrom().equals(afterRow.validFrom())) {
            throw new IllegalStateException(String.format(
                    "CAS更新后读取的 valid_from=%s 与目标值=%s 不符",
                    afterRow.validFrom(), intent.targetValidFrom()));
        }

        String receiptId = UUID.randomUUID().toString();
        String intentPayloadDigest = payloadDigest(intent);
        String beforeDigest = rowDigest(current);
        String afterDigest = rowDigest(afterRow);

        RepairReceipt receipt = new RepairReceipt(
                receiptId,
                intent.requestId(),
                intent.exemptionId(),
                intent.companyId(),
                intent.employeeId(),
                "ADJUST_VALID_FROM",
                current.validFrom(),
                intent.targetValidFrom(),
                intent.approvalEvidenceDigest(),
                intentPayloadDigest,
                now,
                actorId,
                beforeDigest,
                afterDigest);

        port.insertRepairReceipt(receipt);

        String reason = truncate("rec=" + receiptId, REASON_MAX_LENGTH);
        auditService.record(
                actorId,
                "PUNCH_EXEMPTION_REPAIR",
                "PUNCH_EXEMPTION",
                intent.exemptionId(),
                "SUCCESS",
                reason,
                beforeDigest,
                afterDigest);

        // 写后读回执：返回数据库持久化的对象，确保微秒精度与DB一致
        RepairReceipt persisted = port.findReceipt(intent.exemptionId(), "ADJUST_VALID_FROM")
                .orElseThrow(() -> new IllegalStateException(
                        "回执插入后未找到: exemptionId=" + intent.exemptionId()));

        // 验证持久化回执的核心字段与预期一致
        if (!persisted.requestId().equals(intent.requestId())) {
            throw new IllegalStateException(String.format(
                    "持久化回执的 requestId=%s 与预期=%s 不符",
                    persisted.requestId(), intent.requestId()));
        }
        if (!persisted.exemptionId().equals(intent.exemptionId())) {
            throw new IllegalStateException(String.format(
                    "持久化回执的 exemptionId=%s 与预期=%s 不符",
                    persisted.exemptionId(), intent.exemptionId()));
        }

        log.info("免卡日期修复完成: receiptId={}", receiptId);
        return persisted;
    }

    /**
     * 批量修复，单事务提交。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<RepairReceipt> batchRepair(List<RepairIntent> intents) {
        List<RepairReceipt> receipts = new ArrayList<>(intents.size());
        for (RepairIntent intent : intents) {
            receipts.add(repair(intent));
        }
        return receipts;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 验证 UUID 格式：严格 8-4-4-4-12 结构，拒绝全连字符等非法输入。
     */
    private static void validateUuid(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        try {
            UUID parsed = UUID.fromString(value);
            // 往返验证：确保规范化后与输入一致（拒绝大小写混用等）
            if (!value.equals(parsed.toString())) {
                throw new IllegalArgumentException(
                        fieldName + " 必须为标准小写 UUID 格式: " + value);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    fieldName + " 必须为有效 UUID 格式（8-4-4-4-12）: " + value, e);
        }
    }

    /**
     * 生成字符串的完整 SHA-256 十六进制摘要（64字符）。
     */
    static String stableDigest(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash); // 64字符，不截断
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 基于免卡整行的规范化表示（身份/公司/工号/source/from/to/createdAt）计算稳定摘要。
     * 仅 valid_from 在修复后变化；其他字段保持不变，before/after 因此可证明精确变化范围。
     */
    static String rowDigest(ExemptionRow row) {
        String canonical = row.exemptionId() + "|"
                + row.employeeId() + "|"
                + row.listedEmployeeNumber() + "|"
                + row.companyId() + "|"
                + row.source() + "|"
                + row.validFrom() + "|"
                + (row.validTo() != null ? row.validTo() : "NULL") + "|"
                + row.createdAt();
        return stableDigest(canonical);
    }

    /**
     * 规范化完整请求摘要：绑定公司/员工ID/工号/免卡ID/source/expectedFrom/targetFrom/operation/批准依据。
     * 用于幂等重放验证；同 requestId 不同 payload 的请求必须被拒绝。
     */
    static String payloadDigest(RepairIntent intent) {
        String canonical = intent.companyId() + "|"
                + intent.employeeId() + "|"
                + intent.employeeNumber() + "|"
                + intent.exemptionId() + "|"
                + "STANDING_LIST" + "|"       // source 固定约束
                + "NULL" + "|"               // expectedValidTo IS NULL 固定约束
                + intent.expectedValidFrom() + "|"
                + intent.targetValidFrom() + "|"
                + "ADJUST_VALID_FROM" + "|"  // operation
                + intent.approvalEvidenceDigest();
        return stableDigest(canonical);
    }

    // ---- nested types expected by mapper and coordinator ----

    public record RepairIntent(
            String employeeId,
            String employeeNumber,
            String companyId,
            String exemptionId,
            Instant expectedValidFrom,
            Instant targetValidFrom,
            /** 调用方提供的幂等键，同一物理请求的重试必须携带相同值 */
            String requestId,
            /** 修复依据的规范化摘要（确认单据/批准文件的稳定内容摘要） */
            String approvalEvidenceDigest) {}

    public record RepairReceipt(
            String receiptId,
            String requestId,
            String exemptionId,
            String companyId,
            String employeeId,
            String operation,
            Instant originalValidFrom,
            Instant repairedValidFrom,
            String approvalEvidenceDigest,
            String payloadDigest,
            Instant repairedAt,
            String repairedBy,
            String beforeDigest,
            String afterDigest) {}

    public record ExemptionRow(
            String exemptionId,
            String employeeId,
            String listedEmployeeNumber,
            String companyId,
            String source,
            Instant validFrom,
            Instant validTo,
            Instant createdAt) {}
}
